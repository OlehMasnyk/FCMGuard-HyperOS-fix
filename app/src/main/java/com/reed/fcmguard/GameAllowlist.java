package com.reed.fcmguard;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Night protection for HyperOS 4. Between 23:00 and 07:59, with the screen off in deep Doze,
 * HyperOS 4 stops honouring MILLET_NO_RESTRICT_APP for non-messaging apps and freezes Google
 * Play services ("nightDoze"). Greezer still skips packages listed in the game-predownload
 * exemption key, which has no night condition, so GMS is kept there too.
 *
 * Format quirks of the key: packages are joined with '_', the framework splits with
 * String.split("_") and matches exactly (no trimming, trailing empty tokens dropped).
 * SecurityCenter and Joyose rewrite or clear the whole value around game downloads.
 * The framework only reloads it from its observer, so a value left from before a reboot
 * has to be touched once per boot to take effect.
 */
public final class GameAllowlist {
    public static final String KEY = "com.miui.game.allowlist";
    private static final String GMS = "com.google.android.gms";
    private static final String PREF_ENABLED = "night_protection";
    private static final String PREF_ADDED_BY_US = "game_allowlist_added";
    private static final String PREF_NUDGED_BOOT = "game_allowlist_nudged_boot";

    public enum Outcome { DISABLED, PRESENT, ADDED, FAILED }

    private GameAllowlist() {}

    public static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, true);
    }

    public static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (!enabled) removeIfAddedByUs(context);
    }

    public static String read(Context context) {
        return Settings.System.getString(context.getContentResolver(), KEY);
    }

    public static boolean contains(String value) {
        if (value == null) return false;
        for (String token : value.split("_")) {
            if (GMS.equals(token)) return true;
        }
        return false;
    }

    public static synchronized Outcome ensure(Context context) {
        if (!isEnabled(context)) return Outcome.DISABLED;
        ContentResolver resolver = context.getContentResolver();
        String current = read(context);

        if (contains(current)) {
            nudgeOncePerBoot(context, current);
            return Outcome.PRESENT;
        }

        String updated = current == null || current.isEmpty() ? GMS
                : current.endsWith("_") ? current + GMS
                : current + "_" + GMS;
        try {
            Settings.System.putString(resolver, KEY, updated);
        } catch (Throwable ignored) {}
        if (!contains(read(context))) return Outcome.FAILED;

        if (!prefs(context).getBoolean(PREF_ADDED_BY_US, false)) {
            prefs(context).edit().putBoolean(PREF_ADDED_BY_US, true).apply();
        }
        markNudged(context);
        return Outcome.ADDED;
    }

    /**
     * Changes the stored string without changing membership (one trailing '_'), so the
     * framework observer loads a value that was already present before this boot.
     */
    private static void nudgeOncePerBoot(Context context, String current) {
        int boot = bootCount(context);
        if (boot < 0 || prefs(context).getInt(PREF_NUDGED_BOOT, -1) == boot) return;
        String touched = current.endsWith("_") ? current.substring(0, current.length() - 1) : current + "_";
        try {
            Settings.System.putString(context.getContentResolver(), KEY, touched);
        } catch (Throwable ignored) {
            return;
        }
        markNudged(context);
    }

    private static void removeIfAddedByUs(Context context) {
        if (!prefs(context).getBoolean(PREF_ADDED_BY_US, false)) return;
        String current = read(context);
        if (current != null && contains(current)) {
            List<String> kept = new ArrayList<>();
            for (String token : current.split("_")) {
                if (!token.isEmpty() && !GMS.equals(token)) kept.add(token);
            }
            try {
                // An empty string, not a delete: the framework ignores a null read and
                // would keep its cached membership.
                Settings.System.putString(context.getContentResolver(), KEY, TextUtils.join("_", kept));
            } catch (Throwable ignored) {
                return;
            }
            if (contains(read(context))) return;
        }
        prefs(context).edit().putBoolean(PREF_ADDED_BY_US, false).apply();
        EventLog.log(context, "Night protection off: removed Google Play services from " + KEY);
    }

    private static void markNudged(Context context) {
        int boot = bootCount(context);
        if (boot >= 0) prefs(context).edit().putInt(PREF_NUDGED_BOOT, boot).apply();
    }

    private static int bootCount(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return -1;
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(SettingsGuard.PREFS, Context.MODE_PRIVATE);
    }
}
