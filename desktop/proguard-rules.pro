# Lyra para Windows: solo se recorta el paquete de iconos de Material (36 MB, se usan unos 40).
# Todo lo demás se deja tal cual: FFmpeg (JavaCPP), JNA, NewPipe y la serialización usan reflexión.
-keep class !androidx.compose.material.icons.**,** { *; }
-keepattributes *
-dontobfuscate
-dontoptimize
-dontwarn **
-ignorewarnings
