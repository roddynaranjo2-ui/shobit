# WireGuard's JNI entry points are declared native on GoBackend and discovered by name.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep configuration records used by the embedded backend.
-keep class com.wireguard.** { *; }
-dontwarn org.codehaus.mojo.animal_sniffer.**
