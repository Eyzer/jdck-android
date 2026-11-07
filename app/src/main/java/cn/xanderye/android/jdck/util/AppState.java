package cn.xanderye.android.jdck.util;

/**
 * 记录 App 是否在前台，后台自动登录在用户手动使用 App 时不插手
 */
public class AppState {

    public static volatile boolean foreground;
}
