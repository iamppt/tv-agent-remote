/*
 * MainActivity.java —— 电视端状态页(竖屏,纯代码布局,无 res/layout)
 *
 * 职责:
 *   · onCreate 即 startForegroundService 拉起 HttpService(常驻,Activity 退出不影响)
 *   · 展示电视本机 IP,提示手机浏览器打开 http://<IP>:9999
 *     IP 获取:① WifiManager.getDhcpInfo(需要 ACCESS_WIFI_STATE,本 app 不申请,
 *     没权限会抛异常,吞掉即可)→ ② 枚举 NetworkInterface 拿 IPv4(零权限,
 *     电视网线口也适用)→ ③ 都拿不到就提示到 设置→网络 里查
 *   · 说明文字:首次要在 设置→无障碍 里开启本服务;按返回键退出界面但服务常驻
 */
package com.tv.agent;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 启动常驻前台 HTTP 服务(API 26+ 必须 startForegroundService)
        Intent it = new Intent(this, HttpService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(it);
        } else {
            startService(it);
        }

        // Android 13+ 通知权限:不申请也只是常驻通知不显示,服务照常跑
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 1);
        }

        // ---- 纯代码搭状态页 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(0xFF0B0E14);
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("电视Agent 已运行");
        title.setTextColor(0xFFE7ECF5);
        title.setTextSize(26);
        title.setGravity(Gravity.CENTER);

        TextView url = new TextView(this);
        String ip = tvIp();
        url.setText(ip != null
                ? "手机浏览器打开\nhttp://" + ip + ":" + HttpService.PORT
                : "拿不到本机 IP:请到 设置→网络 里查看电视 IP,\n"
                  + "手机浏览器打开 http://<电视IP>:" + HttpService.PORT);
        url.setTextColor(ip != null ? 0xFF9CC3F7 : 0xFFF59E0B);
        url.setTextSize(18);
        url.setGravity(Gravity.CENTER);
        url.setLineSpacing(dp(4), 1f);

        TextView tip = new TextView(this);
        tip.setText("首次使用:设置 → 无障碍 → 已下载的应用 → 开启「电视Agent」\n\n"
                + "手机网页支持:指针触控 / 音量 / 语音与文字指令 / 搜片 / 播放控制\n\n"
                + "按返回键即退出本界面,遥控服务在后台常驻");
        tip.setTextColor(0xFF8A94A8);
        tip.setTextSize(14);
        tip.setGravity(Gravity.CENTER);
        tip.setLineSpacing(dp(4), 1f);

        LinearLayout.LayoutParams lpTitle = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        LinearLayout.LayoutParams lpUrl = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpUrl.setMargins(0, dp(28), 0, 0);
        LinearLayout.LayoutParams lpTip = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpTip.setMargins(0, dp(32), 0, 0);

        root.addView(title, lpTitle);
        root.addView(url, lpUrl);
        root.addView(tip, lpTip);
        setContentView(root);
    }

    /** dp → px */
    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /**
     * 电视本机 IPv4:
     * ① WifiManager.getDhcpInfo(wifi 口);② 枚举网卡(网线口);都失败返回 null。
     * 故意不申请 ACCESS_WIFI_STATE:没权限时 getDhcpInfo 抛异常,吞掉走 ②。
     */
    private String tvIp() {
        try {
            WifiManager wm = (WifiManager) getApplicationContext()
                    .getSystemService(Context.WIFI_SERVICE);
            DhcpInfo d = wm == null ? null : wm.getDhcpInfo();
            if (d != null && d.ipAddress != 0) return intToIp(d.ipAddress);
        } catch (Throwable ignored) { }
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis != null && nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    /** DhcpInfo.ipAddress 是小端 int,转点分十进制 */
    private static String intToIp(int v) {
        return (v & 0xFF) + "." + ((v >> 8) & 0xFF) + "." + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF);
    }
}
