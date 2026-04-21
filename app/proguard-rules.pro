# Keep JNI bridge class — the C++ engine references these by name
-keep class com.kargathra.timegrapher.audio.AudioEngineJNI {
    native <methods>;
    *;
}

# Keep all data classes used with JNI / serialisation
-keep class com.kargathra.timegrapher.audio.** { *; }
-keep class com.kargathra.timegrapher.timing.** { *; }

# Oboe
-keep class com.google.oboe.** { *; }
