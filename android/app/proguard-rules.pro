# ONNX Runtime is accessed via JNI; stripping it breaks flutter_onnxruntime.
-keep class ai.onnxruntime.** { *; }

# Nagisaland suite：H 分区（android/han）通过反射调用 SuiteNav
-keep class io.github.kyosee.venera.suite.SuiteNav { *; }
-keep class io.github.kyosee.venera.suite.SuiteSwitchActivity
-keep class io.github.kyosee.venera.suite.SuiteNavInitializer { *; }
