package com.aposamir.tasbeehapp;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int OVERLAY_PERMISSION_REQ = 1000;
    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.webview);
        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);
        webView.setWebViewClient(new WebViewClient());

        webView.loadUrl("file:///android_asset/index.html");

        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        // RECEIVER_NOT_EXPORTED عبر ContextCompat يمنع أي تطبيق آخر مثبّت
        // بنفس الجهاز من إرسال بث مزوّر باسم BUBBLE_TAPPED (كان يعمل على
        // كل الإصدارات قبل هذا التعديل بدون أي حماية على Android < 13).
        ContextCompat.registerReceiver(
                this,
                bubbleReceiver,
                new IntentFilter("BUBBLE_TAPPED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
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
            // The live path counts this tap, so it is no longer pending.
            SharedPreferences p = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            synchronized (FloatingService.PENDING_LOCK) {
                int pending = p.getInt("pending_taps", 0);
                if (pending > 0) p.edit().putInt("pending_taps", pending - 1).commit();
            }
            webView.evaluateJavascript("javascript:androidBubbleTap();", null);
        }
    };

    public class WebAppInterface {

        @JavascriptInterface
        public void setCurrentUserName(String name) {
            getSharedPreferences("bubble_prefs", MODE_PRIVATE).edit()
                    .putString("current_user", name == null ? "" : name).apply();
        }

        @JavascriptInterface
        public void setSharedMode(boolean shared) {
            getSharedPreferences("bubble_prefs", MODE_PRIVATE).edit()
                    .putBoolean("shared_mode", shared).apply();
        }

        @JavascriptInterface
        public int getPendingBubbleTapCount() {
            return getSharedPreferences("bubble_prefs", MODE_PRIVATE).getInt("pending_taps", 0);
        }

        @JavascriptInterface
        public String getPendingBubbleUser() {
            return getSharedPreferences("bubble_prefs", MODE_PRIVATE).getString("pending_user", "");
        }

        // Atomically read and clear the pending taps; JS must replay them.
        @JavascriptInterface
        public int takePendingBubbleTaps() {
            SharedPreferences p = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            synchronized (FloatingService.PENDING_LOCK) {
                int n = p.getInt("pending_taps", 0);
                p.edit().putInt("pending_taps", 0).commit();
                return n;
            }
        }

        @JavascriptInterface
        public void restorePendingBubbleTaps(int n) {
            SharedPreferences p = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            synchronized (FloatingService.PENDING_LOCK) {
                p.edit().putInt("pending_taps", p.getInt("pending_taps", 0) + n).commit();
            }
        }

        @JavascriptInterface
        public int getNativeBubbleCount() {
            SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            return prefs.getInt("bubble_count", 0);
        }

        @JavascriptInterface
        public void resetNativeBubbleCount() {
            SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            prefs.edit().putInt("bubble_count", 0).apply();

            Intent intent = new Intent("WEB_UPDATED");
            intent.setPackage(getPackageName());
            intent.putExtra("count", 0);
            sendBroadcast(intent);
        }

        @JavascriptInterface
        public void updateCount(int count) {
            SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
            prefs.edit().putInt("bubble_count", count).apply();

            Intent intent = new Intent("WEB_UPDATED");
            intent.setPackage(getPackageName());
            intent.putExtra("count", count);
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
            Intent intent = new Intent(MainActivity.this, FloatingService.class);
            intent.putExtra("scale", scale);
            requestNotificationPermissionOnce();
            ContextCompat.startForegroundService(MainActivity.this, intent);
        }

        @JavascriptInterface
        public void hideBubble() {
            Intent stop = new Intent(MainActivity.this, FloatingService.class);
            stop.setAction(FloatingService.ACTION_STOP);
            try {
                startService(stop);
            } catch (RuntimeException e) {
                stopService(new Intent(MainActivity.this, FloatingService.class));
            }
        }
    }

    // Android 13+: ask once so the small "bubble is running" notification is visible.
    // The bubble still works if the user declines.
    private void requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return;
        SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
        if (prefs.getBoolean("notification_permission_asked", false)) return;
        prefs.edit().putBoolean("notification_permission_asked", true).apply();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(bubbleReceiver);
    }
}
