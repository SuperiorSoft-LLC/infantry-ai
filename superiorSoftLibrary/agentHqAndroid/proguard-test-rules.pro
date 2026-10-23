# ANDROID-MINIFY-1: R8 rules for the androidTest APK only (MinifiedSmokeTest against the minifiedSmoke build).
# They never reach the app under test. androidx.test references compile-only errorprone annotations.
-dontwarn com.google.errorprone.annotations.**
# The test APK is shrunk too: keep the instrumentation runner, JUnit and the smoke test itself, or 0 tests run.
-keep class androidx.test.** { *; }
-keep class org.junit.** { *; }
-keep class junit.** { *; }
-keep @org.junit.runner.RunWith class * { *; }
