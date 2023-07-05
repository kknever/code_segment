# basic proguard rules
# 避免混淆泛型、注解、内部类等
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions
-keepattributes *JavascriptInterface*
# 抛出异常时保留代码行号
-keepattributes SourceFile,LineNumberTable

-dontwarn android.webkit.WebView
-dontwarn android.net.http.SslError
-dontwarn android.webkit.WebViewClient
-dontwarn android.view.**
-dontwarn java.util.concurrent.**
-dontwarn org.reactivestreams.**
-keep public class android.net.http.SslError
-keep public class * extends android.app.Application
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Fragment
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends android.app.backup.BackupAgentHelper
-keep public class * extends android.preference.Preference
-keep public class * extends android.view.View

# 保留我们自定义控件（继承自View）不被混淆
-keep public class * extends android.view.View {
    *** get*();
    void set*(***);
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

-keepclasseswithmembernames class * {
	native <methods>;
	public <init>(android.content.Context, android.util.AttributeSet);
	public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Serializable & Parcelable
-keep public class * implements java.io.Serializable {*;}
-keep class * implements android.os.Parcelable {
	public static final android.os.Parcelable$Creator *;
}

# enum
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# support libraries
-dontwarn android.support.**
-keep class android.support.** { *; }
-keep interface android.support.** { *; }
-keep public class * extends android.support.**
# android x
-dontwarn androidx.**
-keep class androidx.** { *; }
-keep interface androidx.** { *; }
-keep public class * extends androidx.**

# kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
# Most of volatile fields are updated with AFU and should not be mangled
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# common third lib
-dontwarn com.github.**
-keep class com.github.** { *; }
-dontwarn org.**
-keep class org.** { *; }
-dontwarn com.airbnb.**
-keep class com.airbnb.** { *; }
-dontwarn com.google.**
-keep class com.google.** { *; }
-dontwarn com.squre.**
-keep class com.squre.** { *; }
-dontwarn com.facebook.**
-keep class com.facebook.** { *; }



# 在app proguard-pro中测试引用上述pro
-optimizationpasses 5
-optimizations !code/simplification/arithmetic,!code/simplification/cast,!field/*,!class/merging/*
-ignorewarnings
-verbose
# 引用多个混淆规则
-include rules/basic_proguard.pro
