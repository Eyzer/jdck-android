package cn.xanderye.android.jdck.util;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.content.ContextCompat;
import androidx.work.WorkManager;
import cn.xanderye.android.jdck.receiver.CheckAlarmReceiver;
import cn.xanderye.android.jdck.service.AutoLoginService;

/**
 * 后台自动登录的开关与定时唤醒
 */
public class CkSchedule {

    /**
     * 兼容旧版本的 WorkManager 任务名，开启新方案时清掉它
     */
    private static final String LEGACY_WORK = "jd-ck-check";

    private static final String PREF = "CONFIG";

    public static final String PREF_ENABLED = "ckCheck";

    private static final String PREF_HOURS = "ckHours";

    public static final int DEFAULT_HOURS = 3;

    private static final int ALARM_REQUEST = 2;

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(PREF_ENABLED, false);
    }

    public static int hours(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt(PREF_HOURS, DEFAULT_HOURS);
    }

    public static void enable(Context ctx, int hours) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean(PREF_ENABLED, true)
                .putInt(PREF_HOURS, hours)
                .apply();
        // 旧实现依赖 WorkManager 检测后拉界面，后台拉不起来，这里清掉避免重复任务
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(LEGACY_WORK);
        } catch (Exception ignored) {
        }
        startService(ctx);
        scheduleAlarm(ctx, hours);
    }

    public static void disable(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_ENABLED, false).apply();
        cancelAlarm(ctx);
        try {
            WorkManager.getInstance(ctx).cancelUniqueWork(LEGACY_WORK);
        } catch (Exception ignored) {
        }
        ctx.stopService(new Intent(ctx, AutoLoginService.class));
    }

    public static void startService(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, AutoLoginService.class));
    }

    public static void scheduleAlarm(Context ctx, int hours) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) {
            return;
        }
        long trigger = System.currentTimeMillis() + Math.max(1, hours) * 3600_000L;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(ctx));
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(ctx));
            }
        } catch (SecurityException e) {
            // 没拿到精确闹钟权限时退回允许休眠唤醒的非精确闹钟
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, alarmIntent(ctx));
        }
    }

    public static void cancelAlarm(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am != null) {
            am.cancel(alarmIntent(ctx));
        }
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent intent = new Intent(ctx, CheckAlarmReceiver.class);
        intent.setAction(CheckAlarmReceiver.ACTION);
        return PendingIntent.getBroadcast(ctx, ALARM_REQUEST, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
}
