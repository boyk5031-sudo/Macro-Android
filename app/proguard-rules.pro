# --- MacroAndroid release rules (R8 full mode; AGP 9 default) ---------------------------------------------------
# Keep source file names and line numbers so crash traces from Play Console can be retraced with mapping.txt.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# kotlinx.serialization (macro JSON schema, navigation routes). Rules from the kotlinx.serialization README.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class ** {
    @kotlinx.serialization.SerialName <fields>;
}

# Sealed macro model: SerialName discriminators must survive (they are string constants; class names may shrink).
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class com.macroandroid.automation.model.** { *; }
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class com.macroandroid.automation.engine.** { *; }

# Hilt / Room / WorkManager ship consumer rules. AccessibilityService and receivers are referenced from the manifest.
# Nothing else needs reflection: no Gson/Moshi, no dynamic class loading, no JNI.
