package com.aposamir.tasbeehapp;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import androidx.core.content.ContextCompat;

public class FloatingService extends Service {

    public static final String ACTION_STOP = "com.aposamir.tasbeehapp.STOP_BUBBLE";
    private static final String CHANNEL_ID = "tasbeeh_floating_counter";
    private static final int NOTIFICATION_ID = 2401;
    private static final double DEFAULT_SCALE = 2.0 / 3.0;
    private static final long POLL_INTERVAL_MS = 1500;

    private WindowManager windowManager;
    private View floatingView;
    private TextView bubbleCounter;
    private int count = 0;
    private WindowManager.LayoutParams params;
    private final Handler pollHandler = new Handler(Looper.getMainLooper());
    private Runnable pollRunnable;

    private BroadcastReceiver webReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            count = intent.getIntExtra("count", count);
            updateCounterText();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        ensureForeground();
    }

    // Runs as a foreground service (small ongoing notification) so Android and
    // MIUI-style task killers keep the floating counter alive after the app is
    // closed. Returns false if the system refused; the service then stops itself.
    private boolean ensureForeground() {
        Notification notification;
        try {
            notification = buildNotification(R.mipmap.ic_launcher);
        } catch (RuntimeException e) {
            try {
                notification = buildNotification(android.R.drawable.ic_dialog_info);
            } catch (RuntimeException e2) {
                stopSelf();
                return false;
            }
        }
        try {
            startForeground(NOTIFICATION_ID, notification);
            return true;
        } catch (RuntimeException e) {
            stopSelf();
            return false;
        }
    }

    private Notification buildNotification(int smallIcon) {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && manager != null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "عداد المسبحة العائم",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("يبقي عداد المسبحة العائم فعالاً أثناء استخدام التطبيقات الأخرى");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }

        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, openApp, pendingFlags);

        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(smallIcon)
                .setContentTitle("مسبحة جامع عبد الرحمن بن عوف")
                .setContentText("العداد العائم يعمل")
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Every startForegroundService() call must be answered with startForeground().
        if (!ensureForeground()) return START_NOT_STICKY;

        // Stop requests travel through the same intent queue as start requests, so a
        // pending startForegroundService() is always answered before the service stops.
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        double scale = DEFAULT_SCALE;
        if (intent != null && intent.hasExtra("scale")) {
            scale = intent.getDoubleExtra("scale", DEFAULT_SCALE);
        }

        if (floatingView == null) {
            createFloatingBubble(scale);
            startPolling();
        } else {
            applyScale(scale);
        }

        return START_STICKY;
    }

    private void updateCounterText() {
        if (bubbleCounter != null) {
            bubbleCounter.setText(String.valueOf(count));
        }
    }

    private void startPolling() {
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                SharedPreferences prefs = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
                int savedCount = prefs.getInt("bubble_count", count);
                if (savedCount != count) {
                    count = savedCount;
                    updateCounterText();
                }
                pollHandler.postDelayed(this, POLL_INTERVAL_MS);
            }
        };
        pollHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void stopPolling() {
        if (pollRunnable != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    private void createFloatingBubble(double scale) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            stopSelf();
            return;
        }
        SharedPreferences prefsInit = getSharedPreferences("bubble_prefs", MODE_PRIVATE);
        count = prefsInit.getInt("bubble_count", 0);

        floatingView = LayoutInflater.from(this).inflate(R.layout.layout_floating_bubble, null);
        bubbleCounter = floatingView.findViewById(R.id.bubble_counter);
        updateCounterText();

        int layoutFlag;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutFlag = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutFlag = WindowManager.LayoutParams.TYPE_PHONE;
        }

        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                layoutFlag,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.x = 0;
        params.y = 100;

        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        try {
            windowManager.addView(floatingView, params);
        } catch (SecurityException | WindowManager.BadTokenException | IllegalStateException e) {
            floatingView = null;
            bubbleCounter = null;
            stopSelf();
            return;
        }

        applyVisualScale(scale);

        ContextCompat.registerReceiver(
                this,
                webReceiver,
                new IntentFilter("WEB_UPDATED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );

        floatingView.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean isClick;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = params.x;
                        initialY = params.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isClick = true;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (Math.abs(event.getRawX() - initialTouchX) > 10 || Math.abs(event.getRawY() - initialTouchY) > 10) {
                            isClick = false;
                        }
                        params.x = initialX + (int) (event.getRawX() - initialTouchX);
                        params.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            windowManager.updateViewLayout(floatingView, params);
                        } catch (SecurityException | IllegalArgumentException | IllegalStateException e) {
                            stopSelf();
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (isClick) {
                            // Count natively first so the floating bubble keeps
                            // working even while the Activity/WebView is backgrounded.
                            count++;
                            getSharedPreferences("bubble_prefs", MODE_PRIVATE)
                                    .edit()
                                    .putInt("bubble_count", count)
                                    .apply();
                            updateCounterText();

                            // Notify the Activity when it is alive. JavaScript will
                            // treat this as an already-counted native bubble tap.
                            Intent tapIntent = new Intent("BUBBLE_TAPPED");
                            tapIntent.setPackage(getPackageName());
                            sendBroadcast(tapIntent);
                        }
                        return true;
                }
                return false;
            }
        });
    }

    private void applyVisualScale(double scale) {
        if (floatingView == null) return;
        float s = (float) scale;
        floatingView.setScaleX(s);
        floatingView.setScaleY(s);
    }

    private void applyScale(double scale) {
        applyVisualScale(scale);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopPolling();
        try {
            stopForeground(true);
        } catch (RuntimeException ignored) {
        }
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (IllegalArgumentException | IllegalStateException ignored) {
            }
        }
        floatingView = null;
        try {
            unregisterReceiver(webReceiver);
        } catch (IllegalArgumentException e) {
        }
    }
}
