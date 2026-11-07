package cn.xanderye.android.jdck.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import cn.xanderye.android.jdck.util.CkSchedule;

/**
 * 开机后恢复后台自动登录
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!CkSchedule.isEnabled(context)) {
            return;
        }
        CkSchedule.startService(context);
        CkSchedule.scheduleAlarm(context, CkSchedule.hours(context));
    }
}
