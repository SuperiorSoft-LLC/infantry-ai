// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness.descriptor

import java.io.File
import java.nio.file.Files

/**
 * HARNESS-DESC-1 test fixture: an echo-style CLI that needs no installation. It is a one-file Java program the
 * JDK that runs the tests launches in source mode (`java EchoCli.java <args>`): it reads the prompt from stdin
 * (like every agent CLI the daemon drives) and answers in JSONL, one record per line.
 *
 *     {"kind":"hello","sid":"echo-session-1"}
 *     {"kind":"say","text":"echo: <prompt>"}
 *     {"kind":"say","text":"argv: <args joined by |>"}
 *     {"kind":"tokens","in":3,"out":5}
 *     {"kind":"done","why":"stop"}
 *
 * The descriptor under test maps those records to harness events with no code.
 */
object EchoCliFixture {
    private val SOURCE = """
        import java.io.*;
        import java.nio.charset.StandardCharsets;
        public class EchoCli {
            static String q(String s) {
                StringBuilder b = new StringBuilder("\"");
                for (char c : s.toCharArray()) {
                    if (c == '"' || c == '\\') b.append('\\').append(c);
                    else if (c == '\n') b.append("\\n");
                    else if (c == '\r') b.append("\\r");
                    else b.append(c);
                }
                return b.append('"').toString();
            }
            public static void main(String[] args) throws Exception {
                String prompt = new String(System.in.readAllBytes(), StandardCharsets.UTF_8).trim();
                System.out.println("{\"kind\":\"hello\",\"sid\":\"echo-session-1\"}");
                System.out.println("{\"kind\":\"say\",\"text\":" + q("echo: " + prompt) + "}");
                System.out.println("{\"kind\":\"say\",\"text\":" + q("argv: " + String.join("|", args)) + "}");
                System.out.println("not json at all");
                System.out.println("{\"kind\":\"tokens\",\"in\":3,\"out\":5}");
                System.out.println("{\"kind\":\"done\",\"why\":\"stop\"}");
            }
        }
    """.trimIndent()

    /** Write the source into [dir] and return the file. */
    fun writeSource(dir: File): File = File(dir, "EchoCli.java").also { it.writeText(SOURCE) }

    fun javaExecutable(): String = File(System.getProperty("java.home"), "bin/java").path

    /** A descriptor entry (as JSON text) registering this CLI under [id]. [extra] is spliced in verbatim. */
    fun descriptorJson(id: String, source: File, extra: String = ""): String = """
        {
          "id": "$id",
          "name": "Echo CLI",
          "command": ${quote(javaExecutable())},
          "args": [${quote(source.path)}, ["--model", "{model}"], ["--resume", "{sessionId}"], ["--effort", "{effort}"]],
          "capabilities": ["MODEL_SELECTION", "SESSION_RESUME", "USAGE"],
          "output": {
            "format": "jsonl",
            "map": [
              {"match": {"kind": "hello"}, "event": "session", "fields": {"id": "sid"}},
              {"match": {"kind": "say"}, "event": "text", "fields": {"text": "text"}},
              {"match": {"kind": "tokens"}, "event": "usage", "fields": {"input": "in", "output": "out"}},
              {"match": {"kind": "done"}, "event": "completion", "fields": {"reason": "why"}}
            ]
          },
          "commands": [
            {"name": "model", "description": "Switch the model", "action": "SET_MODEL", "argument": "<model>"}
          ]$extra
        }
    """.trimIndent()

    private fun quote(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    fun tempDir(tag: String): File = Files.createTempDirectory("infantry-desc-$tag").toFile()
}
