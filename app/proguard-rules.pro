# yt-dlp / ffmpeg обёртка и её JSON (Jackson) работают через рефлексию
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**
# PDFBox (шрифты и ресурсы грузит по имени)
-keep class com.tom_roush.** { *; }
-dontwarn com.tom_roush.**
-dontwarn com.gemalto.jp2.**
-dontwarn org.bouncycastle.**
# junrar
-keep class com.github.junrar.** { *; }
-dontwarn com.github.junrar.**
-dontwarn org.slf4j.**
# libarchive (RAR5/7z): нативный код вызывает Java-классы по имени
-keep class me.zhanghai.android.libarchive.** { *; }

