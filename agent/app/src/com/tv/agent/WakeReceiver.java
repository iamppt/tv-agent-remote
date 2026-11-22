package com.tv.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 复活闹钟的接收器:AlarmManager 每 10 分钟触发一次(进程被系统整个
 *  杀掉后,闹钟注册在系统里依然会到点派发)→ 把 HTTP 服务拉起来。
 *  另响应手动广播 com.tv.agent.WAKE(测试/遥控器唤醒用,adb:
 *  am broadcast -a com.tv.agent.WAKE)。 */
public class WakeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        try {
            HttpServerCore.startIfNotRunning(context.getApplicationContext());
            // 服务若活着但被停过,顺便把前台服务拉回来(保通知与优先级)
            android.content.Intent svc = new android.content.Intent(context, HttpService.class);
            context.startForegroundService(svc);
        } catch (Throwable t) {
            android.util.Log.w("AgentWake", "复活失败:" + t);
        }
    }
}
