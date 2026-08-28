# sherpa-onnx: JNI entry points are looked up reflectively from native code.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# MediaPipe tasks-genai uses JNI + protobuf-lite reflection.
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# Apache Commons Compress: only the bzip2/tar codepaths are used.
-dontwarn org.apache.commons.compress.**

# Keep the IME service (referenced from AndroidManifest / system binding).
-keep class com.vboard.app.ime.VBoardImeService { *; }
