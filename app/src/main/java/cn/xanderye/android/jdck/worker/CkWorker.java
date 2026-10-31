package cn.xanderye.android.jdck.worker;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import cn.xanderye.android.jdck.R;
import cn.xanderye.android.jdck.activity.MainActivity;
import cn.xanderye.android.jdck.util.CkUtil;

/**
 * 定时检测 cookie，失效则拉起登录页并提示
 */
public class CkWorker extends Worker {

    private static final String CHANNEL = "ck";
    private static final int NOTIFY_ID = 1001;

    public CkWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (CkUtil.isValid(CkUtil.currentCookie())) {
            return Result.success();
        }
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(MainActivity.EXTRA_AUTO_LOGIN, true);
        try {
            ctx.startActivity(intent);
        } catch (Exception ignored) {
            // 系统禁止后台启动界面时靠通知兜底
        }
        notifyUser(ctx, intent);
        return Result.success();
    }

    private void notifyUser(Context ctx, Intent intent) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "CK检测", NotificationManager.IMPORTANCE_HIGH));
        }
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("京东CK已失效")
                .setContentText("点击重新登录并上传cookie")
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH);
        nm.notify(NOTIFY_ID, builder.build());
    }
}
