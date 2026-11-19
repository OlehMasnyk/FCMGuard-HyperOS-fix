package com.reed.fcmguard;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Process;
import android.provider.Settings;

/**
 * Tracks whether FCM Guard can still write system settings. HyperOS has been seen turning
 * the Modify-system-settings switch off overnight; when that happens the user gets a
 * notification that opens the grant screen, and every change is written to the event log.
 */
public final class PermissionWatch {
    public static final String OPSTR_WRITE_SETTINGS = "android:write_settings";
    private static final String ALERT_CHANNEL_ID = "fcm_guard_alerts";
    private static final int ALERT_ID = 427;
    private static final String PREF_STATE = "write_access_state";
    private static final String PREF_LAST_ALERT = "write_access_last_alert";
    private static final long REALERT_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private PermissionWatch() {}

    /**
     * Re-reads the permission state, logs any change and shows or clears the alert.
     * Returns true when FCM Guard can write system settings.
     */
    public static synchronized boolean update(Context context) {
        boolean adb = SettingsGuard.hasAdbGrant(context);
        boolean appOp = SettingsGuard.hasModifySettingsAppOp(context);
        boolean canWrite = adb || appOp;
        String state = "modify-settings=" + (appOp ? "on" : "off")
                + " (app-op " + appOpModeName(context) + ")"
                + ", adb-grant=" + (adb ? "yes" : "no");

        SharedPreferences prefs = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
        String previous = prefs.getString(PREF_STATE, null);
        boolean changed = !state.equals(previous);
        if (changed) {
            prefs.edit().putString(PREF_STATE, state).apply();
            EventLog.log(context, (previous == null ? "Write access: " : "Write access changed: ")
                    + state + (previous == null ? "" : " (was " + previous + ")"));
        }

        if (canWrite) {
            cancelAlert(context);
        } else {
            long now = System.currentTimeMillis();
            long lastAlert = prefs.getLong(PREF_LAST_ALERT, 0L);
            if (changed || now - lastAlert > REALERT_INTERVAL_MS || now < lastAlert) {
                prefs.edit().putLong(PREF_LAST_ALERT, now).apply();
                showAlert(context);
            }
        }
        return canWrite;
    }

    private static String appOpModeName(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return "n/a";
        try {
            AppOpsManager appOps = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) return "n/a";
            int mode = appOps.checkOpNoThrow(OPSTR_WRITE_SETTINGS, Process.myUid(), context.getPackageName());
            switch (mode) {
                case AppOpsManager.MODE_ALLOWED: return "allow";
                case AppOpsManager.MODE_IGNORED: return "ignore";
                case AppOpsManager.MODE_ERRORED: return "deny";
                case AppOpsManager.MODE_DEFAULT: return "default";
                case AppOpsManager.MODE_FOREGROUND: return "foreground";
                default: return "mode " + mode;
            }
        } catch (Throwable t) {
            return "unreadable";
        }
    }

    private static void showAlert(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(ALERT_CHANNEL_ID,
                    context.getString(R.string.alert_channel_name), NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription(context.getString(R.string.alert_channel_description));
            nm.createNotificationChannel(channel);
            builder = new Notification.Builder(context, ALERT_CHANNEL_ID);
        } else {
            builder = new Notification.Builder(context).setPriority(Notification.PRIORITY_HIGH);
        }

        String text = context.getString(R.string.permission_lost_text);
        nm.notify(ALERT_ID, builder
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setContentTitle(context.getString(R.string.permission_lost_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(grantScreenIntent(context))
                .setCategory(Notification.CATEGORY_ERROR)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .build());
    }

    private static void cancelAlert(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(ALERT_ID);
    }

    private static PendingIntent grantScreenIntent(Context context) {
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + context.getPackageName()));
            if (intent.resolveActivity(context.getPackageManager()) == null) {
                intent = new Intent(context, MainActivity.class);
            }
        } else {
            intent = new Intent(context, MainActivity.class);
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 1, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
