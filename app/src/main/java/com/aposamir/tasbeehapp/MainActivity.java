package com.aposamir.tasbeehapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
            webView.evaluateJavascript("javascript:androidTap();", null);
        }
    };

    public class WebAppInterface {

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
            startService(intent);
        }

        @JavascriptInterface
        public void hideBubble() {
            stopService(new Intent(MainActivity.this, FloatingService.class));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(bubbleReceiver);
    }
}
