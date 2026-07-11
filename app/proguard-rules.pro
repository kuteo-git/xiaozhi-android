# ===== Keep rules cho release minify (R8) =====

# JNI: giữ class + tên native method (.so bind theo tên Java_<pkg>_<class>_<method>)
-keepclasseswithmembernames class * {
    native <methods>;
}

# microWakeWord: .so bind đúng com.aiboxplus.sample_wake_word.MicroWakeWordEngine
# + DetectionListener gọi NGƯỢC từ native (callback) -> phải giữ nguyên.
-keep class com.aiboxplus.sample_wake_word.** { *; }

# Opus JNI (src/main/cpp/opus_recorder.cpp + opus_decoder.cpp bind theo tên class+method)
-keep class info.dourok.voicebot.OpusEncoder { *; }
-keep class info.dourok.voicebot.OpusDecoder { *; }

# snowboy JNI (libsnowboy-detect-android.so bind ai.kitt.snowboy.snowboyJNI/SnowboyDetect theo tên)
-keep class ai.kitt.snowboy.** { *; }

# Giữ tên file + số dòng cho stack trace crash đọc được
-keepattributes SourceFile,LineNumberTable

# okhttp tham chiếu TLS provider tùy chọn không nhúng -> bỏ qua cảnh báo
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**
