package com.tv.agent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** 开机自启:电视重启后把 HTTP 服务与前台通知拉起来。 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        try {
            HttpServerCore.startIfNotRunning(context.getApplicationContext());
            android.content.Intent svc = new android.content.Intent(context, HttpService.class);
            context.startForegroundService(svc);
        } catch (Throwable t) {
            android.util.Log.w("AgentBoot", "开机自启失败:" + t);
        }
    }
}
