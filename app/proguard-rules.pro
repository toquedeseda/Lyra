# --- NewPipeExtractor (usa reflexión y el motor JavaScript Rhino para descifrar las URLs de YouTube) ---
-keep class org.schabi.newpipe.extractor.** { *; }
-keep class org.mozilla.javascript.** { *; }
-keep class org.mozilla.classfile.ClassFileWriter
-dontwarn org.mozilla.javascript.**
-dontwarn javax.script.**
-dontwarn java.beans.**
-dontwarn org.ietf.jgss.**
-dontwarn com.google.re2j.**
-dontwarn org.jsoup.**
-dontwarn com.grack.nanojson.**
-keep class com.grack.nanojson.** { *; }

# --- kotlinx.serialization: modelos que se guardan en disco (inicio, cola, copias) ---
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.lyra.music.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.lyra.music.**$$serializer { *; }

# --- OkHttp / Okio ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
