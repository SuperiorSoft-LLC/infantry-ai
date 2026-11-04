// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
dependencies { compileOnly(project(":superiorSoftLibrary:infantryDaemonApi")) }
tasks.jar { archiveFileName.set("Acme_HelloView-daemon.jar"); from(layout.projectDirectory.file("../module.json")) }
