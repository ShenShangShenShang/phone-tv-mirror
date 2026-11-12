package tv.debloat.mirror;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机就把接收服务拉起来。
 * 电视通电以后不用人去点图标，手机随时能连进来。
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        try {
            if (Intent.ACTION_BOOT_COMPLETED.equals(i.getAction())) {
                c.startService(new Intent(c, MirrorService.class));
            }
        } catch (Throwable ignored) {}
    }
}
