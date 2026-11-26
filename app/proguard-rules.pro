# Proguard rules for IqraX Companion
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-keep class com.iqrax.companion.model.** { *; }
