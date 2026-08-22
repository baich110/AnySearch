# ============================================================
# AnySearch APP - ProGuard / R8 混淆规则
# 说明：R8 已内置在 AGP 中，此文件用于"保留"那些不能被混淆的类
# ============================================================

# ---------- 通用保留 ----------
# 保留源码行号（便于崩溃日志定位），去掉后可更小但难排查
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ---------- ARM 环境稳定性优化 ----------
# 只做"混淆 + 移除无用代码"，跳过深度的字节码优化阶段。
# 原因：本机为 ARM 架构，R8 的深度优化（尤其 Compose 相关）在 ARM JVM 上
# 可能卡死或极慢。跳过优化可大幅降低卡死概率，同时混淆保护仍然有效。
-dontoptimize
# 限制 R8 单次处理的方法数，避免 ARM 上内存/栈溢出
-optimizationpasses 1

# ---------- kotlinx.serialization（必须保留，否则反序列化崩溃）----------
# 保留所有 @Serializable 数据类的类名和字段名
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

# 保留序列化生成的 companion 及 serializer
-keepclassmembers class com.anysearch.app.** {
    *** Companion;
}
-keepclasseswithmembers class com.anysearch.app.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# 保留所有使用 kotlinx.serialization 注解的类
-keep @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}

# ---------- 你的业务数据模型（位于 data 包，含 @Serializable）----------
-keep class com.anysearch.app.data.** { *; }

# ---------- OkHttp（网络库）----------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
# OkHttp 内部通过反射加载平台实现，保留以下
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keepclassmembers class okhttp3.internal.platform.** {
    *;
}

# ---------- kotlinx.coroutines ----------
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** {
    *;
}

# ---------- Coil 图片加载 ----------
-dontwarn coil.**
-keep class coil.** { *; }

# ---------- Compose（通常 R8 自动处理，但保险起见保留）----------
-dontwarn androidx.compose.**
-keep class androidx.compose.** { *; }

# ---------- Markdown renderer ----------
-dontwarn com.mikepenz.**
-keep class com.mikepenz.** { *; }

# ---------- WebView / Webkit ----------
-keep class androidx.webkit.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------- DataStore ----------
-dontwarn androidx.datastore.**
-keep class androidx.datastore.** { *; }

# ---------- 保留入口 Activity（清单里声明的组件）----------
-keep class com.anysearch.app.MainActivity { *; }

# ---------- 通用：保留所有 Android 组件入口（避免清单引用失效）----------
-keep public class * extends android.app.Activity { *; }
-keep public class * extends android.app.Application { *; }
-keep public class * extends android.app.Service { *; }
-keep public class * extends android.content.BroadcastReceiver { *; }
-keep public class * extends android.content.ContentProvider { *; }
-keep public class * extends android.view.View { *; }

# ---------- 枚举 ----------
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# ---------- Kotlin 伴生对象 / 默认方法 ----------
-keepclassmembers class **$Companion { *; }
-keepclassmembers class * {
    *** Companion;
}
