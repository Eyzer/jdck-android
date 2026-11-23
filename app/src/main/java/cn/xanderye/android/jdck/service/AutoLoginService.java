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
import android.os.PowerManager;
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
import cn.xanderye.android.jdck.util.LogUtil;
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
 * <p>
 * 内存策略：WebView 只在真正需要登录时才创建，登录结束立刻销毁。
 * 检测 CK 只读 CookieManager，不再为了检测白开一个 Chromium 实例。
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
    private static final long POLL_INTERVAL_MS = 3000L;

    private static final String JD_URL = CkUtil.JD_URL;

    /**
     * 隐藏 WebView 不加载图片，省掉几十兆图片解码内存。
     * 若某项运营商号码认证组件变成空白导致登录失败，把这里改成 false 再试。
     */
    private static final boolean BLOCK_IMAGES = true;

    /**
     * 悬浮窗高度占屏幕的比例。页面按正常宽度渲染，高度给够即可，不必全屏。
     */
    private static final float OVERLAY_HEIGHT_RATIO = 0.6f;

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

    /**
     * WebView 内核是否已被唤醒过。没唤醒过时读不到磁盘上的 Cookie。
     */
    private boolean cookieEngineWarmed;

    private String lastPageUrl = "";

    private String lastPageState = "";

    private String lastAlertKey = "";

    private ConnectivityManager connectivityManager;

    private ConnectivityManager.NetworkCallback cellularCallback;

    @Override
    public void onCreate() {
        super.onCreate();
        LogUtil.init(this);
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
        LogUtil.i("服务启动 action=" + (intent == null ? "null(系统重启)" : intent.getAction())
                + " 状态 checking=" + checking + " loggingIn=" + loggingIn);
        logEnvironment();
        // 先转前台，避免 startForegroundService 后 5 秒内没转前台被系统判定超时
        startForeground(NOTIFY_FG, buildNotification(false, "京东CK自动维护中", "正在检测京东CK…"));
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            LogUtil.i("收到停止指令");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!CkSchedule.isEnabled(this)) {
            LogUtil.i("后台自动登录开关未开启，服务退出");
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!checking && !loggingIn) {
            runCheck();
        } else {
            LogUtil.i("已有任务进行中，跳过本次触发");
        }
        // 不再用 START_STICKY：让系统随时可以回收本进程，下一次由定时闹钟拉起。
        // 原来的 STICKY 会让进程被杀了又立刻重建，内存底座长期挂着不降。
        return START_NOT_STICKY;
    }

    /**
     * 记录当前环境，失败复盘时第一时间看这里
     */
    private void logEnvironment() {
        boolean overlay = Settings.canDrawOverlays(this);
        boolean ignoreBattery = false;
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            ignoreBattery = pm != null && pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
        }
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        boolean notify = nm == null || nm.areNotificationsEnabled();
        String account = selectedAccount();
        LogUtil.i("环境: 网络=" + networkType()
                + " 悬浮窗=" + overlay
                + " 通知=" + notify
                + " 忽略电池优化=" + ignoreBattery
                + " 后台开关=" + CkSchedule.isEnabled(this)
                + " 账号=" + (StringUtils.isBlank(account) ? "未配置" : maskPhone(phoneOf(account)))
                + " 间隔=" + CkSchedule.hours(this) + "h");
    }

    private String networkType() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            if (nc == null) {
                return "NONE";
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return "CELLULAR";
            }
            if (nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                return "WIFI";
            }
            return "OTHER";
        } catch (Exception e) {
            return "ERR:" + e.getMessage();
        }
    }

    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    public static final String ACTION_STOP = "cn.xanderye.android.jdck.STOP";

    @Override
    public void onDestroy() {
        LogUtil.i("服务销毁");
        main.removeCallbacksAndMessages(null);
        releaseCellular();
        destroyWebView();
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------------------------------------------------------------- 检测

    private void runCheck() {
        checking = true;
        updateForeground("正在检测京东CK…");
        LogUtil.i("开始检测CK（只读 Cookie，不创建 WebView）");
        // 纯读 Cookie + HTTP 校验，不需要 WebView，避免为了检测白开一个 Chromium 实例
        main.postDelayed(this::doCheck, 300);
    }

    private void doCheck() {
        String cookie = snapshotCookie();
        Map<String, Object> cookieMap = JDUtil.formatCookies(cookie);
        LogUtil.i("读取Cookie: 长度=" + cookie.length()
                + " pt_key=" + LogUtil.mask((String) cookieMap.get("pt_key"))
                + " pt_pin=" + LogUtil.mask((String) cookieMap.get("pt_pin")));
        io.execute(() -> {
            boolean valid = CkUtil.isValid(cookie);
            LogUtil.i("CK校验结果 valid=" + valid);
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
                    LogUtil.i("CK失效，但App在前台，30秒后再尝试");
                    updateForeground("CK已失效，退到后台后自动登录…");
                    main.postDelayed(() -> {
                        if (!loggingIn && !checking && !AppState.foreground) {
                            startLogin();
                        } else {
                            scheduleNext();
                        }
                    }, 30000);
                } else {
                    LogUtil.i("CK失效，开始后台自动登录");
                    startLogin();
                }
            });
        });
    }

    /**
     * 优先取 WebView 里的 Cookie，取不到时用上次校验通过的快照
     */
    private String snapshotCookie() {
        String cookie = readCookieSafe();
        Map<String, Object> map = JDUtil.formatCookies(cookie);
        if (StringUtils.isBlank((String) map.get("pt_key"))) {
            String saved = config.getString(PK_LAST_COOKIE, "");
            if (StringUtils.isNotBlank(saved)) {
                return saved;
            }
        }
        return cookie == null ? "" : cookie;
    }

    /**
     * 读 Cookie 不依赖 WebView 实例。只有一种情况需要唤醒内核：
     * 本地存过 Cookie 但内核还没初始化过（冷启动首次），此时磁盘上的 Cookie 读不出来，
     * 不唤醒就会误判失效、白跑一次登录。
     */
    private String readCookieSafe() {
        String cookie = "";
        try {
            cookie = CookieManager.getInstance().getCookie(JD_URL);
        } catch (Exception e) {
            LogUtil.e("读取Cookie失败", e);
        }
        if (StringUtils.isBlank(cookie) && !cookieEngineWarmed
                && StringUtils.isNotBlank(config.getString(PK_LAST_COOKIE, ""))) {
            cookieEngineWarmed = true;
            WebView probe = null;
            try {
                probe = new WebView(getApplicationContext());
                cookie = CookieManager.getInstance().getCookie(JD_URL);
                LogUtil.i("已唤醒 WebView 内核读取磁盘 Cookie");
            } catch (Throwable t) {
                LogUtil.e("唤醒 WebView 内核失败", t);
            } finally {
                if (probe != null) {
                    try {
                        probe.destroy();
                    } catch (Throwable ignored) {
                    }
                }
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
        cookieEngineWarmed = true;
        filledThisLogin = false;
        oneKeyFired = false;
        lastPageUrl = "";
        lastPageState = "";
        loginDeadline = System.currentTimeMillis() + LOGIN_TIMEOUT_MS;

        ensureWebView();
        boolean overlay = attachOverlay();
        bindCellular();
        LogUtil.i("登录准备: 悬浮窗=" + (overlay ? "已添加" : "添加失败/无权限")
                + " 网络=" + networkType()
                + " 号码=" + maskPhone(phoneOf(selectedAccount())));
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
            LogUtil.i("已清空Cookie，加载登录页 " + JD_URL);
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
                if (!loggingIn || webView == null) {
                    return;
                }
                String url = webView.getUrl();
                if (!String.valueOf(url).equals(lastPageUrl) || !String.valueOf(state).equals(lastPageState)) {
                    lastPageUrl = String.valueOf(url);
                    lastPageState = String.valueOf(state);
                    LogUtil.i("页面变化 url=" + url + " state=" + state);
                }
                if ("\"captcha\"".equals(state)) {
                    giveUp("京东要求安全验证，需要手动处理");
                    return;
                }
                if (!filledThisLogin && url != null && url.contains("plogin.m.jd.com")) {
                    filledThisLogin = true;
                    LogUtil.i("进入登录页，填充账号");
                    webView.evaluateJavascript(JsUtil.fillLoginJs(account), null);
                }
                // JS 内部有 3 秒节流，这里持续重试即可
                webView.evaluateJavascript(JsUtil.oneKeyJs(phoneOf(account)), value -> {
                    if ("\"ok\"".equals(value)) {
                        if (!oneKeyFired) {
                            LogUtil.i("已触发运营商本机号码认证");
                        }
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
        LogUtil.i("登录成功 pt_key=" + LogUtil.mask(ptKey) + " pt_pin=" + LogUtil.mask(ptPin)
                + " cookie长度=" + cookie.length());
        config.edit().putString(PK_LAST_COOKIE, cookie).apply();
        releaseCellular();
        // 拿到 CK 就把页面和内嵌实例全放掉，后台不用再养着一个浏览器
        destroyWebView();
        updateForeground("登录成功，正在写入青龙…");
        upload(cookie, ptPin, ptKey);
    }

    private void giveUp(String reason) {
        loggingIn = false;
        main.removeCallbacks(loginPoll);
        main.removeCallbacks(loginTimeout);
        LogUtil.e("自动登录失败: " + reason
                + " | 当前URL=" + (webView == null ? "null" : webView.getUrl())
                + " | 网络=" + networkType()
                + " | 已触发一键登录=" + oneKeyFired
                + " | 已填号=" + filledThisLogin);
        releaseCellular();
        destroyWebView();
        alert("自动登录未完成", reason);
        updateForeground("自动登录失败 · " + now());
        scheduleNext();
    }

    // ---------------------------------------------------------------- 上传青龙

    private void upload(String cookie, String ptPin, String ptKey) {
        io.execute(() -> {
            QlInfo qlInfo = loadQlInfo();
            if (qlInfo == null || StringUtils.isBlank(qlInfo.getToken())) {
                LogUtil.e("未配置青龙面板或 token 为空，跳过推送");
                main.post(() -> {
                    alert("京东CK已更新，但未配置青龙面板", "请打开 App 配置青龙面板地址与密钥");
                    updateForeground("登录成功，未配置青龙 · " + now());
                    scheduleNext();
                });
                return;
            }
            LogUtil.i("开始推送青龙: 地址=" + qlInfo.getAddress() + " pt_pin=" + LogUtil.mask(ptPin));
            try {
                List<QlEnv> envList;
                try {
                    envList = QinglongUtil.getEnvList(qlInfo, "");
                } catch (IOException first) {
                    // 青龙 token 过期时用 client_id/client_secret 重新换一个
                    LogUtil.i("青龙读取环境变量失败，尝试重新登录: " + first.getMessage());
                    qlInfo.setToken(QinglongUtil.login(qlInfo));
                    saveQlInfo(qlInfo);
                    LogUtil.i("青龙重新登录成功，token=" + LogUtil.mask(qlInfo.getToken()));
                    envList = QinglongUtil.getEnvList(qlInfo, "");
                }
                LogUtil.i("青龙环境变量数量=" + envList.size());
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
                    LogUtil.i("青龙中没有该账号，新建环境变量");
                } else {
                    LogUtil.i("命中已有环境变量 name=" + target.getName());
                }
                target.setValue("pt_key=" + ptKey + ";pt_pin=" + ptPin + ";");
                target.setRemarks(phoneOf(selectedAccount()));
                QinglongUtil.saveEnv(qlInfo, target);
                config.edit().putString(PK_LAST_UPLOADED, ptKey).apply();
                LogUtil.i("青龙环境变量写入成功");

                // 新建的环境变量需要再查一次拿到 id 才能启用
                try {
                    for (QlEnv env : QinglongUtil.getEnvList(qlInfo, "")) {
                        Map<String, Object> envMap = JDUtil.formatCookies(env.getValue());
                        if (ptPin.equals(envMap.get("pt_pin"))) {
                            QinglongUtil.EableEnv(qlInfo, env);
                            LogUtil.i("青龙环境变量已启用");
                            break;
                        }
                    }
                } catch (IOException e) {
                    LogUtil.e("青龙环境变量启用失败", e);
                }

                main.post(() -> {
                    updateForeground("CK已自动更新到青龙 · " + now());
                    alert("京东CK已自动更新", "已写入青龙面板，无需手动操作");
                    scheduleNext();
                });
            } catch (IOException e) {
                LogUtil.e("推送青龙失败: " + e.getMessage(), e);
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
                LogUtil.i("当前已是移动数据，无需切换");
                return;
            }
            LogUtil.i("当前非移动数据(" + networkType() + ")，请求绑定蜂窝网络");
            NetworkRequest request = new NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cellularCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(@NonNull Network network) {
                    try {
                        connectivityManager.bindProcessToNetwork(network);
                        LogUtil.i("已绑定蜂窝网络，网络=" + networkType());
                    } catch (Exception e) {
                        LogUtil.e("绑定蜂窝网络失败", e);
                    }
                }
            };
            connectivityManager.requestNetwork(request, cellularCallback);
        } catch (Exception e) {
            LogUtil.e("请求蜂窝网络失败", e);
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
        // 隐藏窗口没人看，图片一律不加载。京东登录页图片很多，这一项省内存最明显
        if (BLOCK_IMAGES) {
            settings.setLoadsImagesAutomatically(false);
            settings.setBlockNetworkImage(true);
        }
        // 不预栅格化离屏内容，减少页面 tile 缓存
        settings.setOffscreenPreRaster(false);
        settings.setSupportZoom(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        // 去掉 wv 标识，否则京东会判定为内嵌浏览器，不下发号码认证入口
        settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                LogUtil.i("页面开始加载 " + url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!loggingIn) {
                    return;
                }
                LogUtil.i("页面加载完成 " + url);
                if (url != null && url.contains("plogin.m.jd.com") && !filledThisLogin) {
                    filledThisLogin = true;
                    view.evaluateJavascript(JsUtil.fillLoginJs(selectedAccount()), null);
                }
                main.removeCallbacks(loginPoll);
                main.postDelayed(loginPoll, 800);
            }

            @Override
            public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
                LogUtil.e("页面加载失败 code=" + errorCode + " desc=" + description + " url=" + failingUrl);
            }
        });
        webView.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage msg) {
                LogUtil.i("JS[" + msg.messageLevel() + "] " + msg.message()
                        + " @" + msg.sourceId() + ":" + msg.lineNumber());
                return true;
            }
        });
    }

    /**
     * 彻底释放 WebView：先摘掉悬浮窗，再停加载、清历史、销毁实例。
     * 这一步能把页面 DOM、CSSOM、JS 堆、图片解码全部还给系统。
     */
    private void destroyWebView() {
        removeOverlay();
        WebView victim = webView;
        webView = null;
        if (victim == null) {
            return;
        }
        try {
            victim.stopLoading();
            victim.clearHistory();
            victim.removeAllViews();
            victim.destroy();
            LogUtil.i("WebView 已销毁，页面内存已释放");
        } catch (Throwable t) {
            LogUtil.e("销毁 WebView 失败", t);
        }
    }

    /**
     * 半透明悬浮窗，让 WebView 有窗口可渲染、可执行 JS，但用户看不到。
     * 只占屏幕上半部分，宽度保持全屏以保证页面按手机版排版，运营商认证组件不会变形。
     */
    private boolean attachOverlay() {
        if (overlayAttached) {
            return true;
        }
        if (webView == null) {
            return false;
        }
        if (!Settings.canDrawOverlays(this)) {
            LogUtil.e("缺少悬浮窗权限，WebView 无法渲染，自动登录大概率失败");
            return false;
        }
        try {
            windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            int width = getResources().getDisplayMetrics().widthPixels;
            int height = Math.max((int) (getResources().getDisplayMetrics().heightPixels
                    * OVERLAY_HEIGHT_RATIO), 800);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    width,
                    height,
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
        } catch (Exception e) {
            LogUtil.e("悬浮窗添加失败", e);
        }
        return overlayAttached;
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
