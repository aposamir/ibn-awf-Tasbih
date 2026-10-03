package com.aposamir.tasbeehapp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_REQ = 1000;
    private static final int NOTIFICATION_PERMISSION_REQ = 1001;
    private WebView webView;
    private boolean pageReady = false;
    private boolean bubbleReplayInProgress = false;
    private boolean bubbleReceiverRegistered = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        // Expose the native bridge before the local app starts executing.
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        // Never load arbitrary external pages inside the WebView that owns
        // AndroidBridge. External navigation is handed to the system browser.
        webView.setWebViewClient(new WebViewClient() {
            private boolean handleNavigation(String url) {
                if (url == null || url.startsWith("file:///android_asset/")) {
                    return false;
                }
                try {
                    Intent external = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(external);
                } catch (Exception ignored) {
                }
                return true;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleNavigation(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return handleNavigation(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (url != null && url.startsWith("file:///android_asset/")) {
                    pageReady = true;
                    replayPendingBubbleTaps();
                }
            }
        });

        webView.loadUrl("file:///android_asset/index.html");

        // RECEIVER_NOT_EXPORTED عبر ContextCompat يمنع أي تطبيق آخر مثبّت
        // بنفس الجهاز من إرسال بث مزوّر باسم BUBBLE_TAPPED (كان يعمل على
        // كل الإصدارات قبل هذا التعديل بدون أي حماية على Android < 13).
        ContextCompat.registerReceiver(
                this,
                bubbleReceiver,
                new IntentFilter("BUBBLE_TAPPED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        bubbleReceiverRegistered = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    protected void onResume() {
        super.onResume();
        replayPendingBubbleTaps();
    }

    private void replayPendingBubbleTaps() {
        if (bubbleReplayInProgress || webView == null || !pageReady) return;

        SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
        int pending = prefs.getInt("pending_bubble_taps", 0);
        if (pending <= 0) return;

        String pendingUser = prefs.getString("pending_bubble_user", "");
        if (pendingUser == null || pendingUser.isEmpty()) return;

        bubbleReplayInProgress = true;
        final int tapsToReplay = pending;
        final String expectedUserJson = org.json.JSONObject.quote(pendingUser);
        webView.post(() -> webView.evaluateJavascript(
                "javascript:(function(){if(typeof androidBubbleTap!=='function'||typeof getCurrentParticipantName!=='function')return false;if(getCurrentParticipantName()!==" + expectedUserJson + ")return false;for(var i=0;i<" + tapsToReplay + ";i++){if(androidBubbleTap()!==true)return false;}return true;})()",
                result -> {
                    if ("true".equals(result)) {
                        SharedPreferences latest = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
                        int nowPending = latest.getInt("pending_bubble_taps", 0);
                        int remainingPending = Math.max(0, nowPending - tapsToReplay);
                        SharedPreferences.Editor editor = latest.edit().putInt("pending_bubble_taps", remainingPending);
                        if (remainingPending == 0) editor.remove("pending_bubble_user");
                        editor.apply();
                    }
                    bubbleReplayInProgress = false;

                    // Only continue immediately after a successful batch. If
                    // JavaScript rejected it (for example, no participant name
                    // is registered yet), keep the taps durable and wait for a
                    // later lifecycle/broadcast event instead of spinning.
                    if ("true".equals(result)) {
                        SharedPreferences latest = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
                        if (latest.getInt("pending_bubble_taps", 0) > 0) {
                            replayPendingBubbleTaps();
                        }
                    }
                }
        ));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP && event.getAction() == KeyEvent.ACTION_DOWN) {
            webView.evaluateJavascript("javascript:androidTap();", null);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private BroadcastReceiver bubbleReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            replayPendingBubbleTaps();
        }
    };

    public class WebAppInterface {

        @JavascriptInterface
        public void updateCount(int count) {
            SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            prefs.edit()
                    .putInt("bubble_count", count)
                    .apply();

            Intent intent = new Intent("WEB_UPDATED");
            intent.setPackage(getPackageName());
            intent.putExtra("count", count);
            sendBroadcast(intent);
        }

        @JavascriptInterface
        public void setCurrentUserName(String name) {
            String safeName = name == null ? "" : name.trim();
            getSharedPreferences("bubble_prefs", MODE_PRIVATE)
                    .edit().putString("current_user_name", safeName).apply();
        }

        @JavascriptInterface
        public int getNativeBubbleCount() {
            return getSharedPreferences("bubble_prefs", MODE_PRIVATE)
                    .getInt("bubble_count", 0);
        }

        @JavascriptInterface
        public void resetNativeBubbleCount() {
            getSharedPreferences("bubble_prefs", MODE_PRIVATE)
                    .edit().putInt("bubble_count", 0).apply();

            Intent intent = new Intent("WEB_UPDATED");
            intent.setPackage(getPackageName());
            intent.putExtra("count", 0);
            sendBroadcast(intent);
        }

        @JavascriptInterface
        public boolean hasOverlayPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.canDrawOverlays(MainActivity.this);
            }
            return true;
        }

        @JavascriptInterface
        public void requestOverlayPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
                startActivityForResult(intent, OVERLAY_PERMISSION_REQ);
            }
        }

        @JavascriptInterface
        public void showOverlayBubble(double scale) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(MainActivity.this)) {
                return;
            }

            // Android 13+ requires runtime notification permission for normal
            // notification-drawer visibility. Refusal must not disable the
            // foreground bubble itself.
            SharedPreferences bubblePrefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    && ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED
                    && !bubblePrefs.getBoolean("notification_permission_requested", false)) {
                bubblePrefs.edit().putBoolean("notification_permission_requested", true).apply();
                ActivityCompat.requestPermissions(
                        MainActivity.this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        NOTIFICATION_PERMISSION_REQ
                );
            }

            bubblePrefs
                    .edit().putBoolean("bubble_enabled", true).apply();
            Intent intent = new Intent(MainActivity.this, FloatingService.class);
            intent.putExtra("scale", scale);
            ContextCompat.startForegroundService(MainActivity.this, intent);
        }

        @JavascriptInterface
        public void hideBubble() {
            getSharedPreferences("bubble_prefs", MODE_PRIVATE)
                    .edit().putBoolean("bubble_enabled", false).apply();
            stopService(new Intent(MainActivity.this, FloatingService.class));
        }
    }

    @Override
    protected void onDestroy() {
        pageReady = false;
        super.onDestroy();
        if (bubbleReceiverRegistered) {
            try {
                unregisterReceiver(bubbleReceiver);
            } catch (IllegalArgumentException ignored) {
            }
            bubbleReceiverRegistered = false;
        }
    }
}
