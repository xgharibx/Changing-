# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class eg.wadeh.core.** { *** Companion; }
-keepclasseswithmembers class eg.wadeh.core.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class eg.wadeh.**$$serializer { *; }
-keepclassmembers class eg.wadeh.** { *** Companion; }
-keepclasseswithmembers class eg.wadeh.** { kotlinx.serialization.KSerializer serializer(...); }
-dontwarn org.slf4j.**
