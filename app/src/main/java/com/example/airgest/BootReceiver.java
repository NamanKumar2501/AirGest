package com.example.airgest;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;

/**
 * "Start Air Control after device restart".
 *
 * Android 11+ does not let a camera app start using the camera from a boot broadcast (and Android 14
 * forbids camera foreground services from BOOT_COMPLETED), so silently auto-starting is impossible
 * by design. What we CAN do, and what keeps the user in control, is post a notification after boot;
 * tapping it opens Air Control and starts the controller. We also never enable the accessibility
 * service ourselves - that stays the user's decision in Android Settings.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String CHANNEL_ID = "air_control_boot";
    private static final int NOTIFICATION_ID = 1002;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        SettingsManager settings = new SettingsManager(context);
        if (!settings.isBootPromptEnabled()) return;
        if (!AirControlAccessibilityService.isEnabledInSettings(context)) return;
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        if (Build.VERSION.SDK_INT >= 33
                && context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                    context.getString(R.string.boot_channel_name), NotificationManager.IMPORTANCE_DEFAULT));
        }
        Intent open = new Intent(context, MainActivity.class)
                .putExtra(MainActivity.EXTRA_AUTO_START, true)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(context, 2, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.boot_notif_title))
                .setContentText(context.getString(R.string.boot_notif_text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTIFICATION_ID, n);
    }
}
