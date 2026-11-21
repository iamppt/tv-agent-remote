package com.tv.remote;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.KeyEvent;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    // 遥控页地址:电视上的 TVAgent(改成你电视的局域网 IP)
    private static final String AGENT_URL = "http://192.0.2.10:9999";
    private static final int REQ_MIC = 100;

    private WebView web;
    private boolean justBuilt = false;   // onCreate 刚建过,onResume 首次跳过重建

    private static void log(String m) {
        android.util.Log.d("TVRemote", m);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 语音按住说话要用麦克风:先要运行时权限,WebView 里的授权在 onPermissionRequest 给
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }

        buildWebView();
        justBuilt = true;
    }

    /** 新建 WebView 并加载。

        为什么每次回前台都整个重建:MIUI+MTK 机器上 WebView 的表面在
        Activity 离开前台后重建会失败——切走再回来整个窗口永久变黑
        (实测过渲染层/硬件加速各种组合都一样)。页面自己的状态都在
        localStorage 里,重建无感。 */
    private void buildWebView() {
        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false); // 录音按钮自己管手势,别让 WebView 拦

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                log("onReceivedError " + errorCode + " " + description + " " + failingUrl);
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                // 网页调 getUserMedia 时把麦克风给它(页面只会要这一种)
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        request.grant(request.getResources());
                    }
                });
            }
        });

        web.loadUrl(AGENT_URL);
        log("buildWebView done, loadUrl " + AGENT_URL);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // 系统返回键 = 网页后退;没历史可退就不拦截
        if (keyCode == KeyEvent.KEYCODE_BACK && web.canGoBack()) {
            web.goBack();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        // 拒了麦克风就拒了:页面语音会失败,但触控/键盘不受影响,不弹窗纠缠
        super.onRequestPermissionsResult(code, perms, results);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web == null) {
            return;
        }
        web.onResume();
        // MIUI+MTK:离开前台后 WebView 表面重建失败 → 每次回前台都换一个新的。
        // 页面自己的状态(灵敏度/模式)存 localStorage,重建无感;
        // 正在录音会被打断,可接受。onCreate 后紧跟的首次 onResume 跳过。
        if (justBuilt) {
            justBuilt = false;
        } else {
            buildWebView();
        }
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.destroy();
        }
        super.onDestroy();
    }
}
