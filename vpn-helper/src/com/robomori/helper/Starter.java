package com.robomori.helper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Turns the VPN on for adb, with {@code am broadcast -n com.robomori.helper/.Starter --ei port 18765}, or off with
 * {@code --ez stop true}. A broadcast, because Android lets only the system start a VPN service directly.
 */
public final class Starter extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Intent vpn = new Intent(context, InspectionVpn.class);
        if (intent.getBooleanExtra("stop", false)) {
            vpn.setAction(InspectionVpn.STOP);
            // Without a running VPN there is nothing to stop, and Android refuses to start the service for it.
            try { context.startService(vpn); } catch (IllegalStateException ignored) { }
            return;
        }
        vpn.putExtra("port", intent.getIntExtra("port", 0));
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(vpn);
        else context.startService(vpn);
    }
}
