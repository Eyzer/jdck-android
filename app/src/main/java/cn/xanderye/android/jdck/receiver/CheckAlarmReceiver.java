package cn.xanderye.android.jdck.receiver;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import cn.xanderye.android.jdck.util.CkSchedule;

/**
 * 定时唤醒后台自动登录服务，服务被系统清掉也能重新起来
 */
public class CheckAlarmReceiver extends BroadcastReceiver {

    public static final String ACTION = "cn.xanderye.android.jdck.CHECK_ALARM";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!CkSchedule.isEnabled(context)) {
            return;
        }
        CkSchedule.startService(context);
        CkSchedule.scheduleAlarm(context, CkSchedule.hours(context));
    }
}
