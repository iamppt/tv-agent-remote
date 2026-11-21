/*
 * HttpService.java —— 前台常驻服务(电视Agent 的前台保活壳 + 常驻通知)
 *
 * 职责:
 *   · 以 specialUse 前台服务常驻,提供常驻通知(startFg);手机浏览器 http://<IP>:9999 遥控
 *   · HTTP 服务器本体(ServerSocket/解析/全部路由/小爱守卫/搜片等)已迁入 HttpServerCore,
 *     由本服务与 AgentService(无障碍)双服务托管:
 *       onStartCommand 调 HttpServerCore.startIfNotRunning(this) 幂等拉起——
 *       服务器已在跑直接返回 false(无副作用);死了(accept 异常退出)则在这里重建
 *   · onDestroy 故意不调 HttpServerCore.stop():前台服务被杀时让无障碍服务接管 HTTP
 *     (AgentService.onServiceConnected 会 startIfNotRunning);本服务 START_STICKY
 *     被系统拉起后 onStartCommand 也会兜底拉起——任一服务存活,HTTP 就活着
 *   · 端点/守卫/路由一览见 HttpServerCore 头注释
 */
package com.tv.agent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

public class HttpService extends Service {

    /** 端口常量别名(本体在 HttpServerCore;MainActivity 等旧引用保持不变) */
    public static final int PORT = HttpServerCore.PORT;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startFg();
        // 双服务兜底:幂等拉起 HTTP 服务器(已在跑返回 false;死了则重建)。
        // apps.json 预热也由 HttpServerCore.acceptLoop 自行完成,这里不再重复。
        HttpServerCore.startIfNotRunning(this);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        // 故意不调 HttpServerCore.stop():前台服务被杀时让无障碍服务接管 HTTP
        // (AgentService.onServiceConnected 里会 startIfNotRunning);本服务 START_STICKY
        // 重启后 onStartCommand 也会兜底拉起,保证两个服务任一存活 HTTP 就活着。
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

}
