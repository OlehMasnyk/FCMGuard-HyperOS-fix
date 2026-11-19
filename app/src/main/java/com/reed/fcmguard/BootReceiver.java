package com.reed.fcmguard;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restarts the guard and runs a check after boot, app update, unlock, network changes and
 * charger connection. Manifest delivery of the implicit ones relies on targetSdk 22 (the
 * Android 7/8 implicit-broadcast limits apply only to newer targets) and on HyperOS
 * Autostart being allowed. Nothing is delivered after a force stop until the app is opened.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (!SettingsGuard.isProtectionEnabled(context)) return;
        String action = intent.getAction();
        GuardService.start(context, action == null ? "broadcast" : action.substring(action.lastIndexOf('.') + 1));
    }
}
