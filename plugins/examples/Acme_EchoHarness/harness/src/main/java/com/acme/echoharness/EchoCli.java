package com.acme.echoharness;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * The "agent CLI" of the echo harness: reads the prompt from stdin until EOF, prints a session line, then every prompt line
 * back with a prefix. Plain Java on purpose: it runs in its own JVM with only this jar on the class path (no Kotlin runtime).
 */
public final class EchoCli {
    public static void main(String[] args) throws Exception {
        String prefix = System.getenv().getOrDefault("ECHO_PREFIX", "echo");
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        StringBuilder prompt = new StringBuilder();
        for (String line; (line = in.readLine()) != null; ) prompt.append(line).append('\n');
        System.out.println("session: echo-" + Integer.toHexString(prompt.toString().hashCode()));
        String limit = System.getenv("ECHO_CONTEXT_LIMIT");
        if (limit != null) System.out.println("context limit: " + limit);
        for (String line : prompt.toString().split("\n")) System.out.println(prefix + ": " + line);
        System.out.flush();
    }
}
