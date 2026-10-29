package io.github.srikarmk.hearth;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Opt in to leaving the home helper while the screen is off.
 * {@code adb shell am broadcast -a io.github.srikarmk.hearth.GUARD -n io.github.srikarmk.hearth/.GuardConfig --ez guard_off_in_standby true}
 */
public class GuardConfig extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.hasExtra("guard_off_in_standby")) {
            GuardKeep.setOffInStandby(context, intent.getBooleanExtra("guard_off_in_standby", false));
            GuardKeep.ensureHomeKey(context);
        }
        setResultData("guard_off_in_standby=" + GuardKeep.offInStandby(context));
    }
}
