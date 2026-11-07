package cn.xanderye.android.jdck.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import cn.xanderye.android.jdck.R;
import cn.xanderye.android.jdck.activity.MainActivity;
import cn.xanderye.android.jdck.entity.QlEnv;
import cn.xanderye.android.jdck.entity.QlInfo;
import cn.xanderye.android.jdck.util.AppState;
import cn.xanderye.android.jdck.util.CkSchedule;
import cn.xanderye.android.jdck.util.CkUtil;
import cn.xanderye.android.jdck.util.JDUtil;
import cn.xanderye.android.jdck.util.JsUtil;
import cn.xanderye.android.jdck.util.QinglongUtil;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 后台自动维护京东 CK：
 * 定时检测登录态 -> 失效则在悬浮窗里静默加载京东登录页 -> 自动走运营商本机号码一键登录
 * -> 拿到新的 pt_key 后自动写入青龙面板。
 * <p>
 * 全部在 Service 里完成，不需要拉起界面，也就绕开了 Android 10+ 的后台启动界面限制。
 */
public class AutoLoginService extends Service {

    private static final String CHANNEL_FG = "auto_login_fg";
    private static final String CHANNEL_ALERT = "auto_login_alert";

    private static final int NOTIFY_FG = 2001;
    private static final int NOTIFY_ALERT = 2002;

    private static final String PREF = "CONFIG";
    private static final String PK_LAST_UPLOADED = "lastUploadedKey";
    private static final String PK_LAST_COOKIE = "lastCookie";

    private static final long LOGIN_TIMEOUT_MS = 90 * 1000L;
    private static final long POLL_INTERVAL_MS = 2000L;

    private static final String JD_URL = CkUtil.JD_URL;

    private final Handler main = new Handler(Looper.getMainLooper());

    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private SharedPreferences config;

    private WebView webView;

    private WindowManager windowManager;

    private boolean overlayAttached;

    private boolean checking;

    private boolean loggingIn;

    private long loginDeadline;

    private boolean filledThisLogin;

    private boolean oneKeyFired;

    private String lastAlertKey = "";

    private ConnectivityManager connectivityManager;

    private ConnectivityManager.NetworkCallback cellularCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        config = getSharedPreferences(PREF, Context.MODE_PRIVATE);
        createChannels();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 先转前台，避免 startForegroundService 后 5 秒内没转前台被系统判定超时
        startForeground(NOTIFY_FG, buildNotification(false, "京东CK自动维护中", "正在检测京东CK…"));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!CkSchedule.isEnabled(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!checking && !loggingIn) {
            runCheck();
        }
        return START_STICKY;
    }

    public static final String ACTION_STOP = "cn.xanderye.android.jdck.STOP";

    @Override
    public void onDestroy() {
        main.removeCallbacksAndMessages(null);
        releaseCellular();
        removeOverlay();
        if (webView != null) {
            webView.destroy();
            webView = null;
        }
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 检测

    private void runCheck() {
        checking = true;
        updateForeground("正在检测京东CK…");
        ensureWebView();
        // WebView 的 Cookie 库是异步初始化的，稍等一下再读，避免拿到空 Cookie 误判失效
        main.postDelayed(this::doCheck, 800);
    }

    private void doCheck() {
        String cookie = snapshotCookie();
        io.execute(() -> {
            boolean valid = CkUtil.isValid(cookie);
            main.post(() -> {
                checking = false;
                if (valid) {
                    String cookieNow = snapshotCookie();
                    Map<String, Object> map = JDUtil.formatCookies(cookieNow);
                    String ptKey = (String) map.get("pt_key");
                    String ptPin = (String) map.get("pt_pin");
                    config.edit().putString(PK_LAST_COOKIE, cookieNow).apply();
                    // 页面加载时可能已经刷新过 CK，顺手补齐
                    if (StringUtils.isNotBlank(ptKey) && StringUtils.isNotBlank(ptPin)
                            && !ptKey.equals(config.getString(PK_LAST_UPLOADED, ""))) {
                        upload(cookieNow, ptPin, ptKey);
                    }
                    updateForeground("CK有效 · " + now() + " 已检测");
                    scheduleNext();
                } else if (AppState.foreground) {
                    // 用户正在看 App，不抢他的登录页；退到后台后再自动登录
                    updateForeground("CK已失效，退到后台后自动登录…");
                    main.postDelayed(() -> {
                        if (!loggingIn && !checking && !AppState.foreground) {
                            startLogin();
                        } else {
                            scheduleNext();
                        }
                    }, 30000);
                } else {
                    startLogin();
                }
            });
        });
    }

    /**
     * 优先取 WebView 里的 Cookie，取不到时用上次校验通过的快照
     */
    private String snapshotCookie() {
        String cookie = CkUtil.currentCookie();
        Map<String, Object> map = JDUtil.formatCookies(cookie);
        if (StringUtils.isBlank((String) map.get("pt_key"))) {
            String saved = config.getString(PK_LAST_COOKIE, "");
            if (StringUtils.isNotBlank(saved)) {
                return saved;
            }
        }
        return cookie == null ? "" : cookie;
    }

    private void scheduleNext() {
        CkSchedule.scheduleAlarm(this, CkSchedule.hours(this));
    }

    // ---------------------------------------------------------------- 登录

    private void startLogin() {
        loggingIn = true;
        filledThisLogin = false;
        oneKeyFired = false;
        loginDeadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;

        ensureWebView();
        attachOverlay();
        bindCellular();
        updateForeground("CK已失效，正在后台自动登录…");

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        // 必须等 Cookie 真正清完再加载，否则会把过期的 pt_key 当成登录成功
        cookieManager.removeAllCookies(value -> main.post(() -> {
            if (!loggingIn || webView == null) {
                return;
            }
            cookieManager.flush();
            webView.clearCache(true);
            webView.loadUrl(JD_URL);
            main.postDelayed(loginPoll, 2000);
            // 兜底：页面脚本卡住时也要收尾，避免服务一直停在登录中
            main.postDelayed(loginTimeout, LOGIN_TIMEOUT_MS + 15000);
        }));
    }

    private final Runnable loginTimeout = new Runnable() {
        @Override
        public void run() {
            if (loggingIn) {
                giveUp("自动登录超时，可能京东未下发号码认证入口");
            }
        }
    };

    private final Runnable loginPoll = new Runnable() {
        @Override
        public void run() {
            if (!loggingIn || webView == null) {
                return;
            }
            String cookie = CookieManager.getInstance().getCookie(JD_URL);
            Map<String, Object> map = JDUtil.formatCookies(cookie);
            String ptKey = (String) map.get("pt_key");
            String ptPin = (String) map.get("pt_pin");
            if (StringUtils.isNotBlank(ptKey) && StringUtils.isNotBlank(ptPin)) {
                finishLogin(cookie, ptPin, ptKey);
                return;
            }
            if (System.currentTimeMillis() > loginDeadline) {
                giveUp("自动登录超时，可能京东未下发号码认证入口");
                return;
            }

            String account = selectedAccount();
            webView.evaluateJavascript(JsUtil.pageStateJs(), state -> {
                if (!loggingIn) {
                    return;
                }
                if ("\"captcha\"".equals(state)) {
                    giveUp("京东要求安全验证，需要手动处理");
                    return;
                }
                String url = webView.getUrl();
                if (!filledThisLogin && url != null && url.contains("plogin.m.jd.com")) {
                    filledThisLogin = true;
                    webView.evaluateJavascript(JsUtil.fillLoginJs(account), null);
                }
                // JS 内部有 3 秒节流，这里持续重试即可
                webView.evaluateJavascript(JsUtil.oneKeyJs(phoneOf(account)), value -> {
                    if ("\"ok\"".equals(value)) {
                        oneKeyFired = true;
                        updateForeground("已触发运营商本机号码认证，等待登录结果…");
                    }
                });
                if (loggingIn) {
                    main.postDelayed(loginPoll, POLL_INTERVAL_MS);
                }
            });
        }
    };

    private void finishLogin(String cookie, String ptPin, String ptKey) {
        loggingIn = false;
        main.removeCallbacks(loginPoll);
        main.removeCallbacks(loginTimeout);
        CookieManager.getInstance().flush();
        config.edit().putString(PK_LAST_COOKIE, cookie).apply();
        releaseCellular();
        removeOverlay();
        updateForeground("登录成功，正在写入青龙…");
        upload(cookie, ptPin, ptKey);
    }

    private void giveUp(String reason) {
        loggingIn = false;
        main.removeCallbacks(loginPoll);
        main.removeCallbacks(loginTimeout);
        releaseCellular();
        removeOverlay();
        alert("自动登录未完成", reason);
        updateForeground("自动登录失败 · " + now());
        scheduleNext();
    }

    // ---------------------------------------------------------------- 上传青龙

    private void upload(String cookie, String ptPin, String ptKey) {
        io.execute(() -> {
            QlInfo qlInfo = loadQlInfo();
            if (qlInfo == null || StringUtils.isBlank(qlInfo.getToken())) {
                main.post(() -> {
                    alert("京东CK已更新，但未配置青龙面板", "请打开 App 配置青龙面板地址与密钥");
                    updateForeground("登录成功，未配置青龙 · " + now());
                    scheduleNext();
                });
                return;
            }
            try {
                List<QlEnv> envList;
                try {
                    envList = QinglongUtil.getEnvList(qlInfo, "");
                } catch (IOException first) {
                    // 青龙 token 过期时用 client_id/client_secret 重新换一个
                    qlInfo.setToken(QinglongUtil.login(qlInfo));
                    saveQlInfo(qlInfo);
                    envList = QinglongUtil.getEnvList(qlInfo, "");
                }
                QlEnv target = null;
                for (QlEnv env : envList) {
                    Map<String, Object> envMap = JDUtil.formatCookies(env.getValue());
                    if (ptPin.equals(envMap.get("pt_pin"))) {
                        target = env;
                        break;
                    }
                }
                if (target == null) {
                    target = new QlEnv();
                    target.setName("JD_COOKIE");
                }
                target.setValue("pt_key=" + ptKey + ";pt_pin=" + ptPin + ";");
                target.setRemarks(phoneOf(selectedAccount()));
                QinglongUtil.saveEnv(qlInfo, target);
                config.edit().putString(PK_LAST_UPLOADED, ptKey).apply();

                // 新建的环境变量需要再查一次拿到 id 才能启用
                try {
                    for (QlEnv env : QinglongUtil.getEnvList(qlInfo, "")) {
                        Map<String, Object> envMap = JDUtil.formatCookies(env.getValue());
                        if (ptPin.equals(envMap.get("pt_pin"))) {
                            QinglongUtil.EableEnv(qlInfo, env);
                            break;
                        }
                    }
                } catch (IOException ignored) {
                }

                main.post(() -> {
                    updateForeground("CK已自动更新到青龙 · " + now());
                    alert("京东CK已自动更新", "已写入青龙面板，无需手动操作");
                    scheduleNext();
                });
            } catch (IOException e) {
                main.post(() -> {
                    alert("写入青龙失败", "京东CK已更新，但推送青龙失败：" + e.getMessage());
                    updateForeground("推送青龙失败 · " + now());
                    scheduleNext();
                });
            }
        });
    }

    private QlInfo loadQlInfo() {
        String json = config.getString("qlJSON", null);
        if (json == null) {
            return null;
        }
        try {
            return JSON.parseObject(json, QlInfo.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void saveQlInfo(QlInfo qlInfo) {
        config.edit().putString("qlJSON", JSON.toJSONString(qlInfo)).apply();
    }

    // ---------------------------------------------------------------- 网络

    /**
     * 运营商号码认证必须走蜂窝网关，WiFi 下取不到号码。
     * 这里尽量把本进程的默认网络绑到移动数据，非蜂窝网络时尽力而为。
     */
    @SuppressWarnings("deprecation")
    private void bindCellular() {
        try {
            connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                return;
            }
            Network active = connectivityManager.getActiveNetwork();
            NetworkCapabilities nc = connectivityManager.getNetworkCapabilities(active);
            if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return;
            }
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cellularCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    try {
                        connectivityManager.bindProcessToNetwork(network);
                    } catch (Exception ignored) {
                    }
                }
            };
            connectivityManager.requestNetwork(request, cellularCallback);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("deprecation")
    private void releaseCellular() {
        try {
            if (connectivityManager != null && cellularCallback != null) {
                connectivityManager.unregisterNetworkCallback(cellularCallback);
            }
            if (connectivityManager != null) {
                connectivityManager.bindProcessToNetwork(null);
            }
        } catch (Exception ignored) {
        }
        cellularCallback = null;
        connectivityManager = null;
    }

    private boolean hasCellular() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- 隐藏 WebView

    private void ensureWebView() {
        if (webView != null) {
            return;
        }
        webView = new WebView(getApplicationContext());
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
        settings.setLoadWithOverviewMode(true);
        // 去掉 wv 标识，否则京东会判定为内嵌浏览器，不下发号码认证入口
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (!loggingIn) {
                    return;
                }
                if (url != null && url.contains("plogin.m.jd.com") && !filledThisLogin) {
                    filledThisLogin = true;
                    view.evaluateJavascript(JsUtil.fillLoginJs(selectedAccount()), null);
                }
                main.removeCallbacks(loginPoll);
                main.postDelayed(loginPoll, 800);
            }
        });
    }

    /**
     * 1 像素透明悬浮窗，让 WebView 有窗口可渲染、可执行 JS，但用户看不到
     */
    private void attachOverlay() {
        if (overlayAttached || webView == null) {
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            return;
        }
        try {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.START | Gravity.TOP;
            params.x = 0;
            params.y = 0;
            // 1% 透明度：用户看不见，但页面按正常手机尺寸渲染，运营商认证组件不会因为视口太小而消失
            params.alpha = 0.01f;
            windowManager.addView(webView, params);
            overlayAttached = true;
        } catch (Exception ignored) {
        }
    }

    private void removeOverlay() {
        if (overlayAttached && windowManager != null && webView != null) {
            try {
                windowManager.removeView(webView);
            } catch (Exception ignored) {
            }
        }
        overlayAttached = false;
    }

    // ---------------------------------------------------------------- 账号

    private String selectedAccount() {
        String selected = config.getString("selectedPhone", null);
        if (StringUtils.isNotBlank(selected)) {
            return selected;
        }
        String phones = config.getString("phoneStr", "");
        if (StringUtils.isBlank(phones)) {
            return "";
        }
        String[] lines = phones.split("\r\n");
        return lines.length > 0 ? lines[0] : "";
    }

    private String phoneOf(String account) {
        return account == null ? "" : account.split(" ")[0];
    }

    // ---------------------------------------------------------------- 通知

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        NotificationChannel fg = new NotificationChannel(CHANNEL_FG, "CK自动维护", NotificationManager.IMPORTANCE_LOW);
        NotificationChannel alert = new NotificationChannel(CHANNEL_ALERT, "CK自动维护提醒", NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(fg);
        nm.createNotificationChannel(alert);
    }

    private Notification buildNotification(boolean high, String title, String text) {
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        if (high) {
            intent.putExtra(MainActivity.EXTRA_AUTO_LOGIN, true);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this,
                high ? CHANNEL_ALERT : CHANNEL_FG)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(!high)
                .setAutoCancel(high)
                .setOngoing(!high)
                .setPriority(high ? NotificationCompat.PRIORITY_HIGH : NotificationCompat.PRIORITY_LOW);
        return builder.build();
    }

    private void updateForeground(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) {
            return;
        }
        String hint = hasCellular() ? "" : "（当前非移动数据，号码认证可能不可用）";
        nm.notify(NOTIFY_FG, buildNotification(false, "京东CK自动维护中", text + hint));
    }

    private void alert(String title, String text) {
        // 同一条提醒只提醒一次，避免反复打扰
        String key = title + text;
        if (key.equals(lastAlertKey)) {
            return;
        }
        lastAlertKey = key;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            nm.notify(NOTIFY_ALERT, buildNotification(true, title, text));
        }
    }

    private String now() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(new Date());
    }
}
