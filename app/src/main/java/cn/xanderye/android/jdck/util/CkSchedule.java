package cn.xanderye.android.jdck.util;

import android.content.Context;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import cn.xanderye.android.jdck.worker.CkWorker;

import java.util.concurrent.TimeUnit;

/**
 * 定时检测任务开关
 */
public class CkSchedule {

    private static final String NAME = "jd-ck-check";

    public static void enable(Context ctx, int hours) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(CkWorker.class, hours, TimeUnit.HOURS).build();
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.REPLACE, request);
    }

    public static void disable(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(NAME);
    }
}
