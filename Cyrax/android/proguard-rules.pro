# Aggressive obfuscation
-optimizationpasses 9
-verbose

# Keep nothing
-keep class com.random.package.name.** { *; }
-keepclassmembers class com.random.package.name.** { *; }

# String encryption
-assumenosideeffects class java.lang.StringBuilder { *; }

# Obfuscate classes
-repackageclasses 'com.package'
-allowaccessmodification

# Remove logging
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# Remove stack traces
-renamesourcefileattribute SourceFile
-keepattributes SourceFile,LineNumberTable
