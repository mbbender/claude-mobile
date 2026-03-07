# JSch
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Tink / security-crypto
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**
-dontwarn javax.annotation.concurrent.**
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**
-keep class com.google.crypto.tink.** { *; }
