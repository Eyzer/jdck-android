package cn.xanderye.android.jdck.util;

import android.webkit.CookieManager;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Cookie 读取与有效性校验
 */
public class CkUtil {

    public static final String JD_URL = "https://home.m.jd.com/myJd/home.action";

    /**
     * 取 WebView 里当前的京东 cookie
     */
    public static String currentCookie() {
        return CookieManager.getInstance().getCookie(JD_URL);
    }

    /**
     * 请求京东用户信息接口判断登录态，网络异常按有效处理，避免误触发重新登录
     */
    public static boolean isValid(String cookie) {
        Map<String, Object> ck = JDUtil.formatCookies(cookie);
        if (ck.get("pt_key") == null || ck.get("pt_pin") == null) {
            return false;
        }
        Map<String, Object> headers = new HashMap<>(4);
        headers.put("Referer", "https://home.m.jd.com/");
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/110.0.0.0 Mobile Safari/537.36");
        try {
            HttpUtil.ResEntity res = HttpUtil.doGet("https://me-api.jd.com/user_new/info/GetJDUserInfoUnion", headers, ck, null);
            String body = res.getResponse();
            // 拿不到响应体时不判失效，防止接口变动导致反复拉起登录
            return body == null || body.trim().isEmpty() || body.contains("\"retcode\":\"0\"");
        } catch (IOException e) {
            return true;
        }
    }
}
