# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in ${sdk.dir}/tools/proguard/proguard-android.txt

# UniApp WebView Plugin specific rules
-keep class uni.dcloud.io.uniplugin_webview.** { *; }
-keep class com.alibaba.fastjson.** { *; }
