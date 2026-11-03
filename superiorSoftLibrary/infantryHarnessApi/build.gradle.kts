// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// :superiorSoftLibrary:infantryHarnessApi -- HARNESS-PLUGIN-3 H3 (docs/HARNESS-PLUGINS.md section 3.1): the ONE surface a harness
// plugin compiles against: what it implements (HarnessPlugin, Harness, HarnessTranslator) and what it may call (HarnessHost).
// A LEAF with no project dependency and no library beyond the Kotlin stdlib, so its ABI is only what is written here. A harness
// jar depends on it `compileOnly`; the daemon provides it at run time through HarnessApiClassLoader, which exposes nothing else.
// Do NOT add a dependency on :infantry (or any other module) here: a harness plugin must never see daemon internals.
plugins {
    kotlin("jvm")
}

// SDK-PUB-1: published to Maven (gradle/sdk-publish.gradle.kts).
apply(from = rootProject.file("gradle/sdk-publish.gradle.kts"))

kotlin {
    jvmToolchain(21)
}
