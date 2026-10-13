package io.github.ekotov.davmail;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restarts the gateway after boot or an app update if it was left running. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if ((Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))
                && Config.isEnabled(context)) {
            GatewayService.start(context);
        }
    }
}
