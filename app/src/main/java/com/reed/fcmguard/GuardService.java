package com.reed.fcmguard;

import android.Manifest;
import android.app.AlarmManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;

public class GuardService extends Service {
    /** Start action that asks for an immediate check (unlock, network, alarm, boot...). */
    public static final String ACTION_CHECK = "com.reed.fcmguard.action.CHECK";
    public static final String EXTRA_REASON = "reason";

    // v2 intentionally uses a new channel id. Android does not allow an app to raise
    // an existing channel from IMPORTANCE_MIN to IMPORTANCE_LOW after creation.
    private static final String CHANNEL_ID = "fcm_guard_persistent_v2";
    private static final int NOTIFICATION_ID = 426;
    private static final long WATCHDOG_INTERVAL_MS = 30L * 60L * 1000L;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ContentObserver observer;
    private ContentObserver gameObserver;
    private String observedKey;
    private String observedRequiredItem;
    private boolean foreground;
    /** null until the first onStartCommand(); then the notification mode last applied. */
    private Boolean appliedPersistentMode;
    private boolean initialCheckDone;
    private GameAllowlist.Outcome lastGameOutcome;
    private String lastFailure;
    private String notificationText;
    private AppOpsManager.OnOpChangedListener opListener;

    private final Runnable repairDebounced = new Runnable() {
        @Override public void run() {
            repair();
        }
    };

    private final BroadcastReceiver screenOnReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            scheduleCheck(400L);
        }
    };

    /** Starts (or pokes) the guard service with an immediate check. */
    public static void start(Context context, String reason) {
        Intent service = new Intent(context, GuardService.class)
                .setAction(ACTION_CHECK)
                .putExtra(EXTRA_REASON, reason);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    SettingsGuard.usePersistentNotification(context)) {
                context.startForegroundService(service);
            } else {
                context.startService(service);
            }
        } catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        SettingsGuard.rememberIfUseful(this);
        EventLog.logPreviousProcessExits(this);
        watchWriteSettingsAppOp();
        registerReceiver(screenOnReceiver, new IntentFilter(Intent.ACTION_SCREEN_ON));
        // Observers, foreground state and the first check are set up in onStartCommand(),
        // which always follows onCreate() for this started-only service.
    }

    /**
     * Idempotent: MainActivity calls startService() on every resume, so a repeated start
     * only re-applies what actually changed instead of re-registering the observer and
     * running a full repair each time. ACTION_CHECK starts always run a check.
     */
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        SettingsGuard.setProtectionEnabled(this, true);
        applyExecutionMode();
        boolean configChanged = registerObserverIfNeeded();
        registerGameObserverIfNeeded();
        boolean checkRequested = intent != null && ACTION_CHECK.equals(intent.getAction());
        if (!initialCheckDone) {
            String reason = intent == null ? "restarted by system"
                    : intent.getStringExtra(EXTRA_REASON) != null ? intent.getStringExtra(EXTRA_REASON)
                    : "app opened";
            EventLog.log(this, "Guard service started (" + reason + ")");
        }
        if (configChanged || !initialCheckDone || checkRequested) {
            initialCheckDone = true;
            scheduleCheck(0L);
        }
        scheduleWatchdog();
        return START_STICKY;
    }

    private void scheduleCheck(long delayMs) {
        handler.removeCallbacks(repairDebounced);
        handler.postDelayed(repairDebounced, delayMs);
    }

    private void repair() {
        boolean canWrite = PermissionWatch.update(this);
        SettingsGuard.Result result = SettingsGuard.repair(this);
        String failure = result.success ? null : result.message;
        if (result.changed) {
            EventLog.log(this, "Re-added " + SettingsGuard.getConfiguredRequiredItem(this)
                    + " to " + SettingsGuard.getConfiguredKey(this));
            FcmReconnect.kick(this);
            refreshNotification(getString(R.string.notification_repaired));
        } else if (failure != null) {
            if (!failure.equals(lastFailure)) EventLog.log(this, "Whitelist repair failed: " + failure);
            refreshNotification(failure);
        } else {
            refreshNotification(getString(canWrite ? R.string.notification_active : R.string.permission_missing));
        }
        lastFailure = failure;
        checkGameAllowlist();
    }

    private void checkGameAllowlist() {
        GameAllowlist.Outcome outcome = GameAllowlist.ensure(this);
        if (outcome == GameAllowlist.Outcome.ADDED) {
            EventLog.log(this, "Night protection: added Google Play services to " + GameAllowlist.KEY);
        } else if (outcome == GameAllowlist.Outcome.FAILED && lastGameOutcome != outcome) {
            EventLog.log(this, "Night protection: could not write " + GameAllowlist.KEY);
        }
        lastGameOutcome = outcome;
    }

    /**
     * Calls back as soon as the Modify-system-settings app-op changes, so a revocation is
     * logged and alerted right away instead of at the next check.
     */
    private void watchWriteSettingsAppOp() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(APP_OPS_SERVICE);
            if (appOps == null) return;
            opListener = (op, packageName) -> {
                if (getPackageName().equals(packageName)) handler.post(() -> repair());
            };
            appOps.startWatchingMode(PermissionWatch.OPSTR_WRITE_SETTINGS, getPackageName(), opListener);
        } catch (Throwable ignored) {
            opListener = null;
        }
    }

    /**
     * A non-wakeup alarm: it never wakes a sleeping phone, but it is delivered at the next
     * wake-up and restarts the service if HyperOS killed the process (not after a force stop).
     */
    private void scheduleWatchdog() {
        AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarms == null) return;
        try {
            alarms.set(AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS, watchdogIntent());
        } catch (Throwable ignored) {}
    }

    private PendingIntent watchdogIntent() {
        Intent intent = new Intent(this, GuardService.class)
                .setAction(ACTION_CHECK)
                .putExtra(EXTRA_REASON, "watchdog alarm");
        return PendingIntent.getService(this, 2, intent,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /**
     * Watches the configured key. Returns true when the key or the required item changed
     * since the last call (including the very first call), i.e. when an immediate check
     * is worthwhile.
     */
    private boolean registerObserverIfNeeded() {
        String key = SettingsGuard.getConfiguredKey(this);
        String requiredItem = SettingsGuard.getConfiguredRequiredItem(this);
        boolean keyChanged = observer == null || !key.equals(observedKey);
        boolean changed = keyChanged || !requiredItem.equals(observedRequiredItem);
        observedRequiredItem = requiredItem;
        if (!keyChanged) return changed;

        ContentResolver resolver = getContentResolver();
        try {
            if (observer != null) resolver.unregisterContentObserver(observer);
        } catch (Throwable ignored) {}

        observer = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                scheduleCheck(400L);
            }
        };
        resolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        observedKey = key;
        return true;
    }

    private void registerGameObserverIfNeeded() {
        if (gameObserver != null) return;
        gameObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange, Uri uri) {
                scheduleCheck(400L);
            }
        };
        try {
            getContentResolver().registerContentObserver(
                    Settings.System.getUriFor(GameAllowlist.KEY), false, gameObserver);
        } catch (Throwable ignored) {}
    }

    private void applyExecutionMode() {
        boolean persistent = SettingsGuard.usePersistentNotification(this);
        boolean modeChanged = appliedPersistentMode == null || appliedPersistentMode != persistent;
        appliedPersistentMode = persistent;

        if (persistent) {
            if (modeChanged) ensureNotificationChannel(this);
            // Always re-enter the foreground: a startForegroundService() call must be
            // answered with startForeground(), and it re-posts the notification after the
            // user re-enables notifications in system settings.
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_active)));
            foreground = true;
        } else if (modeChanged) {
            if (foreground) stopForeground(true);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIFICATION_ID);
            foreground = false;
        }
    }

    /**
     * Creates the visible-but-silent foreground-service channel. Returns true only
     * when this call created the channel for the first time.
     */
    public static boolean ensureNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false;
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        boolean created = nm.getNotificationChannel(CHANNEL_ID) == null;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.enableLights(false);
        channel.setSound(null, null);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        nm.createNotificationChannel(channel);
        return created;
    }

    /** True when Android will actually place the foreground notification in the shade. */
    public static boolean canShowPersistentNotification(Context context) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return false;

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !nm.areNotificationsEnabled()) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= 33 &&
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = nm.getNotificationChannel(CHANNEL_ID);
            return channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
        }
        return true;
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        initialCheckDone = false;
        // onDestroy() runs when protection is switched off (not when HyperOS kills the
        // process), so the watchdog must not bring the service back.
        AlarmManager alarms = (AlarmManager) getSystemService(ALARM_SERVICE);
        if (alarms != null) alarms.cancel(watchdogIntent());
        try {
            unregisterReceiver(screenOnReceiver);
        } catch (Throwable ignored) {}
        try {
            if (observer != null) getContentResolver().unregisterContentObserver(observer);
            if (gameObserver != null) getContentResolver().unregisterContentObserver(gameObserver);
        } catch (Throwable ignored) {}
        if (opListener != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                ((AppOpsManager) getSystemService(APP_OPS_SERVICE)).stopWatchingMode(opListener);
            } catch (Throwable ignored) {}
        }
        EventLog.log(this, "Guard service stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void refreshNotification(String text) {
        if (!foreground || text.equals(notificationText)) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }

    private Notification buildNotification(String text) {
        notificationText = text;
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder = new Notification.Builder(this, CHANNEL_ID);
        } else {
            builder = new Notification.Builder(this).setPriority(Notification.PRIORITY_LOW);
        }

        return builder
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(text)
                .setContentIntent(pi)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }
}
