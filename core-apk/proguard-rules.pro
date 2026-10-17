# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ---- Media3 反射扩展(DefaultMediaSourceFactory 用 Class.forName 发现) ----
# HLS/DASH/SmoothStreaming/RTSP/RTMP 的 MediaSource 工厂与 DataSource 由 media3 内部
# 按类名反射加载:类被裁剪或改名后播放器会静默退回"仅支持渐进式",网络流全部失败。
-keep class androidx.media3.exoplayer.hls.** { *; }
-keep class androidx.media3.exoplayer.dash.** { *; }
-keep class androidx.media3.exoplayer.smoothstreaming.** { *; }
-keep class androidx.media3.exoplayer.rtsp.** { *; }
-keep class androidx.media3.datasource.rtmp.** { *; }
# librtmp 走 JNI,native 侧按名字查符号。
-keep class io.antmedia.rtmp_client.** { *; }
-keepclasseswithmembernames class io.antmedia.rtmp_client.** { native <methods>; }
# 各容器抽取器同样按类名反射发现(DefaultExtractorsFactory)。
-keep class androidx.media3.extractor.** { *; }
-dontwarn androidx.media3.**
-dontwarn io.antmedia.**
