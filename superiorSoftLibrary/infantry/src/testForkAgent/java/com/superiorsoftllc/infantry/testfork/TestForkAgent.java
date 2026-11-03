package com.superiorsoftllc.infantry.testfork;

import java.io.File;

/**
 * TEST-SPEED-1: gives every parallel test fork its own state dir and tmpdir.
 *
 * The test task runs several forks (maxParallelForks), and every fork gets the SAME JVM arguments, so a per-fork
 * directory cannot be passed on the command line. This agent runs in each fork before main() (and so before anything
 * reads the settings) and points both at a subfolder named after the fork's PID: {@code <tmpdir>/fork-<pid>} and
 * {@code <INFANTRY_STATE_DIR>/fork-<pid>}. The build wipes the parents once per task run. A fork therefore never sees
 * another fork's state files, lock, or fixed-name paths built from java.io.tmpdir. Limit: the JDK's own createTempFile
 * (java.io and java.nio) caches java.io.tmpdir during VM startup, before premain, so random temp files stay in the
 * shared parent; their names are unique, so that is harmless.
 */
public final class TestForkAgent {
    private TestForkAgent() {}

    public static void premain(String args) {
        String fork = "fork-" + ProcessHandle.current().pid();
        redirect("java.io.tmpdir", fork);
        redirect("INFANTRY_STATE_DIR", fork);
    }

    private static void redirect(String property, String fork) {
        String base = System.getProperty(property);
        if (base == null || base.isBlank()) return;
        File dir = new File(base, fork);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("TestForkAgent: cannot create " + dir);
        System.setProperty(property, dir.getAbsolutePath());
    }
}
