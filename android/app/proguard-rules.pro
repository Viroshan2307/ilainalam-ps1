# Minify is off for Phase 1. If you enable it, keep ExecuTorch's JNI classes:
-keep class org.pytorch.executorch.** { *; }
-keep class com.facebook.jni.** { *; }
