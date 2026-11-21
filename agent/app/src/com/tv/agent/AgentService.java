/*
 * AgentService.java —— 无障碍执行器(电视Agent 的"手")
 *
 * 职责:
 *   · 被系统无障碍绑定后常驻;instance 供 HttpService 判断/调用(onServiceConnected 赋值,onDestroy 置 null)
 *   · global/back/home:全局返回键、主页键(GLOBAL_ACTION_BACK=1 / GLOBAL_ACTION_HOME=2)
 *   · tap/swipe:dispatchGesture 合成触摸(小米电视 MiTV-ASSU0,Android 14,
 *     无触摸屏但合成触摸有效;1920×1080 坐标系)
 *   · clickText:按文字找节点并点击(精确匹配优先,避免把「搜索历史」当成「搜索」点下去)
 *   · setTextIntoField:找 EditText 灌字并回读核对,失败重试一次
 *   · screenTexts:按位置排序收集当前屏幕可见文字,给手机端展示
 *   · topWindowPackage:最上层应用窗口包名,供 HttpService 的小爱互斥守卫使用
 *
 * 节点遍历统一走 dfs():限深度/限数量 + seen 集合防环,全程判空。
 * AccessibilityNodeInfo#recycle 在 API33+ 已废弃,直接用即可。
 */
package com.tv.agent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class AgentService extends AccessibilityService {

    private static final String TAG = "AgentService";

    /** HttpService 通过它判断无障碍是否已开启 */
    public static volatile AgentService instance;

    /** DFS 保护:节点总数上限 / 递归深度上限,防异常界面拖死 */
    private static final int MAX_NODES = 3000;
    private static final int MAX_DEPTH = 50;

    // ------------------------------------------------ 生命周期

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "无障碍服务已连接");
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) { /* 不依赖事件驱动 */ }

    @Override public void onInterrupt() { /* 保留空实现 */ }

    // ------------------------------------------------ 全局动作

    /** 全局动作:GLOBAL_ACTION_BACK=1 / GLOBAL_ACTION_HOME=2 等 */
    public boolean global(int action) {
        try {
            return performGlobalAction(action);
        } catch (Throwable t) {
            Log.w(TAG, "global(" + action + ") 失败", t);
            return false;
        }
    }

    /** 返回键 */
    public boolean back() { return global(GLOBAL_ACTION_BACK); }

    /** 主页键 */
    public boolean home() { return global(GLOBAL_ACTION_HOME); }

    // ------------------------------------------------ 合成触摸

    /** 单点轻触,按下 50ms */
    public boolean tap(int x, int y) {
        try {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, 50));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "tap(" + x + "," + y + ") 失败", t);
            return false;
        }
    }

    /** 从 (x1,y1) 滑到 (x2,y2),时长 ms */
    public boolean swipe(int x1, int y1, int x2, int y2, long ms) {
        try {
            Path p = new Path();
            p.moveTo(x1, y1);
            p.lineTo(x2, y2);
            GestureDescription.Builder b = new GestureDescription.Builder();
            b.addStroke(new GestureDescription.StrokeDescription(p, 0, Math.max(10, ms)));
            return dispatchGesture(b.build(), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "swipe 失败", t);
            return false;
        }
    }

    // ------------------------------------------------ 窗口 / 包名

    /**
     * 最上层 APPLICATION 窗口的包名(拿不到返回 null)。
     * 供小爱互斥守卫与 /api/status 使用;getWindows() 不可用时退回 rootInActiveWindow。
     */
    public String topWindowPackage() {
        try {
            List<AccessibilityWindowInfo> wins = getWindows();
            if (wins != null) {
                AccessibilityWindowInfo best = null;
                for (AccessibilityWindowInfo w : wins) {
                    if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                    if (best == null || w.getLayer() > best.getLayer()) best = w;
                }
                if (best != null && best.getRoot() != null && best.getRoot().getPackageName() != null) {
                    return best.getRoot().getPackageName().toString();
                }
            }
        } catch (Throwable ignored) { /* 有的机器没开 interactive windows,走兜底 */ }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && root.getPackageName() != null) {
                return root.getPackageName().toString();
            }
        } catch (Throwable ignored) { }
        return null;
    }

    // ------------------------------------------------ 节点遍历

    /** 深度优先收集节点;判空 + seen 防环(AccessibilityNodeInfo 重写过 equals)+ 限深度限数量 */
    private void dfs(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out,
                     Set<AccessibilityNodeInfo> seen, int depth) {
        if (node == null || out.size() >= MAX_NODES || depth > MAX_DEPTH) return;
        if (!seen.add(node)) return;
        out.add(node);
        int n = node.getChildCount();
        for (int i = 0; i < n; i++) {
            try {
                dfs(node.getChild(i), out, seen, depth + 1);
            } catch (Throwable ignored) { }
        }
    }

    /** 每个节点访问都是一次 IPC:全树 DFS 在电视桌面上要几十秒。
        快速路径一律用下面的有界收集(早停),collect 全树只留给非热路径。 */
    private static final int QUICK_VISIT_CAP = 120;

    /** 接口式谓词:visit=true 继续收集,false 中止整趟遍历 */
    private interface NodeSink {
        /** 返回 false = 已收集够,立即中止 */
        boolean onNode(AccessibilityNodeInfo n);
    }

    /** 有界 DFS:访问不超过 visitCap 个节点,满足 sink 停止条件就早停 */
    private void boundedDfs(AccessibilityNodeInfo node, NodeSink sink,
                            Set<AccessibilityNodeInfo> seen, int[] visited, int depth) {
        if (node == null || visited[0] >= QUICK_VISIT_CAP || depth > MAX_DEPTH) return;
        if (!seen.add(node)) return;
        visited[0]++;
        try {
            if (!sink.onNode(node)) return;
        } catch (Throwable ignored) { }
        int n = node.getChildCount();
        for (int i = 0; i < n && visited[0] < QUICK_VISIT_CAP; i++) {
            try {
                boundedDfs(node.getChild(i), sink, seen, visited, depth + 1);
            } catch (Throwable ignored) { }
        }
    }

    /** 以 root 为根收集全部可达节点 */
    private List<AccessibilityNodeInfo> collect(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root != null) dfs(root, out, new HashSet<AccessibilityNodeInfo>(), 0);
        return out;
    }

    /** 节点的展示文字:优先 text,其次 contentDescription;都没有返回 null */
    private static String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        if (t != null && t.length() > 0) return t.toString();
        CharSequence d = n.getContentDescription();
        if (d != null && d.length() > 0) return d.toString();
        return null;
    }

    // ------------------------------------------------ 按文字点击

    /**
     * 按文字点击。查找顺序:精确相等(优先 clickable)→ 精确(tap 中心兜底)
     * → contains(优先 clickable)→ contains(tap 中心兜底)。
     * 精确优先,避免首页把「搜索历史」这类包含文字误当成「搜索」点下去。
     */
    public boolean clickText(String text) {
        if (text == null || text.isEmpty()) return false;
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return false;

            // 快速路径:框架侧文字搜索(服务端子串匹配,一次 IPC)
            List<AccessibilityNodeInfo> hits = root.findAccessibilityNodeInfosByText(text);
            if (hits != null) {
                AccessibilityNodeInfo exactClickable = null, exactAny = null;
                for (AccessibilityNodeInfo n : hits) {
                    String label = textOf(n);
                    if (label == null) continue;
                    if (!label.equals(text)) continue;
                    if (n.isClickable()) { exactClickable = n; break; }
                    if (exactAny == null) exactAny = n;
                }
                // 尝试顺序:精确+可点 → 精确+tap兜底
                AccessibilityNodeInfo[] order = { exactClickable, exactAny };
                for (AccessibilityNodeInfo n : order) {
                    if (n != null && tryClick(n)) return true;
                }
            }

            // 慢速路径:有界 DFS(含 contentDescription 匹配)
            final AccessibilityNodeInfo[] found = { null, null };
            boundedDfs(root, new NodeSink() {
                @Override public boolean onNode(AccessibilityNodeInfo n) {
                    String label = textOf(n);
                    if (label == null || !label.contains(text)) return true;
                    if (n.isClickable() && found[0] == null) found[0] = n;
                    if (found[1] == null) found[1] = n;
                    return found[0] == null;   // 拿到可点的就停
                }
            }, new HashSet<AccessibilityNodeInfo>(), new int[1], 0);
            // 尝试顺序同上:模糊+可点 → 模糊+tap兜底
            AccessibilityNodeInfo[] order = { found[0], found[1] };
            for (AccessibilityNodeInfo n : order) {
                if (n != null && tryClick(n)) return true;
            }
            return false;
        } catch (Throwable t) {
            Log.w(TAG, "clickText(\"" + text + "\") 失败", t);
            return false;
        }
    }

    /** clickable 节点先 performAction(ACTION_CLICK),失败再 tap 中心兜底 */
    private boolean tryClick(AccessibilityNodeInfo n) {
        try {
            if (n.isClickable() && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true;
            return tapNodeCenter(n);
        } catch (Throwable t) {
            return tapNodeCenter(n);
        }
    }

    /** 对节点 bounds 中心做合成触摸(节点本身不可点时的兜底) */
    private boolean tapNodeCenter(AccessibilityNodeInfo n) {
        try {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.isEmpty()) return false;
            return tap(r.centerX(), r.centerY());
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------ 输入框灌字

    /** 便捷入口:不关心框内最终文字 */
    public boolean setTextIntoField(String value) {
        return setTextIntoField(value, null);
    }

    /**
     * 找第一个 EditText:ACTION_SET_TEXT 空串清空 → ACTION_SET_TEXT 写入 value
     * → 回读 getText() 核对包含 value;失败重试一次。
     * 节点不可 SET_TEXT 时先 tap 聚焦再试。outFinal 可空,带回框内最终文字。
     */
    public boolean setTextIntoField(String value, StringBuilder outFinal) {
        String target = value == null ? "" : value;
        boolean ok = false;
        String finalText = "";
        // 最多试两轮 = 原始尝试 + 失败重试一次
        for (int attempt = 0; attempt < 2 && !ok; attempt++) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) { sleepQuiet(400); continue; }
                AccessibilityNodeInfo edit = findFirstEditText(root);
                if (edit == null) { sleepQuiet(400); continue; }

                // 1) 清空(空串)
                Bundle clearArgs = new Bundle();
                clearArgs.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                boolean cleared = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
                if (!cleared) {
                    // 2) 节点不肯 SET_TEXT:tap 聚焦后再试一次
                    tapNodeCenter(edit);
                    cleared = edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs);
                }
                // 3) 写入目标文字
                Bundle putArgs = new Bundle();
                putArgs.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, target);
                boolean set = cleared && edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, putArgs);

                // 4) 回读核对(节点可能已刷新:refresh 失败就重新找)
                String cur = null;
                if (edit.refresh()) cur = textOf(edit);
                if (cur == null) {
                    AccessibilityNodeInfo root2 = getRootInActiveWindow();
                    AccessibilityNodeInfo e2 = root2 == null ? null : findFirstEditText(root2);
                    if (e2 != null) cur = textOf(e2);
                }
                finalText = cur == null ? "" : cur;
                ok = set && (target.isEmpty()
                        ? finalText.isEmpty()
                        : finalText.contains(target));
                if (!ok) sleepQuiet(500);
            } catch (Throwable t) {
                Log.w(TAG, "setTextIntoField 异常", t);
                sleepQuiet(400);
            }
        }
        if (outFinal != null) outFinal.append(finalText);
        return ok;
    }

    /** DFS 找第一个类名含 EditText 的节点(有界早停:找到即返回) */
    private AccessibilityNodeInfo findFirstEditText(AccessibilityNodeInfo root) {
        final AccessibilityNodeInfo[] found = { null };
        boundedDfs(root, new NodeSink() {
            @Override public boolean onNode(AccessibilityNodeInfo n) {
                CharSequence cn = n.getClassName();
                if (cn != null && cn.toString().contains("EditText")) {
                    found[0] = n;
                    return false;   // 找到即停
                }
                return true;
            }
        }, new HashSet<AccessibilityNodeInfo>(), new int[1], 0);
        return found[0];
    }

    // ------------------------------------------------ 屏幕文字

    /**
     * 收集当前屏幕可见文字(手机端展示用):
     * 优先取 type=APPLICATION 且 isFocused 的窗口(getWindows() 拿不到就退 rootInActiveWindow),
     * DFS 收 text/desc 非空节点,按 bounds 中心 y 再 x 排序,相邻去重,取前 limit 条。
     */
    public List<String> screenTexts(int limit) {
        List<String> out = new ArrayList<>();
        if (limit <= 0) return out;
        try {
            AccessibilityNodeInfo root = null;
            try {
                List<AccessibilityWindowInfo> wins = getWindows();
                if (wins != null) {
                    for (AccessibilityWindowInfo w : wins) {
                        if (w == null) continue;
                        if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && w.isFocused()) {
                            root = w.getRoot();
                            if (root != null) break;
                        }
                    }
                }
            } catch (Throwable ignored) { /* 走 rootInActiveWindow 兜底 */ }
            if (root == null) root = getRootInActiveWindow();
            if (root == null) return out;

            // 有界收集:凑够 limit*3 条就停(留排序余量),节点访问上限 QUICK_VISIT_CAP
            final int want = limit * 3;
            List<Object[]> items = new ArrayList<>();
            boundedDfs(root, new NodeSink() {
                @Override public boolean onNode(AccessibilityNodeInfo n) {
                    String s = textOf(n);
                    if (s == null) return true;
                    s = s.trim();
                    if (s.isEmpty()) return true;
                    Rect r = new Rect();
                    n.getBoundsInScreen(r);
                    items.add(new Object[]{ s, r });
                    return items.size() < want;
                }
            }, new HashSet<AccessibilityNodeInfo>(), new int[1], 0);

            Collections.sort(items, new Comparator<Object[]>() {
                @Override public int compare(Object[] a, Object[] b) {
                    Rect ra = (Rect) a[1], rb = (Rect) b[1];
                    int c = Integer.compare(ra.centerY(), rb.centerY());
                    if (c != 0) return c;
                    return Integer.compare(ra.centerX(), rb.centerX());
                }
            });
            String last = null;
            for (Object[] it : items) {
                String s = (String) it[0];
                if (s.equals(last)) continue;   // 相邻去重
                last = s;
                out.add(s);
                if (out.size() >= limit) break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "screenTexts 失败", t);
        }
        return out;
    }

    // ------------------------------------------------ 备用

    /** 备用:当前输入焦点节点上的文字(暂未用到,留着调试) */
    public String focusedText() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return null;
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            return f == null ? null : textOf(f);
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------ 工具

    private static void sleepQuiet(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
