# 数据实体走 fastjson 反射反序列化，字段名和 setter 不能改，必须整包保留
-keep class cn.xanderye.android.jdck.entity.** { *; }
-keepclassmembers class cn.xanderye.android.jdck.entity.** { *; }

# fastjson 内部大量反射拿泛型和注解
-keep class com.alibaba.fastjson.** { *; }
-dontwarn com.alibaba.fastjson.**
-keepclassmembers class * {
    @com.alibaba.fastjson.annotation.* <fields>;
    @com.alibaba.fastjson.annotation.* <methods>;
}

# Apache HttpClient（cz.msebera 版本）靠反射挑 SSL 实现，不能裁剪
-keep class org.apache.http.** { *; }
-dontwarn org.apache.http.**
-keep class cz.msebera.android.** { *; }
-dontwarn cz.msebera.android.**

# commons-lang3 保留，避免 StringUtils 之类被内联出问题
-keep class org.apache.commons.lang3.** { *; }
-dontwarn org.apache.commons.lang3.**

# WorkManager 的 Worker 由系统按类名实例化，必须保留构造函数
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
-keep class * extends androidx.work.InputMerger { *; }

# 组件类由 Manifest 引用，保留类名即可，成员允许混淆
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver

# 保留必要属性，否则泛型和注解反射会失败
-keepattributes Signature
-keepattributes Annotation
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod

# 关掉混淆后的日志噪音，同时让堆栈还能看
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# AppCompat / Material 自带的规则已经包含在依赖里，这里不再重复
