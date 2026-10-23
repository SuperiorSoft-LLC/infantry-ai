# ANDROID-MINIFY-1: extra R8 rules for the minifiedSmoke APK ONLY (never the release APK).
# The androidTest APK does not bundle libraries the app already has; it calls the app's copy. The androidx.test
# runner calls androidx.tracing.Trace methods the app itself never uses, so the app's R8 removes them and the
# runner dies with NoSuchMethodError before any test runs. Keeping androidx.tracing has no bearing on what the
# smoke checks (minification and kotlinx.serialization decoding).
-keep class androidx.tracing.** { *; }
# Same reason for the Kotlin stdlib: the androidx.test runner is written in Kotlin (e.g. TestDirCalculator uses
# kotlin.LazyKt) and the app's R8 inlines/removes stdlib pieces the app does not call. Only kotlin.** is kept:
# app classes and kotlinx.serialization stay fully subject to R8, which is what the smoke exercises.
-keep class kotlin.** { *; }
