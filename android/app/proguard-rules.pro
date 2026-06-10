# ProGuard rules for SHRIMP Android App
-keepattributes *Annotation*
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class com.shrimp.voice.settings.ShrimpSettings { *; }
-keepclassmembers class com.shrimp.voice.network.ShrimpProtocol$MessageFrame { *; }
