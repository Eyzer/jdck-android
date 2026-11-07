package cn.xanderye.android.jdck.activity;

import android.app.AlertDialog;
import android.content.*;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.*;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import cn.xanderye.android.jdck.R;
import cn.xanderye.android.jdck.config.Config;
import cn.xanderye.android.jdck.entity.QlEnv;
import cn.xanderye.android.jdck.entity.QlInfo;
import cn.xanderye.android.jdck.receiver.SMSReceiver;
import cn.xanderye.android.jdck.util.AppState;
import cn.xanderye.android.jdck.util.CkSchedule;
import cn.xanderye.android.jdck.util.JDUtil;
import cn.xanderye.android.jdck.util.JsUtil;
import cn.xanderye.android.jdck.util.QinglongUtil;
import com.alibaba.fastjson.JSON;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class MainActivity extends AppCompatActivity {

    private Context context;

    private Button addBtn, delBtn, inputBtn, getCookieBtn, clearCookieBtn,sendCK, oneKeyBtn;

    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    private int oneKeyTries;

    /**
     * 运营商号码认证页的注入脚本统一放在 JsUtil，后台 Service 也用同一份
     */
    private Spinner phoneSpinner;

    private WebView webView;

    private static final String JD_URL = "https://home.m.jd.com/myJd/home.action";

    public static final String EXTRA_AUTO_LOGIN = "autoLogin";

    private static final int CK_HOURS = 3;

    private boolean ckCheckEnabled;

    private static final Pattern PHONE_PATTERN = Pattern.compile("1\\d{10}");

    private SharedPreferences config;

    private String cookie = null;

    private String lastFilledUrl = "";

    private Set<String> phoneSet = new HashSet<>();

    private Boolean smsEnabled;
    private SMSReceiver smsReceiver;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        context = this;

        // 配置存储
        config = getSharedPreferences("CONFIG", Context.MODE_PRIVATE);
        // 获取短信接收器配置
        smsEnabled = config.getBoolean("smsEnabled", false);
        if (smsEnabled) {
            // 注册接收器
            registerSMSReceiver();
        }

        webView = findViewById(R.id.webView);
        Config.getInstance().setWebView(webView);
        //支持javascript
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        // 去掉 wv 标识，否则京东会判定为内嵌浏览器，不下发号码认证入口
        webView.getSettings().setUserAgentString(webView.getSettings().getUserAgentString().replace("; wv", ""));
        //自适应屏幕
        webView.getSettings().setLoadWithOverviewMode(true);
        resetWebview();

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                //添加Cookie获取操作
                CookieManager cookieManager = CookieManager.getInstance();
                cookie = cookieManager.getCookie(url);
                autoFillLogin(url);
                startOneKeyLogin();
                uiHandler.postDelayed(MainActivity.this::autoUploadIfNeeded, 1500);
                super.onPageFinished(view, url);
            }
        });
        // 配置账号下拉框
        phoneSpinner = findViewById(R.id.phoneSpinner);
        String phoneStr = config.getString("phoneStr", null);
        if (phoneStr != null) {
            String[] phones = phoneStr.split("\r\n");
            phoneSet = new HashSet<>(Arrays.asList(phones));
            updatePhone();
        }
        // 记住选中的账号，后台自动登录用它取号码和密码
        phoneSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                Object selected = parent.getSelectedItem();
                if (selected != null) {
                    config.edit().putString("selectedPhone", selected.toString()).apply();
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        // 添加按钮
        addBtn = findViewById(R.id.addBtn);
        addBtn.setOnClickListener(v -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            final AlertDialog dialog = builder.create();
            View dialogView = View.inflate(context, R.layout.activity_phone, null);
            //设置对话框布局
            dialog.setView(dialogView);
            dialog.show();

            EditText phoneText = dialogView.findViewById(R.id.phoneText);
            Button confirmBtn = dialogView.findViewById(R.id.confirmBtn);
            confirmBtn.setOnClickListener(v2 -> {
                String phone = phoneText.getText().toString();
                if (StringUtils.isBlank(phone)) {
                    Toast.makeText(this, "账号输入错误", Toast.LENGTH_SHORT).show();
                    return;
                }
                dialog.cancel();
                phoneSet.add(phone);
                updatePhone();
                Toast.makeText(this, "添加成功", Toast.LENGTH_SHORT).show();
            });
        });
        // 删除按钮
        delBtn = findViewById(R.id.delBtn);
        delBtn.setOnClickListener(v -> {
            String selectedPhone = (String) phoneSpinner.getSelectedItem();
            if (selectedPhone == null) {
                Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show();
                return;
            }
            phoneSet = phoneSet.stream().filter(phone -> !phone.equals(selectedPhone)).collect(Collectors.toSet());
            // 更新账号
            updatePhone();
            Toast.makeText(this, "删除成功", Toast.LENGTH_SHORT).show();
        });

        // 一键输入按钮
        inputBtn = findViewById(R.id.inputBtn);
        inputBtn.setOnClickListener(v -> {
            String selectedPhone = (String) phoneSpinner.getSelectedItem();
            if (selectedPhone == null) {
                Toast.makeText(this, "请先选择账号", Toast.LENGTH_SHORT).show();
                return;
            }
            webView.loadUrl("javascript:" + buildInputJs(selectedPhone));
        });
        // 获取cookie按钮
        getCookieBtn = findViewById(R.id.getCookieBtn);
        getCookieBtn.setOnClickListener(v -> {
            Map<String, Object> map = JDUtil.formatCookies(cookie);
            String ptKey = (String) map.get("pt_key");
            String ptPin = (String) map.get("pt_pin");
            if (StringUtils.isAnyBlank(ptKey, ptPin)) {
                Toast.makeText(this, "未获取到Cookie，请先登录", Toast.LENGTH_SHORT).show();
                return;
            }
            String selectedPhone = (String) phoneSpinner.getSelectedItem();
            String Phone="";
            if (selectedPhone != null) {
                String[] info=  selectedPhone.split(" ");
                Phone=info[0];
            }

            String cookie = MessageFormat.format("pt_key={0};pt_pin={1};", ptKey, ptPin);
            QlInfo qlInfo = Config.getInstance().getQlInfo();
            copyToClipboard(cookie);
            if (qlInfo == null || qlInfo.getToken() == null) {
                Toast.makeText(this, "获取成功，已复制到剪切板", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "获取成功，已复制到剪切板，尝试自动更新青龙面板环境变量", Toast.LENGTH_SHORT).show();
                updateCookie(cookie,Phone);
            }
        });
        // 重置cookie刷新页面按钮
        clearCookieBtn = findViewById(R.id.clearCookieBtn);
        clearCookieBtn.setOnClickListener(v -> {
//            updateCookie("pt_key=test;pt_pin=Yclown;");
            resetWebview();
        });

        // 一键登录按钮
        oneKeyBtn = findViewById(R.id.oneKeyBtn);
        oneKeyBtn.setOnClickListener(v -> {
            if (!isCellular()) {
                Toast.makeText(this, "号码认证依赖运营商网关，请关闭WIFI使用移动数据", Toast.LENGTH_LONG).show();
                return;
            }
            webView.evaluateJavascript(oneKeyJs(), value ->
                    Toast.makeText(this, "\"ok\"".equals(value) ? "已触发本机号码认证" : "当前页面无号码认证入口", Toast.LENGTH_SHORT).show());
        });

        ckCheckEnabled = config.getBoolean("ckCheck", false);
        if (ckCheckEnabled) {
            // 服务已在跑时不会重复检测，进程被杀后由这里恢复
            CkSchedule.startService(this);
        }

        // 检查token有效 同时获取环境变量
        checkQlLogin();
        // 定时任务判定失效后拉起，直接重载页面走登录
        if (getIntent().getBooleanExtra(EXTRA_AUTO_LOGIN, false)) {
            getIntent().removeExtra(EXTRA_AUTO_LOGIN);
            resetWebview();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (intent.getBooleanExtra(EXTRA_AUTO_LOGIN, false)) {
            intent.removeExtra(EXTRA_AUTO_LOGIN);
            resetWebview();
        }
    }

    @Override
    protected void onResume() {
        AppState.foreground = true;
        super.onResume();
    }

    @Override
    protected void onPause() {
        AppState.foreground = false;
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        unregisterSMSReceiver();
        uiHandler.removeCallbacks(oneKeyTask);
        super.onDestroy();
    }

    /**
     * 登录成功拿到新cookie后自动上传青龙，同一个pt_key只传一次
     */
    private void autoUploadIfNeeded() {
        Map<String, Object> map = JDUtil.formatCookies(cookie);
        String ptKey = (String) map.get("pt_key");
        String ptPin = (String) map.get("pt_pin");
        if (StringUtils.isAnyBlank(ptKey, ptPin) || ptKey.equals(config.getString("lastUploadedKey", ""))) {
            return;
        }
        config.edit().putString("lastUploadedKey", ptKey).apply();
        QlInfo qlInfo = Config.getInstance().getQlInfo();
        if (qlInfo == null || qlInfo.getToken() == null) {
            return;
        }
        String selected = (String) phoneSpinner.getSelectedItem();
        String phone = selected == null ? "" : selected.split(" ")[0];
        updateCookie("pt_key=" + ptKey + ";pt_pin=" + ptPin + ";", phone);
    }

    /**
     * 登录页填号脚本，账号列表里选中的号码自动补全到输入框
     */
    private String buildInputJs(String selected) {
        return JsUtil.fillLoginJs(selected);
    }

    /**
     * 登录页加载后自动补全选中的手机号，同一地址只填一次，避免盖掉手动输入
     */
    private void autoFillLogin(String url) {
        if (url == null || url.equals(lastFilledUrl) || !url.contains("plogin.m.jd.com")) {
            return;
        }
        String selected = (String) phoneSpinner.getSelectedItem();
        if (StringUtils.isBlank(selected)) {
            return;
        }
        lastFilledUrl = url;
        // 等页面脚本渲染出输入框再填
        uiHandler.postDelayed(() -> webView.evaluateJavascript(buildInputJs(selected), null), 500);
    }

    /**
     * 一键登录脚本带上选中的号码，用于补全页面上的掩码输入格
     */
    private String oneKeyJs() {
        String selected = (String) phoneSpinner.getSelectedItem();
        String phone = selected == null ? "" : selected.split(" ")[0];
        return JsUtil.oneKeyJs(phone);
    }

    /**
     * 是否走蜂窝网络，号码认证只在移动数据下取得到号码
     */
    private boolean isCellular() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR);
    }

    /**
     * 页面加载后轮询等待号码认证组件渲染，命中一次即停
     */
    private void startOneKeyLogin() {
        uiHandler.removeCallbacks(oneKeyTask);
        if (!isCellular()) {
            return;
        }
        oneKeyTries = 0;
        uiHandler.postDelayed(oneKeyTask, 800);
    }

    private final Runnable oneKeyTask = new Runnable() {
        @Override
        public void run() {
            if (oneKeyTries++ >= 8) {
                return;
            }
            webView.evaluateJavascript(oneKeyJs(), value -> {
                if (!"\"ok\"".equals(value)) {
                    uiHandler.postDelayed(oneKeyTask, 1000);
                }
            });
        }
    };

    public boolean onCreateOptionsMenu(Menu menu) {
        String text = smsEnabled ? "关闭短信识别" : "开启短信识别";
        menu.add(Menu.NONE, 1, 1, "青龙面板");
        menu.add(Menu.NONE, 2, 2, text);
        menu.add(Menu.NONE, 3, 3, "关于");
        menu.add(Menu.NONE, 4, 4, item4Text());
        return true;
    }

    private String item4Text() {
        return ckCheckEnabled
                ? "关闭后台自动登录（每" + CK_HOURS + "小时检测CK）"
                : "开启后台自动登录（每" + CK_HOURS + "小时检测CK）";
    }

    /**
     * 后台自动登录开关：前台服务里静默加载登录页，不走 startActivity，所以后台也能跑
     */
    private void toggleAutoLogin() {
        ckCheckEnabled = !ckCheckEnabled;
        config.edit().putBoolean("ckCheck", ckCheckEnabled).apply();
        if (ckCheckEnabled) {
            CkSchedule.enable(this, CK_HOURS);
        } else {
            CkSchedule.disable(this);
        }
        invalidateOptionsMenu();
        if (ckCheckEnabled) {
            Toast.makeText(this, "已开启：每" + CK_HOURS + "小时检测一次，CK失效后在后台自动登录并推送青龙。"
                    + "请同时允许自启动、关闭电池优化。", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "已关闭后台自动登录", Toast.LENGTH_LONG).show();
        }
    }

    public boolean onOptionsItemSelected(MenuItem item){
        switch (item.getItemId()){
            case 1: {
                Intent intent = new Intent(MainActivity.this, LoginActivity.class);
                startActivity(intent);
            } break;
            case 2: {
                String toastText;
                if (smsEnabled) {
                    toastText = "关闭成功，请重启应用生效";
                } else {
                    String[] permissions = new String[]{"android.permission.RECEIVE_SMS", "android.permission.READ_SMS"};
                    boolean result = checkPermissionAllGranted(permissions);
                    if (!result) {
                        AlertDialog.Builder builder = new AlertDialog.Builder(context);
                        builder.setMessage("未获取到短信读取权限，是否跳转设置页面并开启权限（MIUI需要额外开启通知类短信权限）？");
                        builder.setPositiveButton("设置", (dialog, which) -> {
                            openSettings();
                        });
                        builder.create().show();
                        break;
                    }
                    toastText = "开启成功，请重启应用生效";
                }
                Toast.makeText(this, toastText, Toast.LENGTH_LONG).show();
                smsEnabled = !smsEnabled;
                String text = smsEnabled ? "关闭短信识别" : "开启短信识别";
                item.setTitle(text);
                SharedPreferences.Editor edit = config.edit();
                edit.putBoolean("smsEnabled", smsEnabled);
                edit.apply();
            } break;
            case 3: {
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setMessage("@XanderYe 版权所有 @yclown修改");
                builder.setPositiveButton("项目页面", (dialog, which) -> {
                    Uri uri = Uri.parse("https://github.com/yclown/jdck-android");
                    Intent intent = new Intent();
                    intent.setAction("android.intent.action.VIEW");
                    intent.setData(uri);
                    startActivity(intent);
                });
                builder.create().show();
            }
            break;
            case 4: {
                if (!ckCheckEnabled && !Settings.canDrawOverlays(this)) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(context);
                    builder.setTitle("需要悬浮窗权限");
                    builder.setMessage("后台自动登录会在你看不到的小悬浮窗里加载京东登录页。"
                            + "不给权限也可能成功，但概率低很多。授权后请再点一次菜单开启。");
                    builder.setPositiveButton("去授权", (dialog, which) ->
                            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()))));
                    builder.setNegativeButton("仍然开启", (dialog, which) -> toggleAutoLogin());
                    builder.create().show();
                    break;
                }
                toggleAutoLogin();
            }
            break;
        }
        return true;
    }

    /**
     * 检查权限
     * @param permissions
     * @return boolean
     * @author XanderYe
     * @date 2022/12/30
     */
    private boolean checkPermissionAllGranted(String[] permissions) {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    /**
     * 打开设置
     * @param
     * @return void
     * @author XanderYe
     * @date 2022/12/30
     */
    private void openSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    /**
     * 注册短信接收器
     * @param
     * @return void
     * @author XanderYe
     * @date 2022/12/30
     */
    private void registerSMSReceiver() {
        smsReceiver = new SMSReceiver();
        IntentFilter filter = new IntentFilter();
        filter.setPriority(1000);
        filter.addAction(SMSReceiver.SMS_RECEIVED);
        registerReceiver(smsReceiver, filter);
    }

    /**
     * 取消注册短信接收器
     * @param
     * @return void
     * @author XanderYe
     * @date 2022/12/30
     */
    private void unregisterSMSReceiver() {
        if (smsReceiver != null) {
            unregisterReceiver(smsReceiver);
            smsReceiver = null;
        }
    }

    /**
     * 清空cookie并加载页面
     * @param
     * @return void
     * @author XanderYe
     * @date 2022/5/10
     */
    private void resetWebview() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.removeAllCookies(null);
        cookieManager.flush();
        webView.clearCache(true);
        webView.loadUrl(JD_URL);
    }

    /**
     * 更新下拉和存储中的账号
     * @return void
     * @author XanderYe
     * @date 2022/5/10
     */
    private void updatePhone() {
        // 更新账号
        String newPhoneStr = phoneSet.stream().collect(Collectors.joining("\r\n"));
        SharedPreferences.Editor edit = config.edit();
        edit.putString("phoneStr", newPhoneStr);
        edit.apply();
        String[] phones = phoneSet.toArray(new String[0]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, phones);
        phoneSpinner.setAdapter(adapter);
    }

    /**
     * 复制文字到剪切板
     * @param copyStr
     * @return boolean
     * @author XanderYe
     * @date 2022/5/10
     */
    private boolean copyToClipboard(String copyStr) {
        try {
            //获取剪贴板管理器
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            // 创建普通字符型ClipData
            ClipData mClipData = ClipData.newPlainText("Label", copyStr);
            // 将ClipData内容放到系统剪贴板里。
            cm.setPrimaryClip(mClipData);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void checkQlLogin() {
        String qlJSON = config.getString("qlJSON", null);
        if (qlJSON == null) {
            return;
        }
        QlInfo qlInfo = JSON.parseObject(qlJSON, QlInfo.class);
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        singleThreadExecutor.execute(() -> {
            Looper.prepare();


            try {
                List<QlEnv> qlEnvList = QinglongUtil.getEnvList(qlInfo,"");
                Config.getInstance().setQlEnvList(qlEnvList);
                Config.getInstance().setQlInfo(qlInfo);
                Toast.makeText(this, "青龙token有效", Toast.LENGTH_SHORT).show();
            } catch (IOException e) {
                Toast.makeText(this, "青龙token已失效，请重新登录", Toast.LENGTH_SHORT).show();
            }
            Looper.loop();
        });
        singleThreadExecutor.shutdown();
    }

    /**
     * 调用青龙接口更新cookie
     * @param cookie
     * @return void
     * @author XanderYe
     * @date 2022/5/11
     */
    private void updateCookie(String cookie,String phone) {
        Map<String, Object> map = JDUtil.formatCookies(cookie);
        String ptPin = (String) map.get("pt_pin");
        QlInfo qlInfo = Config.getInstance().getQlInfo();
        List<QlEnv> qlEnvList =Config.getInstance().getQlEnvList();
        if(qlEnvList==null){
            qlEnvList=new ArrayList<QlEnv>();
        }
        QlEnv targetEnv = null;
        for (QlEnv qlEnv : qlEnvList) {
            Map<String, Object> envMap = JDUtil.formatCookies(qlEnv.getValue());
            String tempPin = (String) envMap.get("pt_pin");
            if(ptPin.equals(tempPin)) {
                targetEnv = qlEnv;
                break;
            }
        }
        if (targetEnv == null) {
            targetEnv = new QlEnv();
            targetEnv.setName("JD_COOKIE");
        }
        targetEnv.setValue(cookie);
        targetEnv.setRemarks(phone);
        ExecutorService singleThreadExecutor = Executors.newSingleThreadExecutor();
        QlEnv finalTargetEnv = targetEnv;
        singleThreadExecutor.execute(() -> {
            Looper.prepare();
            try {
                boolean success = QinglongUtil.saveEnv(qlInfo, finalTargetEnv);
                QinglongUtil.EableEnv(qlInfo,finalTargetEnv);
                if (success) {
                    Toast.makeText(this, "更新cookie成功", Toast.LENGTH_SHORT).show();
                }
            } catch (IOException e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
            Looper.loop();
        });
        singleThreadExecutor.shutdown();

    }
}