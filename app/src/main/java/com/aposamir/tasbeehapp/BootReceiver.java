package com.aposamir.tasbeehapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import androidx.core.content.ContextCompat;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;

        SharedPreferences prefs = context.getSharedPreferences("bubble_prefs", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("bubble_enabled", false)) return;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            prefs.edit().putBoolean("bubble_enabled", false).apply();
            return;
        }

        Intent serviceIntent = new Intent(context, FloatingService.class);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
