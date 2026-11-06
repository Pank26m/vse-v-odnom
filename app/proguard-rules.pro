# yt-dlp / ffmpeg обёртка и её JSON (Jackson) работают через рефлексию
-keep class com.yausername.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-dontwarn com.fasterxml.jackson.**
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod
-keep class org.apache.commons.compress.** { *; }
-keep class org.apache.commons.io.** { *; }
-dontwarn org.apache.commons.**
