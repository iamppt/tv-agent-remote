/*
 * HttpService.java —— 前台常驻服务 + 内嵌 HTTP 服务器(电视Agent 的"耳朵和嘴")
 *
 * 职责:
 *   · 以 specialUse 前台服务常驻,监听 0.0.0.0:9999,手机浏览器同局域网直连遥控
 *   · 路由一览:
 *       GET  /            → assets/index.html 遥控页(404 给纯文本)
 *       GET  /api/status  → 无障碍状态 + 屏幕文字 + 前台包名 topPkg
 *       POST /api/global  → 返回/主页(用户主动按,不做小爱守卫,由用户负责)
 *       POST /api/tap     → 合成点击(小爱守卫)
 *       POST /api/swipe   → 合成滑动(小爱守卫)
 *       POST /api/vol     → 音量 STREAM_MUSIC,返回 from→to(不动界面,不守卫)
 *       POST /api/text    → 往输入框灌字(小爱守卫);submit=true 时填完自动点「搜索」
 *       POST /api/click   → 按文字点击(小爱守卫)
 *       POST /api/screen  → 屏幕文字(只读,不守卫;GET/POST 都收)
 *       POST /api/apps    → 应用表安装情况(assets/apps.json;GET/POST 都收)
 *       POST /api/launch  → 打开应用(doLaunch 内守卫)
 *       POST /api/search  → 完整搜片流程(不盲按返回;doSearch 内守卫)
 *       POST /api/asr     → WAV 语音 → 硅基流动 Qwen3-Omni → 指令 JSON → 执行
 *       POST /api/textcmd → 文字指令 → 魔搭文本模型(设置页可选)→ 同一套解析与执行
 *       POST /api/exec    → 手动执行意图(语音降级通道,支持 {app,action,query} 或 {corrected})
 *       POST /api/player  → 播放控制:center/forward/backward/fullscreen/quality/pick
 *   · 应用配置外置:assets/apps.json(构建时由 apps.example.json 生成,不进 git),
 *     格式兼容两种:旧纯数组 [{name,pkg,...}] 或新 {"apps":[...],"specials":[...]};
 *     未配置时 /api/apps 返回空数组、/api/search|launch 报「apps.json 未配置」
 *   · 特例流程:apps.json 顶层 "specials":[{trigger,app,entry,match}],语音/文字指令的
 *     query 命中某条 trigger 时优先走 doSpecial(如「宝宝巴士」直达历史并播放)
 *   · 意图解析提示词按 apps.json 的应用名动态生成(getIntentPrompt)
 *   · 小爱互斥守卫 isXiaoAiOnTop():所有会动电视界面的操作前检查最上层窗口包名
 *     (含 voiceassist / aiassistant / miai 即视为小爱),命中则返回
 *     {"ok":false,"blocked":"xiaoi",...},与小爱完全互不干涉
 *   · 统一 CORS:Access-Control-Allow-Origin: *(手机 file:// 网页也能用),OPTIONS 直接 200
 *   · 所有网络/长流程都在 worker 线程;短操作 opLock 保序;搜片长流程 searchLock 串行
 *   · 响应一律 org.json 构造保证转义正确;每条路由 try/catch,单次请求失败不弄死服务
 */
package com.tv.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class HttpService extends Service {

    private static final String TAG = "HttpService";
    public static final int PORT = 9999;

    private static final String SF_URL   = "https://api.siliconflow.cn/v1/chat/completions";
    private static final String SF_MODEL = "Qwen/Qwen3-Omni-30B-A3B-Instruct";
    private static final String MS_URL   = "https://api-inference.modelscope.cn/v1/chat/completions";
    private static final String TEXT_MODEL_DEFAULT = "Qwen/Qwen3.8-Flash-Next";
    private static final String[] TEXT_MODEL_ALLOWED = {
            "Qwen/Qwen3.8-Flash-Next",
            "deepseek-ai/DeepSeek-V4.1-Flash-1017",
            "Qwen/Qwen3.5-35B-A3B"
    };

    private volatile boolean running = false;
    private ServerSocket serverSocket;

    /** 应用配置缓存:启动/首用时从 assets/apps.json 读一次 */
    private volatile List<AppRec> appList;
    /** 特例规则缓存:与 appList 同步加载 */
    private volatile List<SpecialRec> specialList;

    /** 短操作(点击/滑动/输入等)统一用这把锁保序 */
    private final Object opLock = new Object();
    /** 搜片等长流程串行锁(一次只跑一个长流程) */
    private final Object searchLock = new Object();

    /** 一条应用配置:显示名 / 包名 / 搜索入口文字 / 搜索提交按钮文字 */
    private static class AppRec {
        final String name, pkg, search, submit, homeMark;
        AppRec(String name, String pkg, String search, String submit, String homeMark) {
            this.name = name;
            this.pkg = pkg;
            this.search = search;
            this.submit = submit;
            this.homeMark = homeMark;
        }
    }

    /** 一条特例规则:trigger 命中关键词 / app 目标应用名 / entry 应用内入口文字 / match 要点的条目关键词 */
    private static class SpecialRec {
        final String trigger, app, entry, match;
        SpecialRec(String trigger, String app, String entry, String match) {
            this.trigger = trigger;
            this.app = app;
            this.entry = entry;
            this.match = match;
        }
    }

    // ------------------------------------------------ 服务生命周期

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        ensureAppList();   // 启动时读 apps.json(缺失/坏 JSON 只警告,不挡服务)
        startServer();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (serverSocket != null) serverSocket.close();
        } catch (IOException ignored) { }
        super.onDestroy();
    }

    /** 前台通知:channel id 固定「agent」,小图标 ic_stat,标题 电视Agent·遥控服务 */
    private void startFg() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder nb;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(
                    "agent", "电视Agent", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            nb = new Notification.Builder(this, "agent");
        } else {
            nb = new Notification.Builder(this);
        }
        nb.setSmallIcon(R.drawable.ic_stat)
          .setContentTitle("电视Agent·遥控服务")
          .setContentText("手机浏览器打开 http://<电视IP>:" + PORT)
          .setOngoing(true);
        // foregroundServiceType="specialUse" 写在 manifest 里,startForeground 自动带类型
        startForeground(1, nb.build());
    }

    // ------------------------------------------------ TCP 服务器

    private void startServer() {
        if (running) return;
        synchronized (searchLock) {
            if (running) return;
            running = true;
        }
        Thread t = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "agent-http-accept");
        t.start();
    }

    /** accept 循环:每个连接一个 worker 线程,操作语义上串行(opLock/searchLock) */
    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress("0.0.0.0", PORT));
            Log.i(TAG, "HTTP 服务已监听 0.0.0.0:" + PORT);
            while (running) {
                final Socket s;
                try {
                    s = serverSocket.accept();
                } catch (IOException e) {
                    break;   // 服务被关,正常退出
                }
                Thread w = new Thread(new Runnable() {
                    @Override public void run() { handleConnection(s); }
                }, "agent-http-worker");
                w.start();
            }
        } catch (Throwable e) {
            running = false;   // 下次 onStartCommand 可重试拉起
            Log.e(TAG, "HTTP 服务器退出", e);
        }
    }

    // ------------------------------------------------ 应用配置(assets/apps.json)

    /**
     * 读 assets/apps.json,兼容两种格式:
     *   旧:纯数组 [{"name":"显示名","pkg":"包名","search":"搜索入口","submit":"提交按钮"}]
     *   新:{"apps":[...同上...],"specials":[{"trigger","app","entry","match"}]}
     * 文件缺失/为空/坏 JSON → 空表(启动时也调一次,失败只警告不挡服务)。
     */
    private List<AppRec> getAppList() {
        List<AppRec> cached = appList;
        if (cached != null) return cached;
        synchronized (searchLock) {
            if (appList != null) return appList;
            List<AppRec> list = new ArrayList<>();
            List<SpecialRec> specials = new ArrayList<>();
            InputStream is = null;
            try {
                is = getAssets().open("apps.json");
                String raw = new String(readAllBytes(is), StandardCharsets.UTF_8).trim();
                JSONArray appArr, spArr = null;
                if (raw.startsWith("[")) {
                    appArr = new JSONArray(raw);   // 旧格式:纯数组,无特例
                } else {
                    JSONObject root = new JSONObject(raw);
                    appArr = root.optJSONArray("apps");
                    if (appArr == null) appArr = new JSONArray();
                    spArr = root.optJSONArray("specials");
                }
                for (int i = 0; i < appArr.length(); i++) {
                    JSONObject o = appArr.getJSONObject(i);
                    String name = o.optString("name", "").trim();
                    String pkg = o.optString("pkg", "").trim();
                    if (name.isEmpty() || pkg.isEmpty()) continue;
                    list.add(new AppRec(name, pkg,
                            o.optString("search", "搜索").trim(),
                            o.optString("submit", "搜索").trim(),
                            o.optString("homeMark", "").trim()));
                }
                if (spArr != null) {
                    for (int i = 0; i < spArr.length(); i++) {
                        JSONObject o = spArr.getJSONObject(i);
                        String trigger = o.optString("trigger", "").trim();
                        String app = o.optString("app", "").trim();
                        if (trigger.isEmpty() || app.isEmpty()) continue;
                        specials.add(new SpecialRec(trigger, app,
                                o.optString("entry", "").trim(),
                                o.optString("match", "").trim()));
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "apps.json 读取失败(视为未配置):" + msgOf(t));
            } finally {
                try { if (is != null) is.close(); } catch (IOException ignored) { }
            }
            appList = list;
            specialList = specials;
            return list;
        }
    }

    /** query 包含某条特例 trigger(包含匹配)时返回该条;否则 null(含无 query) */
    private SpecialRec matchSpecial(String query) {
        getAppList();   // 确保配置已加载
        if (query == null || query.isEmpty()) return null;
        List<SpecialRec> ss = specialList;
        if (ss == null) return null;
        for (SpecialRec s : ss) {
            if (s.trigger != null && !s.trigger.isEmpty() && query.contains(s.trigger)) return s;
        }
        return null;
    }

    /** 启动时预热应用表 */
    private void ensureAppList() {
        try {
            getAppList();
        } catch (Throwable ignored) { }
    }

    /**
     * 意图解析提示词(system 消息,语音/文字/手动降级三处共用):
     * 模型只许输出一个 JSON 对象;app 枚举按 apps.json 的应用名动态生成「应用A|应用B|none」,
     * 列表为空时枚举只有 none 并提示先配置。
     */
    private String getIntentPrompt() {
        StringBuilder names = new StringBuilder();
        for (AppRec a : getAppList()) {
            if (names.length() > 0) names.append('|');
            names.append(a.name);
        }
        boolean empty = names.length() == 0;
        String enumStr = empty ? "none" : names.append("|none").toString();
        String extra = empty ? "当前未配置任何应用(apps.json),app 一律填 none。" : "";
        return "你是电视遥控器的语音指令解析器。听懂用户语音后,只输出一个 JSON 对象,不要输出任何其他文字或解释。"
                + "JSON 格式:{\"corrected\":\"纠错后的完整语句\",\"app\":\"" + enumStr + "\","
                + "\"action\":\"search|launch|history|key|unknown\",\"query\":\"片名或参数,无则为空串\"}。"
                + "规则:用户要求找片/搜片/看某剧时 action=search、app 填对应应用(不确定则 none)、query 填片名;"
                + "要打开某个应用时 action=launch;要历史记录时 action=history;音量/频道/确认/返回等按键类 action=key;"
                + "听不清或无指令时 action=unknown,query 填空串。corrected 永远填纠错后的完整中文语句。" + extra;
    }

    // ------------------------------------------------ HTTP 解析

    private void handleConnection(Socket s) {
        try {
            s.setSoTimeout(20000);   // 读请求最多等 20s,防客户端挂着不说话
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String reqLine = readLine(in);
            if (reqLine == null || reqLine.trim().isEmpty()) return;
            String method = "GET", path = "/";
            String[] parts = reqLine.trim().split("\\s+");
            if (parts.length >= 1) method = parts[0].toUpperCase();
            if (parts.length >= 2) path = parts[1];
            String query = "";
            int q = path.indexOf('?');
            if (q >= 0) { query = path.substring(q + 1); path = path.substring(0, q); }

            // headers:只关心 Content-Length
            int contentLength = 0;
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().toLowerCase().equals("content-length")) {
                    try {
                        contentLength = Integer.parseInt(line.substring(c + 1).trim());
                    } catch (NumberFormatException ignored) { }
                }
            }

            // body:按 Content-Length 读尽(语音 base64 约 1MB,上限 8MB)
            byte[] body = null;
            if (contentLength > 0) {
                if (contentLength > 8 * 1024 * 1024) {
                    writeRaw(out, 413, "text/plain; charset=utf-8",
                            "payload too large".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                body = new byte[contentLength];
                readFully(in, body);
            }
            Log.d(TAG, method + " " + path);
            route(method, path, query, body, out);
        } catch (Throwable t) {
            Log.w(TAG, "连接处理异常", t);
        } finally {
            try { s.close(); } catch (IOException ignored) { }
        }
    }

    /** 读一行(CRLF 结尾);流结束返回 null */
    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        boolean any = false;
        int c;
        while ((c = in.read()) >= 0) {
            any = true;
            if (c == '\n') break;
            if (c != '\r') sb.append((char) c);
            if (sb.length() > 8192) break;   // 防异常超长行
        }
        return any ? sb.toString() : null;
    }

    private static void readFully(InputStream in, byte[] buf) throws Exception {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("客户端提前断开,body 不足 " + buf.length);
            off += n;
        }
    }

    private static byte[] readAllBytes(InputStream is) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) bo.write(buf, 0, n);
        return bo.toByteArray();
    }

    // ------------------------------------------------ 响应输出(统一 CORS)

    private void respond(OutputStream out, int code, String json) throws Exception {
        writeRaw(out, code, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    private void writeRaw(OutputStream out, int code, String ctype, byte[] data) throws Exception {
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\n"
                + "Content-Type: " + ctype + "\r\n"
                + "Content-Length: " + data.length + "\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n"
                + "Access-Control-Allow-Headers: Content-Type\r\n"
                + "Cache-Control: no-store\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            default:  return "Status";
        }
    }

    // ------------------------------------------------ 路由

    private void route(String method, String path, String query, byte[] body, OutputStream out) {
        try {
            // CORS 预检直接放行
            if ("OPTIONS".equals(method)) {
                respond(out, 200, "{\"ok\":true}");
                return;
            }
            // 遥控页
            if ("GET".equals(method) && ("/".equals(path) || "/index.html".equals(path))) {
                serveIndex(out);
                return;
            }
            if (!path.startsWith("/api/")) {
                writeRaw(out, 404, "text/plain; charset=utf-8",
                        ("not found: " + path).getBytes(StandardCharsets.UTF_8));
                return;
            }

            JSONObject req;
            try {
                req = (body == null || body.length == 0)
                        ? new JSONObject()
                        : new JSONObject(new String(body, StandardCharsets.UTF_8));
            } catch (Throwable t) {
                throw new IllegalArgumentException("请求 JSON 解析失败:" + t.getMessage());
            }

            if ("GET".equals(method) && "/api/status".equals(path))  { handleStatus(out, query != null && query.contains("screen=1")); return; }
            if ("/api/apps".equals(path))   { handleApps(out); return; }    // GET/POST 都收(页面用 GET)
            if ("/api/screen".equals(path)) { handleScreen(out); return; }  // GET/POST 都收
            if ("POST".equals(method) && "/api/global".equals(path)) { handleGlobal(req, out); return; }
            if ("POST".equals(method) && "/api/tap".equals(path))    { handleTap(req, out); return; }
            if ("POST".equals(method) && "/api/swipe".equals(path))  { handleSwipe(req, out); return; }
            if ("POST".equals(method) && "/api/vol".equals(path))    { handleVol(req, out); return; }
            if ("POST".equals(method) && "/api/text".equals(path))   { handleText(req, out); return; }
            if ("POST".equals(method) && "/api/click".equals(path))  { handleClick(req, out); return; }
            if ("POST".equals(method) && "/api/launch".equals(path)) { handleLaunch(req, out); return; }
            if ("POST".equals(method) && "/api/search".equals(path)) { handleSearch(req, out); return; }
            if ("POST".equals(method) && "/api/asr".equals(path))    { handleAsr(req, out); return; }
            if ("POST".equals(method) && "/api/textcmd".equals(path)) { handleTextCmd(req, out); return; }
            if ("POST".equals(method) && "/api/exec".equals(path))   { handleExec(req, out); return; }
            if ("POST".equals(method) && "/api/player".equals(path)) { handlePlayer(req, out); return; }

            writeRaw(out, 404, "text/plain; charset=utf-8",
                    ("not found: " + path).getBytes(StandardCharsets.UTF_8));
        } catch (Throwable t) {
            // 每条路由的兜底:统一 {"ok":false,"error":...},不让请求弄死服务
            Log.w(TAG, "处理 " + path + " 失败", t);
            try {
                JSONObject e = new JSONObject();
                e.put("ok", false);
                e.put("error", String.valueOf(t.getMessage() != null ? t.getMessage() : t));
                respond(out, 200, e.toString());
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------ 各路由处理

    private void serveIndex(OutputStream out) throws Exception {
        InputStream is = null;
        try {
            is = getAssets().open("index.html");
            writeRaw(out, 200, "text/html; charset=utf-8", readAllBytes(is));
        } catch (IOException e) {
            writeRaw(out, 404, "text/plain; charset=utf-8",
                    "index.html 未打包进 APK".getBytes(StandardCharsets.UTF_8));
        } finally {
            try { if (is != null) is.close(); } catch (IOException ignored) { }
        }
    }

    /** GET /api/status:无障碍状态 + 前台包名。screen 列表只在 ?screen=1 时算
     *  (读屏要逐节点 IPC,这台电视 ~50ms/节点,默认路径必须零遍历,轮询才不卡) */
    private void handleStatus(OutputStream out, boolean withScreen) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        AgentService a = AgentService.instance;
        r.put("a11y", a != null);
        JSONArray sc = new JSONArray();
        String topPkg = "";
        if (a != null) {
            if (withScreen) {
                try {
                    for (String s : a.screenTexts(10)) sc.put(s);
                } catch (Throwable ignored) { }
            }
            try {
                topPkg = a.topWindowPackage() == null ? "" : a.topWindowPackage();
            } catch (Throwable ignored) { }
        }
        r.put("screen", sc);
        r.put("topPkg", topPkg);
        respond(out, 200, r.toString());
    }

    /** POST /api/global {action:"back"|"home"} —— 用户主动按的,不做小爱守卫 */
    private void handleGlobal(JSONObject req, OutputStream out) throws Exception {
        String action = req.optString("action", "");
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        boolean ok;
        if ("back".equals(action)) ok = a.back();
        else if ("home".equals(action)) ok = a.home();
        else throw new IllegalArgumentException("action 只支持 back/home");
        JSONObject r = new JSONObject();
        r.put("ok", ok);
        r.put("action", action);
        respond(out, 200, r.toString());
    }

    /** POST /api/tap {x,y} */
    private void handleTap(JSONObject req, OutputStream out) throws Exception {
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) { respond(out, 200, blocked.toString()); return; }
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        int x = needInt(req, "x"), y = needInt(req, "y");
        boolean ok;
        synchronized (opLock) { ok = a.tap(x, y); }
        JSONObject r = new JSONObject();
        r.put("ok", ok);
        respond(out, 200, r.toString());
    }

    /** POST /api/swipe {x1,y1,x2,y2,dur} */
    private void handleSwipe(JSONObject req, OutputStream out) throws Exception {
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) { respond(out, 200, blocked.toString()); return; }
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        int x1 = needInt(req, "x1"), y1 = needInt(req, "y1");
        int x2 = needInt(req, "x2"), y2 = needInt(req, "y2");
        long dur = req.optLong("dur", 120);
        boolean ok;
        synchronized (opLock) { ok = a.swipe(x1, y1, x2, y2, dur); }
        JSONObject r = new JSONObject();
        r.put("ok", ok);
        respond(out, 200, r.toString());
    }

    /** POST /api/vol {delta}:STREAM_MUSIC 音量,返回 from→to;异常不 crash */
    private void handleVol(JSONObject req, OutputStream out) throws Exception {
        int delta = req.optInt("delta", 1);
        JSONObject r = new JSONObject();
        try {
            AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            int from = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int to = Math.max(0, Math.min(max, from + delta));
            if (to != from) am.setStreamVolume(AudioManager.STREAM_MUSIC, to, 0);
            int after = am.getStreamVolume(AudioManager.STREAM_MUSIC);
            r.put("ok", true);
            r.put("from", from);
            r.put("to", after);
            r.put("max", max);
        } catch (Throwable t) {
            r.put("ok", false);
            r.put("error", "音量操作失败:" + (t.getMessage() != null ? t.getMessage() : t.toString()));
        }
        respond(out, 200, r.toString());
    }

    /** POST /api/text {value, submit?}:填输入框;submit=true 填完自动点「搜索」并回屏幕 */
    private void handleText(JSONObject req, OutputStream out) throws Exception {
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) { respond(out, 200, blocked.toString()); return; }
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        String value = req.optString("value", "");
        boolean submit = req.optBoolean("submit", false);
        JSONObject r = new JSONObject();
        StringBuilder typed = new StringBuilder();
        synchronized (opLock) {
            boolean ok = a.setTextIntoField(value, typed);
            r.put("ok", ok);
            r.put("typed", typed.toString());   // 框内最终文字,给前端确认
            if (ok && submit) {
                a.clickText("搜索");            // 点搜索按钮提交
                sleepQuiet(3000);
                r.put("submitted", true);
                r.put("screen", screenArr(a, 10));
            }
        }
        respond(out, 200, r.toString());
    }

    /** POST /api/click {text} */
    private void handleClick(JSONObject req, OutputStream out) throws Exception {
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) { respond(out, 200, blocked.toString()); return; }
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        String text = req.optString("text", "");
        if (text.isEmpty()) throw new IllegalArgumentException("缺少 text");
        boolean ok;
        synchronized (opLock) { ok = a.clickText(text); }
        JSONObject r = new JSONObject();
        r.put("ok", ok);
        respond(out, 200, r.toString());
    }

    /** POST /api/screen:屏幕文字 20 条(只读,不守卫) */
    private void handleScreen(OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("screen", screenArr(AgentService.instance, 20));
        respond(out, 200, r.toString());
    }

    /** /api/apps:应用表(assets/apps.json)+ 逐个探测安装情况;未配置返回空数组 */
    private void handleApps(OutputStream out) throws Exception {
        JSONArray arr = new JSONArray();
        PackageManager pm = getPackageManager();
        for (AppRec app : getAppList()) {
            JSONObject o = new JSONObject();
            o.put("name", app.name);
            o.put("pkg", app.pkg);
            boolean installed = false;
            try {
                installed = pm.getLaunchIntentForPackage(app.pkg) != null;
            } catch (Throwable ignored) { }
            o.put("installed", installed);
            arr.put(o);
        }
        JSONObject r = new JSONObject();
        r.put("ok", true);
        r.put("apps", arr);
        respond(out, 200, r.toString());
    }

    /** 应用表没配置 / 没找到时的统一报错文案 */
    private String appsTableError(String name) {
        if (getAppList().isEmpty()) {
            return "apps.json 未配置,把模板填上你电视上装的应用";
        }
        return "应用表里没有「" + name + "」(查 assets/apps.json)";
    }

    /** POST /api/launch {app} */
    private void handleLaunch(JSONObject req, OutputStream out) throws Exception {
        String name = req.optString("app", "");
        AppRec app = resolveApp(name);
        if (app == null) throw new IllegalArgumentException(appsTableError(name));
        respond(out, 200, doLaunch(app).toString());
    }

    /** POST /api/search {app,query,autoplay?}:完整搜片长流程;autoplay 缺省 true(页面设置可关) */
    private void handleSearch(JSONObject req, OutputStream out) throws Exception {
        String name = req.optString("app", "");
        String query = req.optString("query", "").trim();
        if (query.isEmpty()) throw new IllegalArgumentException("缺少 query");
        AppRec app = resolveApp(name);
        if (app == null) throw new IllegalArgumentException(appsTableError(name));
        boolean autoplay = req.optBoolean("autoplay", true);
        respond(out, 200, doSearch(app, query, autoplay).toString());
    }

    /** POST /api/asr {audio_base64,mime}:WAV → 硅基流动 Omni → 意图 → 执行 */
    private void handleAsr(JSONObject req, OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        try {
            String b64 = req.optString("audio_base64", "").trim();
            if (b64.isEmpty()) throw new IllegalArgumentException("没有收到音频(audio_base64 为空)");
            int comma = b64.indexOf(',');
            if (b64.startsWith("data:") && comma > 0) b64 = b64.substring(comma + 1).trim();
            byte[] wav = Base64.decode(b64, Base64.DEFAULT);
            checkWav(wav);   // 非 PCM / 头损坏直接抛
            String key = readKey("SILICONFLOW_API_KEY");
            if (key.isEmpty()) {
                throw new IllegalStateException("未配置 SILICONFLOW_API_KEY(构建时注入 assets/key.txt)");
            }
            // 组 Omni 的多模态消息:text + audio_url(data:audio/wav;base64)
            JSONArray content = new JSONArray();
            content.put(new JSONObject()
                    .put("type", "text")
                    .put("text", "请识别这段语音并输出指令JSON。"));
            String audioUrl = "data:audio/wav;base64,"
                    + Base64.encodeToString(wav, Base64.NO_WRAP);
            content.put(new JSONObject()
                    .put("type", "audio_url")
                    .put("audio_url", new JSONObject().put("url", audioUrl)));
            JSONObject intent = parseIntoIntent(callChat(SF_URL, key, SF_MODEL, content, 20000));
            r.put("result", intent);
            executeIntent(r, intent);   // 公共执行逻辑,小爱守卫在里面
            r.put("ok", true);
            r.put("path", "omni");
        } catch (Throwable t) {
            // 语音这条链路失败不报 HTTP 错,前端按 path=failed 显示
            r = new JSONObject();
            r.put("ok", true);
            r.put("path", "failed");
            r.put("error", "语音处理失败:" + msgOf(t));
        }
        respond(out, 200, r.toString());
    }

    /** POST /api/textcmd {text}:文字指令 → 魔搭 Qwen3.5 → 意图 → 执行 */
    private void handleTextCmd(JSONObject req, OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        try {
            String text = req.optString("text", "").trim();
            if (text.isEmpty()) throw new IllegalArgumentException("text 为空");
            String key = readKey("MODELSCOPE_API_KEY");
            if (key.isEmpty()) {
                throw new IllegalStateException("未配置 MODELSCOPE_API_KEY(构建时注入 assets/key.txt)");
            }
            String model = req.optString("model", TEXT_MODEL_DEFAULT).trim();
            boolean allowed = false;
            for (String m : TEXT_MODEL_ALLOWED) if (m.equals(model)) { allowed = true; break; }
            if (!allowed) model = TEXT_MODEL_DEFAULT;
            String user = "语音转写文本:" + text + "\n请输出指令JSON。";
            JSONObject intent = parseIntoIntent(callChat(MS_URL, key, model, user, 40000));
            r.put("result", intent);
            r.put("model", model);
            executeIntent(r, intent);   // 与语音段共用同一套执行逻辑
            r.put("ok", true);
            r.put("path", "textcmd");
        } catch (Throwable t) {
            r = new JSONObject();
            r.put("ok", false);
            r.put("path", "failed");
            r.put("error", "文字指令失败:" + msgOf(t));
        }
        respond(out, 200, r.toString());
    }

    /**
     * POST /api/exec:手动执行意图(语音降级通道)。
     * 两种入参:{app,action,query(,corrected)} 直接执行;或只有 {corrected} →
     * 走魔搭文本通道把整句话解析成意图再执行。
     */
    private void handleExec(JSONObject req, OutputStream out) throws Exception {
        JSONObject r = new JSONObject();
        try {
            if (req.has("app") && req.has("action")) {
                JSONObject intent = new JSONObject();
                intent.put("corrected", req.optString("corrected", ""));
                intent.put("app", req.optString("app", "none"));
                intent.put("action", req.optString("action", "unknown"));
                intent.put("query", req.optString("query", ""));
                r.put("result", intent);
                executeIntent(r, intent);
                r.put("ok", true);
                r.put("path", "manual");
            } else if (!req.optString("corrected", "").trim().isEmpty()) {
                String key = readKey("MODELSCOPE_API_KEY");
                if (key.isEmpty()) {
                    throw new IllegalStateException("未配置 MODELSCOPE_API_KEY(构建时注入 assets/key.txt)");
                }
                JSONObject intent = parseIntoIntent(callChat(MS_URL, key, TEXT_MODEL_DEFAULT,
                        "语音转写文本:" + req.optString("corrected", "").trim() + "\n请输出指令JSON。", 40000));
                r.put("result", intent);
                executeIntent(r, intent);
                r.put("ok", true);
                r.put("path", "omni-text");
            } else {
                throw new IllegalArgumentException("需要 {app,action,query} 或 {corrected}");
            }
        } catch (Throwable t) {
            r = new JSONObject();
            r.put("ok", true);
            r.put("path", "failed");
            r.put("error", "执行失败:" + msgOf(t));
        }
        respond(out, 200, r.toString());
    }

    /** POST /api/player {action,...}:播放控制统一端点 */
    private void handlePlayer(JSONObject req, OutputStream out) throws Exception {
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) { respond(out, 200, blocked.toString()); return; }
        AgentService a = AgentService.instance;
        if (a == null) throw new IllegalStateException("电视上未开启无障碍服务");
        String action = req.optString("action", "");
        JSONObject r = new JSONObject();
        r.put("action", action);
        synchronized (opLock) {
            if ("center".equals(action)) {
                // 屏幕中央:唤出/隐藏播放器控制条
                r.put("ok", a.tap(960, 540));
            } else if ("forward".equals(action)) {
                // 中央水平拖动快进(TVBox 内核播放器普遍支持)
                r.put("ok", a.swipe(960, 540, 1560, 540, 600));
            } else if ("backward".equals(action)) {
                r.put("ok", a.swipe(960, 540, 360, 540, 600));
            } else if ("fullscreen".equals(action)) {
                // 先找「全屏」按钮,找不到点右下角兜底
                boolean ok = a.clickText("全屏");
                if (!ok) ok = a.tap(1870, 1030);
                r.put("ok", ok);
            } else if ("quality".equals(action)) {
                boolean ok = a.clickText("切换清晰度") || a.clickText("清晰度") || a.clickText("线路");
                sleepQuiet(1000);   // 等选项列表弹出
                r.put("ok", ok);
                r.put("screen", screenArr(a, 15));   // 让手机端展示清晰度/线路选项
                if (!ok) r.put("error", "没找到清晰度/线路入口");
            } else if ("pick".equals(action)) {
                String text = req.optString("text", "").trim();
                if (text.isEmpty()) throw new IllegalArgumentException("pick 需要 text");
                r.put("ok", a.clickText(text));
            } else {
                throw new IllegalArgumentException(
                        "action 只支持 center/forward/backward/fullscreen/quality/pick");
            }
        }
        respond(out, 200, r.toString());
    }

    // ------------------------------------------------ 小爱互斥守卫

    /** 最上层窗口包名是否是小爱(voiceassist / aiassistant / miai,大小写不敏感) */
    private boolean isXiaoAiOnTop() {
        AgentService a = AgentService.instance;
        if (a == null) return false;
        String top = a.topWindowPackage();
        if (top == null || top.isEmpty()) return false;
        String p = top.toLowerCase();
        return p.contains("voiceassist") || p.contains("aiassistant") || p.contains("miai");
    }

    /**
     * 守卫:小爱在前台时返回 {"ok":false,"blocked":"xiaoi",error:...},调用方直接回给前端;
     * 检测不到小爱返回 null(继续原流程)。检测不到窗口(返回 null 包名)不拦,放行。
     */
    private JSONObject xiaoiBlocked() {
        AgentService a = AgentService.instance;
        if (a == null) return null;   // 无障碍没开,交给各 handler 自己报错
        String top = a.topWindowPackage();
        if (top == null || top.isEmpty()) return null;
        String p = top.toLowerCase();
        if (p.contains("voiceassist") || p.contains("aiassistant") || p.contains("miai")) {
            try {
                JSONObject r = new JSONObject();
                r.put("ok", false);
                r.put("blocked", "xiaoi");
                r.put("error", "电视上小爱正在使用,请先关闭小爱再操作");
                r.put("topPkg", top);
                return r;
            } catch (Throwable ignored) { }
        }
        return null;
    }

    // ------------------------------------------------ 业务:launch / search

    /** 按名字(或直接给包名)解析应用表;找不到返回 null */
    private AppRec resolveApp(String nameOrPkg) {
        if (nameOrPkg == null || nameOrPkg.isEmpty()) return null;
        for (AppRec a : getAppList()) {
            if (a.name.equals(nameOrPkg) || a.pkg.equals(nameOrPkg)) return a;
        }
        return null;
    }

    /** 打开应用(NEW_TASK);小爱在前台时返回 blocked 结果 */
    private JSONObject doLaunch(AppRec app) throws Exception {
        JSONObject r = new JSONObject();
        JSONObject blocked = xiaoiBlocked();
        if (blocked != null) return blocked;
        PackageManager pm = getPackageManager();
        Intent it = pm.getLaunchIntentForPackage(app.pkg);
        if (it == null) {
            r.put("ok", false);
            r.put("error", app.name + " 未安装");
            return r;
        }
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        synchronized (opLock) {
            startActivity(it);
        }
        r.put("ok", true);
        r.put("message", "已打开 " + app.name);
        return r;
    }

    /** 兼容旧调用(语音/文字指令路径):自动播放默认开 */
    private JSONObject doSearch(AppRec app, String query) throws Exception {
        return doSearch(app, query, true);
    }

    /**
     * 完整搜片流程(不盲按返回键,小爱浮层已在守卫处拦截):
     *   a. 守卫 + 打开应用(NEW_TASK),sleep 2s 等冷启动
     *   b. 轮询最多 25s(间隔 1s):clickText(搜索入口文字,来自 apps.json 的 search 字段)
     *      成功即进搜索页(clickText 精确匹配优先,不会误点「搜索历史」);
     *      点击后 sleep 2.5s 等搜索页就位
     *   c. 轮询最多 10s 等 setTextIntoField(query) 成功(搜索框加载慢的应用靠轮询兜住)
     *   d. clickText(提交按钮文字,来自 apps.json 的 submit 字段),sleep 4s 等结果
     *   e. 返回 screenTexts(10)
     *   f. autoplay=true 时接自动播放链(见 autoplayFirst,失败不影响搜片结果)
     * 任一步失败返回 {ok:false,error:"没找到搜索入口",steps,screen}。
     */
    private JSONObject doSearch(AppRec app, String query, boolean autoplay) throws Exception {
        JSONObject r = new JSONObject();
        JSONArray steps = new JSONArray();
        r.put("steps", steps);
        r.put("app", app.name);
        r.put("query", query);
        AgentService a = AgentService.instance;
        if (a == null) {
            r.put("ok", false);
            r.put("error", "电视上未开启无障碍服务");
            return r;
        }
        synchronized (searchLock) {   // 长流程串行,避免两次搜片互相踩
            // 小爱守卫:不与小爱同学互相干涉
            JSONObject blocked = xiaoiBlocked();
            if (blocked != null) return blocked;
            try {
                // a. 打开应用
                PackageManager pm = getPackageManager();
                Intent it = pm.getLaunchIntentForPackage(app.pkg);
                if (it == null) {
                    r.put("ok", false);
                    r.put("error", app.name + " 未安装");
                    return r;
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(it);
                steps.put("打开 " + app.name);
                sleepQuiet(2000);

                // a2. 退回应用首页:已在应用内(比如上次停在搜索结果页)时,
                //     在应用自身里按返回退到 homeMark 标志页,不盲按(小爱守卫在入口已拦)。
                //     homeMark 未配置的应用跳过此步。
                if (app.homeMark != null && !app.homeMark.isEmpty()) {
                    for (int i = 0; i < 6; i++) {
                        List<String> texts = a.screenTexts(20);
                        boolean onHome = false;
                        for (String t : texts) if (t.contains(app.homeMark)) { onHome = true; break; }
                        if (onHome) { steps.put("已回到" + app.name + "首页"); break; }
                        a.back();
                        sleepQuiet(1200);
                    }
                }

                // b. 轮询点搜索入口(文字来自 apps.json 的 search 字段)
                boolean entered = false;
                long deadline = System.currentTimeMillis() + 25000;
                while (System.currentTimeMillis() < deadline) {
                    if (a.clickText(app.search)) { entered = true; break; }
                    sleepQuiet(1000);
                }
                if (!entered) {
                    r.put("ok", false);
                    r.put("error", "没找到搜索入口");
                    r.put("screen", screenArr(a, 10));
                    return r;
                }
                steps.put("点开「" + app.search + "」");
                sleepQuiet(2500);

                // c. 轮询等输入框就绪并灌字
                boolean typed = false;
                StringBuilder typedText = new StringBuilder();
                deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    if (a.setTextIntoField(query, typedText)) { typed = true; break; }
                    sleepQuiet(1000);
                }
                if (!typed) {
                    r.put("ok", false);
                    r.put("error", "没找到搜索入口");
                    r.put("screen", screenArr(a, 10));
                    return r;
                }
                steps.put("输入「" + query + "」");

                // d. 提交(文字来自 apps.json 的 submit 字段)
                a.clickText(app.submit);
                steps.put("点「" + app.submit + "」提交");
                sleepQuiet(4000);

                // e. 回屏幕文字
                r.put("ok", true);
                r.put("message", "已在" + app.name + "搜索「" + query + "」");
                r.put("screen", screenArr(a, 10));

                // f. 自动播放(可选):失败只追加 steps,不推翻搜片本身的 ok
                if (autoplay) autoplayFirst(r, a, steps, query);
                return r;
            } catch (Throwable t) {
                r.put("ok", false);
                r.put("error", "搜片执行异常:" + msgOf(t));
                try { r.put("screen", screenArr(a, 10)); } catch (Throwable ignored) { }
                return r;
            }
        }
    }

    // ------------------------------------------------ 自动播放链(搜片成功后可选)

    /**
     * 搜片成功后的自动播放链(通用动作,不做更多猜测):
     *   ① sleep 3s 等结果屏稳定
     *   ② 点开第一个结果卡片:先 clickText 含「┃」的文字(线路卡片特征),
     *      失败则取 screenTexts 里第一条不含「全部显示」「搜索」「query」的条目 tryClick
     *   ③ sleep 4s 等进详情页
     *   ④ clickText("播放");找不到节点就 tap 屏幕中心偏下 (960,700)
     *   ⑤ sleep 5s 等起播
     *   ⑥ 全屏:clickText("全屏"),失败 tap(1870,1030)
     * 任何一步失败立即停止,已走步骤留在 steps,并回当前 screen;不报错不崩溃。
     */
    private void autoplayFirst(JSONObject r, AgentService a, JSONArray steps, String query) {
        try {
            // ① 等结果屏稳定
            sleepQuiet(3000);
            // ② 点开第一个结果卡片
            boolean opened = a.clickText("┃");
            if (opened) {
                steps.put("自动播放:点开第一个线路卡片");
            } else {
                String pick = null;
                List<String> texts = a.screenTexts(20);
                for (String t : texts) {
                    if (t == null || t.isEmpty()) continue;
                    if (t.contains("全部显示") || t.contains("搜索")) continue;
                    if (query != null && !query.isEmpty() && t.contains(query)) continue;   // 跳过搜索框里的片名
                    pick = t;
                    break;
                }
                if (pick == null || !a.clickText(pick)) {
                    steps.put("自动播放中断:没找到可点的结果卡片");
                    r.put("screen", screenArr(a, 10));
                    return;
                }
                steps.put("自动播放:点开「" + truncate(pick, 12) + "」");
            }
            // ③ 等进详情页
            sleepQuiet(4000);
            // ④ 点播放,找不到节点改点屏幕中心偏下
            if (a.clickText("播放")) {
                steps.put("自动播放:点「播放」");
            } else {
                a.tap(960, 700);
                steps.put("自动播放:点「播放」失败,改点屏幕中央偏下");
            }
            // ⑤ 等起播
            sleepQuiet(5000);
            // ⑥ 全屏
            if (a.clickText("全屏")) {
                steps.put("自动播放:全屏");
            } else {
                a.tap(1870, 1030);
                steps.put("自动播放:全屏按钮没找到,点右下角");
            }
            r.put("screen", screenArr(a, 10));
        } catch (Throwable t) {
            try {
                steps.put("自动播放中断:" + msgOf(t));
                r.put("screen", screenArr(a, 10));
            } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------ 特例流程(宝宝巴士等,规则在 apps.json specials)

    /**
     * 特例流程:小爱守卫 → 打开 app → sleep 4s → 轮询点入口(entry,10s)→ sleep 2s
     * → 轮询点含 match 的条目(10s)→ sleep 4s → 屏上有「播放」就点 → sleep 5s → 全屏
     * (同自动播放链:找不到「全屏」按钮点右下角)。
     * 任一步失败返回 {ok:false,error,steps,screen},不崩溃。
     */
    private JSONObject doSpecial(SpecialRec sp) throws Exception {
        JSONObject r = new JSONObject();
        JSONArray steps = new JSONArray();
        r.put("steps", steps);
        r.put("special", sp.trigger);
        AgentService a = AgentService.instance;
        if (a == null) {
            r.put("ok", false);
            r.put("error", "电视上未开启无障碍服务");
            return r;
        }
        AppRec app = resolveApp(sp.app);
        if (app == null) {
            r.put("ok", false);
            r.put("error", "特例「" + sp.trigger + "」:" + appsTableError(sp.app));
            return r;
        }
        synchronized (searchLock) {   // 长流程串行,与搜片互斥
            JSONObject blocked = xiaoiBlocked();
            if (blocked != null) return blocked;
            try {
                PackageManager pm = getPackageManager();
                Intent it = pm.getLaunchIntentForPackage(app.pkg);
                if (it == null) {
                    r.put("ok", false);
                    r.put("error", app.name + " 未安装");
                    return r;
                }
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                synchronized (opLock) {
                    startActivity(it);
                }
                steps.put("打开 " + app.name);
                sleepQuiet(4000);

                // 轮询点应用内入口(如「历史」),最多 10s
                boolean entered = false;
                long deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    if (sp.entry.isEmpty() || a.clickText(sp.entry)) { entered = true; break; }
                    sleepQuiet(1000);
                }
                if (!entered) {
                    r.put("ok", false);
                    r.put("error", "没找到入口「" + sp.entry + "」");
                    r.put("screen", screenArr(a, 10));
                    return r;
                }
                steps.put("点开「" + sp.entry + "」");
                sleepQuiet(2000);

                // 轮询点含 match 的第一个条目,最多 10s
                boolean picked = false;
                deadline = System.currentTimeMillis() + 10000;
                while (System.currentTimeMillis() < deadline) {
                    if (a.clickText(sp.match)) { picked = true; break; }
                    sleepQuiet(1000);
                }
                if (!picked) {
                    r.put("ok", false);
                    r.put("error", "没找到含「" + sp.match + "」的条目");
                    r.put("screen", screenArr(a, 10));
                    return r;
                }
                steps.put("点开含「" + sp.match + "」的条目");
                sleepQuiet(4000);

                // 屏上有「播放」就点,没有则跳过
                boolean hasPlay = false;
                for (String t : a.screenTexts(15)) {
                    if (t != null && t.contains("播放")) { hasPlay = true; break; }
                }
                if (hasPlay) {
                    a.clickText("播放");
                    steps.put("点「播放」");
                } else {
                    steps.put("屏上无「播放」,跳过");
                }
                sleepQuiet(5000);

                // 全屏:找不到按钮点右下角
                if (a.clickText("全屏")) {
                    steps.put("全屏");
                } else {
                    a.tap(1870, 1030);
                    steps.put("全屏按钮没找到,点右下角");
                }
                r.put("ok", true);
                r.put("message", "已通过特例「" + sp.trigger + "」打开 " + app.name);
                r.put("screen", screenArr(a, 10));
                return r;
            } catch (Throwable t) {
                r.put("ok", false);
                r.put("error", "特例执行异常:" + msgOf(t));
                try { r.put("screen", screenArr(a, 10)); } catch (Throwable ignored) { }
                return r;
            }
        }
    }

    // ------------------------------------------------ 意图解析与执行(语音/文字共用)

    /**
     * 公共执行:把 {corrected,app,action,query} 变成动作。
     * 特例优先:action=search/launch 且 query 含某条 specials.trigger → doSpecial;
     * search(且 query 非空、app 在表内)→ doSearch;launch(app 在表内)→ doLaunch;
     * 其余不执行,message=「识别到:...」。小爱守卫由 doSearch/doLaunch/doSpecial 内部完成,
     * blocked 字段原样透传给前端。
     */
    private void executeIntent(JSONObject r, JSONObject intent) throws Exception {
        String action = intent.optString("action", "");
        String app = intent.optString("app", "");
        String query = intent.optString("query", "").trim();
        String corrected = intent.optString("corrected", "");

        // 特例优先于普通搜片:search/launch 且 query 命中某条 trigger(如「宝宝巴士」)
        if (("search".equals(action) || "launch".equals(action)) && !query.isEmpty()) {
            SpecialRec sp = matchSpecial(query);
            if (sp != null) {
                JSONObject sr = doSpecial(sp);
                if ("xiaoi".equals(sr.optString("blocked", ""))) r.put("blocked", "xiaoi");
                r.put("executed", sr.optBoolean("ok", false));
                r.put("message", sr.optBoolean("ok", false)
                        ? sr.optString("message", "")
                        : sr.optString("error", "特例执行失败"));
                if (sr.has("steps")) r.put("steps", sr.get("steps"));
                if (sr.has("screen")) r.put("screen", sr.get("screen"));
                return;
            }
        }

        if ("search".equals(action) && !query.isEmpty()) {
            AppRec rec = resolveApp(app);
            if (rec == null) {
                r.put("executed", false);
                r.put("message", "识别到:「" + corrected + "」;" + appsTableError(app) + ",没执行");
            } else {
                JSONObject sr = doSearch(rec, query);
                if ("xiaoi".equals(sr.optString("blocked", ""))) r.put("blocked", "xiaoi");
                r.put("executed", sr.optBoolean("ok", false));
                r.put("message", sr.optBoolean("ok", false)
                        ? sr.optString("message", "")
                        : sr.optString("error", "搜索失败"));
                if (sr.has("steps")) r.put("steps", sr.get("steps"));
                if (sr.has("screen")) r.put("screen", sr.get("screen"));
            }
        } else if ("launch".equals(action)) {
            AppRec rec = resolveApp(app);
            if (rec == null) {
                r.put("executed", false);
                r.put("message", "识别到:「" + corrected + "」;" + appsTableError(app) + ",没执行");
            } else {
                JSONObject lr = doLaunch(rec);
                if ("xiaoi".equals(lr.optString("blocked", ""))) r.put("blocked", "xiaoi");
                r.put("executed", lr.optBoolean("ok", false));
                r.put("message", lr.optBoolean("ok", false)
                        ? lr.optString("message", "")
                        : lr.optString("error", "打开失败"));
            }
        } else {
            r.put("executed", false);
            r.put("message", corrected.isEmpty() ? "没听出可执行的指令" : "识别到:「" + corrected + "」");
        }
    }

    /** 模型返回文本 → 剥 ```json 围栏 → 截取 {...} → 解析意图,缺省补空串 */
    private JSONObject parseIntoIntent(String content) throws Exception {
        String s = content == null ? "" : content.trim();
        if (s.startsWith("```")) {   // 剥 markdown 围栏
            int nl = s.indexOf('\n');
            s = nl >= 0 ? s.substring(nl + 1) : "";
            int end = s.lastIndexOf("```");
            if (end >= 0) s = s.substring(0, end);
            s = s.trim();
        }
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a >= 0 && b > a) s = s.substring(a, b + 1);
        JSONObject j = new JSONObject(s);
        JSONObject r = new JSONObject();
        r.put("corrected", j.optString("corrected", ""));
        r.put("app", j.optString("app", ""));
        r.put("action", j.optString("action", ""));
        r.put("query", j.optString("query", ""));
        return r;
    }

    // ------------------------------------------------ 大模型调用(硅基流动 / 魔搭共用)

    /**
     * 调 chat/completions:userContent 传 JSONArray(多模态 audio_url)或 String(纯文本)。
     * system 用 getIntentPrompt()(app 枚举随 apps.json 动态);temperature 0.1,max_tokens 200。
     * 返回 choices[0].message.content;HTTP>=400 或空内容抛异常。
     */
    private String callChat(String url, String key, String model, Object userContent, int timeoutMs) throws Exception {
        JSONObject bodyObj = new JSONObject();
        bodyObj.put("model", model);
        bodyObj.put("temperature", 0.1);
        bodyObj.put("max_tokens", 200);
        JSONArray messages = new JSONArray();
        messages.put(new JSONObject().put("role", "system").put("content", getIntentPrompt()));
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        bodyObj.put("messages", messages);
        byte[] payload = bodyObj.toString().getBytes(StandardCharsets.UTF_8);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestMethod("POST");
            c.setRequestProperty("Authorization", "Bearer " + key);
            c.setRequestProperty("Content-Type", "application/json");
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(payload);
            os.flush();
            os.close();
            int code = c.getResponseCode();
            InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String resp = is == null ? "" : new String(readAllBytes(is), StandardCharsets.UTF_8);
            if (code >= 400) {
                throw new IllegalStateException("模型接口 HTTP " + code + ":" + truncate(resp, 300));
            }
            JSONObject j = new JSONObject(resp);
            String content = j.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .getString("content");
            if (content == null || content.trim().isEmpty()) {
                throw new IllegalStateException("模型返回空内容");
            }
            return content;
        } finally {
            try { c.disconnect(); } catch (Throwable ignored) { }
        }
    }

    // ------------------------------------------------ WAV 校验 / key / 工具

    /**
     * 校验 WAV(RIFF/WAVE 头,手写解析):遍历 chunk,fmt 必须是 PCM(编码 1),
     * data 必须存在;非 PCM 报错。解析只为校验,音频本体按原样 base64 回填给模型。
     */
    private static void checkWav(byte[] b) throws IllegalArgumentException {
        if (b == null || b.length < 12) throw new IllegalArgumentException("音频太小,不是有效 WAV");
        if (b[0] != 'R' || b[1] != 'I' || b[2] != 'F' || b[3] != 'F'
                || b[8] != 'W' || b[9] != 'A' || b[10] != 'V' || b[11] != 'E') {
            throw new IllegalArgumentException("不是 WAV(RIFF) 音频");
        }
        int pos = 12;
        boolean fmtOk = false, dataOk = false;
        int format = -1, channels = 0, rate = 0, bits = 0;
        long dataLen = 0;
        while (pos + 8 <= b.length) {
            String id = new String(b, pos, 4, StandardCharsets.US_ASCII);
            long size = ((b[pos + 4] & 0xFFL))
                    | ((b[pos + 5] & 0xFFL) << 8)
                    | ((b[pos + 6] & 0xFFL) << 16)
                    | ((b[pos + 7] & 0xFFL) << 24);
            if (size < 0) size &= 0xFFFFFFFFL;
            if ("fmt ".equals(id)) {
                if (size < 16 || pos + 8 + 16 > b.length) {
                    throw new IllegalArgumentException("WAV fmt 块损坏");
                }
                format   = le16(b, pos + 8);
                channels = le16(b, pos + 10);
                rate     = le32(b, pos + 12);
                bits     = le16(b, pos + 22);
                if (format != 1) {
                    throw new IllegalArgumentException("仅支持 PCM WAV(音频编码=" + format + ")");
                }
                fmtOk = true;
            } else if ("data".equals(id)) {
                if (size <= 0 || pos + 8 >= b.length) {
                    throw new IllegalArgumentException("WAV data 块为空");
                }
                dataLen = size;
                dataOk = true;
            }
            pos += 8 + (int) size + ((int) size & 1);   // chunk 按 2 字节对齐
            if (fmtOk && dataOk) break;
        }
        if (!fmtOk || !dataOk) throw new IllegalArgumentException("WAV 缺少 fmt/data 块");
        Log.i(TAG, "WAV 校验通过: PCM " + channels + "ch " + rate + "Hz "
                + bits + "bit, data " + dataLen + "B");
    }

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    /**
     * 读 assets/key.txt:两行,「SILICONFLOW_API_KEY=xxx」「MODELSCOPE_API_KEY=xxx」,
     * 按行 split("=") 取值,去引号;name 对应的行缺失/为空返回 ""。
     */
    private String readKey(String name) {
        InputStream is = null;
        try {
            is = getAssets().open("key.txt");
            String s = new String(readAllBytes(is), StandardCharsets.UTF_8);
            for (String line : s.split("\n")) {
                line = line.trim();
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String k = line.substring(0, eq).trim();
                if (k.equalsIgnoreCase(name)) {
                    String v = line.substring(eq + 1).trim();
                    v = v.replace("\"", "").replace("'", "").trim();
                    return v;
                }
            }
            return "";
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (is != null) is.close(); } catch (IOException ignored) { }
        }
    }

    /** 屏幕文字转 JSONArray(异常吞掉,给空数组) */
    private JSONArray screenArr(AgentService a, int n) {
        JSONArray arr = new JSONArray();
        if (a != null) {
            try {
                for (String s : a.screenTexts(n)) arr.put(s);
            } catch (Throwable ignored) { }
        }
        return arr;
    }

    private static int needInt(JSONObject o, String k) throws Exception {
        if (!o.has(k)) throw new IllegalArgumentException("缺少参数 " + k);
        return o.getInt(k);
    }

    private static String msgOf(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
