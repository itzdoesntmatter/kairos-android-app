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

# Fix R8 error: Missing class okhttp3.internal.Util
-dontwarn okhttp3.internal.Util
-keep class okhttp3.internal.Util { *; }

# ical4j 4.4.0 (Proton sync bridge): ZoneRulesProvider exists on Android API 26+
# (minSdk 28) and is only reflectively probed by ical4j; joda-convert is an
# optional compile-only dep of threeten-extra.
-dontwarn java.time.zone.ZoneRulesProvider
-dontwarn org.joda.convert.ToString
