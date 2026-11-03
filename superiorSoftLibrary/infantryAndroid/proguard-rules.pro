# ANDROID-MINIFY-1: app-side R8 rules for the Infantry Android release (and the minifiedSmoke build type).
#
# kotlinx.serialization: NO app-side keep rules on purpose. kotlinx-serialization-core ships its own R8 consumer
# rules (META-INF/com.android.tools/r8/kotlinx-serialization-*.pro), maintained per library version and merged by
# AGP; an app-side copy would go stale. infantryUiCore also calls every serializer explicitly
# (e.g. DaemonClient.kt:132 json.decodeFromString(Envelope.serializer(), text)), which R8 traces statically.
# ktor and kotlinx.coroutines likewise ship their own consumer rules.
# Runtime guard: src/androidTest/.../MinifiedSmokeTest.kt (connectedMinifiedSmokeAndroidTest).

# Readable crash stack traces (retrace with build/outputs/mapping/<variant>/mapping.txt).
-keepattributes SourceFile,LineNumberTable
