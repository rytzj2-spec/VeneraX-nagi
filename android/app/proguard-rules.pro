# ONNX Runtime is accessed via JNI; stripping it breaks flutter_onnxruntime.
-keep class ai.onnxruntime.** { *; }

# Nagisaland suite：H 分区（Han1meViewer 库）通过反射调用常驻底栏
-keep class io.github.kyosee.venera.suite.SuiteNav { public static *; }

# Nagisaland 主界面：网页通过 @JavascriptInterface 调用，SuiteNav 用 Class.forName 找主界面
-keep class com.nagisaland.mobile.** { *; }
-keepclassmembers class * { @android.webkit.JavascriptInterface <methods>; }
