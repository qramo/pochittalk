# libwebrtc は JNI から Java のクラス/メソッドを名前で呼ぶので、難読化・削除させない
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
