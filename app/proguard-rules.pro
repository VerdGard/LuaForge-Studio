# 保留所有 R 类及其内部类，避免资源引用混淆
-keep class **.R { *; }
-keep class **.R$* { *; }

# 保留指定包下的所有类（含子包）
-keep class android.widget.** { *; }
-keep class com.android.** { *; }
-keep class com.androlua.** { *; }
-keep class com.luajava.** { *; }
-keep class com.myopicmobile.** { *; }
-keep class com.nirenr.** { *; }
-keep class github.daisukiKaffuChino.** { *; }
-keep class org.luaj.** { *; }

# 保留 AndroidX 基础组件（排除 Compose 相关类）
-keep class androidx.activity.** { *; }
-dontwarn androidx.activity.compose.**
-keep class androidx.appcompat.** { *; }
-keep class androidx.annotation.** { *; }
-keep class androidx.collection.** { *; }
-keep class androidx.constraintlayout.** { *; }
-keep class androidx.coordinatorlayout.** { *; }
-keep class androidx.core.** { *; }
-keep class androidx.customview.** { *; }
-keep class androidx.documentfile.** { *; }
-keep class androidx.drawerlayout.** { *; }
-keep class androidx.dynamicanimation.** { *; }
-keep class androidx.cardview.** { *; }
-keep class androidx.fragment.** { *; }
-keep class androidx.gridlayout.** { *; }
-keep class androidx.legacy.** { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.lifecycle.viewmodel.compose.**
-dontwarn androidx.lifecycle.runtime.compose.**
-keep class androidx.localbroadcastmanager.** { *; }
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.compose.**
-keep class androidx.palette.** { *; }
-keep class androidx.preference.** { *; }
-keep class androidx.startup.** { *; }
-keep class androidx.swiperefreshlayout.** { *; }
-keep class androidx.slidingpanelayout.** { *; }
-keep class androidx.recyclerview.** { *; }
-keep class androidx.transition.** { *; }
-keep class androidx.window.** { *; }
-keep class androidx.viewpager.** { *; }
-keep class androidx.viewpager2.** { *; }
-keep class androidx.browser.** { *; }

# 不保留 Compose 相关类，允许其被混淆
# （无 -keep 规则即默认混淆）

# WebView 与 JS 交互配置（按需启用）
# 需替换 fqcn.of.javascript.interface.for.webview 为实际 JS 接口类全限定名
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#    public *;
#}

# 调试配置：保留行号信息以优化堆栈跟踪（按需启用）
#-keepattributes SourceFile,LineNumberTable
# 隐藏原始源文件名（启用行号保留时可搭配使用）
#-renamesourcefileattribute SourceFile

# 通用 Android/Kotlin 保留规则
# 保留含原生方法的类
-keepclasseswithmembernames class * {
    native <methods>;
}

# 保留枚举类的 values() 和 valueOf() 方法
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# 保留 Parcelable 实现类及 Creator 内部类
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 Material Design 组件类
-keep class com.google.android.material.** { *; }

# 保留 Gson 相关类
-keep class com.google.gson.** { *; }

# OkHttp 3
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-keep class okio.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# 保留 OkHttp 的内部类和方法（如果需要）
-keepclasseswithmembers class okhttp3.** {
    public *;
}

# 保留 OkHttp 的注解
-keepattributes *Annotation*
-keepattributes Signature

# 如果你使用了 OkHttp 的 WebSocket 功能
-keep class okhttp3.internal.ws.** { *; }

# 保留 OkHttp 的 Callback 接口（如果有自定义的回调）
-keep class * implements okhttp3.Callback {
    public <methods>;
}


# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep public class * extends com.bumptech.glide.module.AppGlideModule
-keep public enum com.bumptech.glide.load.resource.bitmap.ImageHeaderParser$** {
    **[] $VALUES;
    public *;
}

# 保留 Glide 注解处理器生成的方法
-keepclassmembers class * extends com.bumptech.glide.module.AppGlideModule {
    <init>(...);
}

# 保留 GeneratedAppGlideModuleImpl 类（Glide 注解处理器生成）
-keep class com.bumptech.glide.GeneratedAppGlideModuleImpl

# 保留 Glide 的注解和模型类
-keep public class * implements com.bumptech.glide.load.model.GlideModel

# 保留 Transformations 相关类
-keep class com.bumptech.glide.load.resource.bitmap.** { *; }
-keep class com.bumptech.glide.load.resource.gif.** { *; }

# 保留所有 Glide 相关类及其方法
-keep class com.bumptech.glide.** { *; }
-dontwarn com.bumptech.glide.**

-dontwarn com.tendcloud.tenddata.**
-keep class com.tendcloud.** {*;}
-keep public class com.tendcloud.** {  public protected *;}




# 忽略 JDK 管理类
-dontwarn com.sun.management.**
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-dontwarn javax.lang.model.element.ModuleElement

# 忽略 Eclipse 编译器
-dontwarn org.eclipse.jdt.internal.compiler.**
-dontwarn org.eclipse.jdt.internal.compiler.tool.EclipseCompiler

# 忽略 Kotlin 元数据问题（临时）
-keepattributes *Annotation*
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses
-keepattributes EnclosingMethod
# 调试控制台:core(产物) 经 ConsoleBridgeRef 反射这些 IDE 侧类的
# 类名/方法/字段名,必须保留名称与成员(否则 release 混淆后反射失败、控制台静默失效)。
-keep class com.luaforge.studio.console.core.ConsoleRegistry { *; }
-keep class com.luaforge.studio.console.core.ConsoleBridge { *; }
-keep class com.luaforge.studio.console.core.SessionInfo { *; }
-keep class com.luaforge.studio.console.core.MethodCallResult { *; }
-keepclassmembers enum com.luaforge.studio.console.core.MethodCallResult$Status { *; }

# Compose 桥:core 侧 LuaActivity 用 `Class.forName("com.luaforge.studio.compose.LuaCompose")`
# 按字符串查找,再反射调 `register(LuaState, Context)`。R8 看不到这层引用,
# 会把这个包判为不可达并删除 —— 后果是 Column/Text/Button 等 65 个全局函数
# 全部不存在,Lua 侧报 `attempt to call a nil value (global 'Column')`。
# `-keep class ... { *; }` 已同时保住类名、成员与静态方法签名(反射按签名匹配)。
-keep class com.luaforge.studio.compose.** { *; }
-dontwarn com.luaforge.studio.compose.**


# ---------------------------------------------------------------------------
# 构建期的 Java 编译 / DEX 生成器
# ---------------------------------------------------------------------------
# IDE 在设备上完成用户项目的 Java 编译与 dex 生成,依赖两个"编译器库":
#   org.eclipse.jdt:ecj   —— ECJ(Java 源码 -> class)
#   com.android.tools:r8  —— D8/R8(class -> dex,以及构建精简)
#
# 这两者都是**编译器**,内部大量依赖反射、ServiceLoader、按类名字符串查找
# 组件,以及通过 `getDeclaredField/Method` 访问内部结构。R8 看不到这些引用,
# 会把它们判为不可达并裁掉/改名,后果是运行时在某个类的静态初始化块里抛
# `ExceptionInInitializerError`。
#
# 典型症状(已实际发生):用户构建 dex 打包方式时,构建日志停在
# "ECJ 前内存: ..." 之后没有任何后续 —— 因为
# `ExceptionInInitializerError` 是 **Error 而非 Exception**,
# 外层的 `catch (Exception)` 接不住,异常直接冒到协程顶层。
#
# 注意:ECJ 与 D8 是**工具链**,不是应用逻辑,keep 它们不会影响
# 用户代码的精简效果(用户项目走的是另一条 D8 链路)。
-keep class org.eclipse.jdt.** { *; }
-dontwarn org.eclipse.jdt.**

-keep class com.android.tools.r8.** { *; }
-keep class com.android.tools.r8.internal.** { *; }
-dontwarn com.android.tools.r8.**

# D8/R8 通过反射加载的辅助组件与资源
-keep class com.android.tools.** { *; }
-dontwarn com.android.tools.**

# 编译器用到的 JDK 抽象(注解处理、文件管理器)在 Android 上是空实现,
# 只需保住类名不被裁剪,不需要实现
-keep class javax.annotation.processing.** { *; }
-keep class javax.tools.** { *; }
-dontwarn javax.tools.**
-dontwarn javax.annotation.processing.**

# ECJ 通过 `messages.properties` 之类的资源文件取本地化报错文本,
# 需要保留目录结构以便 ClassLoader 按路径查找
-keepdirectories org/eclipse/jdt/**
