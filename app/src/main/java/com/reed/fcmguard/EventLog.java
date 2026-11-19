package com.reed.fcmguard;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Small on-device event log so a failure overnight can be explained the next morning:
 * permission changes, repairs, and why the previous process ended. Only state changes are
 * written, so the file stays tiny on a healthy phone.
 */
public final class EventLog {
    private static final String TAG = "FCMGuard";
    private static final String FILE_NAME = "events.log";
    private static final int MAX_BYTES = 96 * 1024;
    private static final int KEEP_BYTES = 48 * 1024;
    private static final String PREF_LAST_EXIT_TS = "last_logged_exit_ts";

    private EventLog() {}

    public static synchronized void log(Context context, String message) {
        Log.i(TAG, message);
        String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                + "  " + message + "\n";
        File file = file(context);
        try (FileOutputStream out = new FileOutputStream(file, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            return;
        }
        if (file.length() > MAX_BYTES) trim(file);
    }

    public static synchronized String read(Context context) {
        File file = file(context);
        if (!file.exists()) return "";
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] data = new byte[(int) file.length()];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) break;
                read += n;
            }
            return new String(data, 0, read, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public static synchronized void clear(Context context) {
        //noinspection ResultOfMethodCallIgnored
        file(context).delete();
    }

    /**
     * Logs how earlier FCM Guard processes ended (force stop, freezer, low memory, ...), once
     * per exit. Android 11+ only; this is the most direct evidence of HyperOS killing the app.
     */
    public static void logPreviousProcessExits(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        try {
            ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (am == null) return;
            List<ApplicationExitInfo> exits =
                    am.getHistoricalProcessExitReasons(context.getPackageName(), 0, 10);
            if (exits == null || exits.isEmpty()) return;

            SharedPreferences prefs = context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
            long lastLogged = prefs.getLong(PREF_LAST_EXIT_TS, 0L);
            List<ApplicationExitInfo> fresh = new ArrayList<>();
            for (ApplicationExitInfo exit : exits) {
                if (exit.getTimestamp() > lastLogged) fresh.add(exit);
            }
            if (fresh.isEmpty()) return;
            Collections.reverse(fresh); // The API returns newest first.

            SimpleDateFormat time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US);
            long newest = lastLogged;
            for (ApplicationExitInfo exit : fresh) {
                String description = exit.getDescription();
                log(context, "Previous process ended at " + time.format(new Date(exit.getTimestamp()))
                        + ": " + reasonName(exit.getReason())
                        + " (importance " + exit.getImportance() + ")"
                        + (description == null || description.isEmpty() ? "" : " — " + description));
                newest = Math.max(newest, exit.getTimestamp());
            }
            prefs.edit().putLong(PREF_LAST_EXIT_TS, newest).apply();
        } catch (Throwable ignored) {}
    }

    private static String reasonName(int reason) {
        switch (reason) {
            case ApplicationExitInfo.REASON_EXIT_SELF: return "exited itself";
            case ApplicationExitInfo.REASON_SIGNALED: return "killed by signal";
            case ApplicationExitInfo.REASON_LOW_MEMORY: return "low memory";
            case ApplicationExitInfo.REASON_CRASH: return "crash";
            case ApplicationExitInfo.REASON_CRASH_NATIVE: return "native crash";
            case ApplicationExitInfo.REASON_ANR: return "ANR";
            case ApplicationExitInfo.REASON_INITIALIZATION_FAILURE: return "initialization failure";
            case ApplicationExitInfo.REASON_PERMISSION_CHANGE: return "permission change";
            case ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE: return "excessive resource usage";
            case ApplicationExitInfo.REASON_USER_REQUESTED: return "force stopped";
            case ApplicationExitInfo.REASON_USER_STOPPED: return "user stopped";
            case ApplicationExitInfo.REASON_DEPENDENCY_DIED: return "dependency died";
            case ApplicationExitInfo.REASON_OTHER: return "other (system)";
            case ApplicationExitInfo.REASON_FREEZER: return "freezer";
            case ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE: return "package state change";
            case ApplicationExitInfo.REASON_PACKAGE_UPDATED: return "package updated";
            default: return "unknown (" + reason + ")";
        }
    }

    private static File file(Context context) {
        return new File(context.getApplicationContext().getFilesDir(), FILE_NAME);
    }

    private static void trim(File file) {
        try (FileInputStream in = new FileInputStream(file)) {
            long skip = file.length() - KEEP_BYTES;
            if (in.skip(skip) != skip) return;
            byte[] tail = new byte[KEEP_BYTES];
            int read = 0;
            while (read < tail.length) {
                int n = in.read(tail, read, tail.length - read);
                if (n < 0) break;
                read += n;
            }
            int start = 0;
            while (start < read && tail[start] != '\n') start++;
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                out.write(tail, Math.min(start + 1, read), Math.max(0, read - start - 1));
            }
        } catch (IOException ignored) {}
    }
}
