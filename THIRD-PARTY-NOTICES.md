# Third-party notices

Infantry AI ships the following third-party components. Each stays under its own license; this list is the
attribution those licenses ask for. The license of Infantry AI itself is not chosen yet (see `LICENSE`).

## JediTerm (terminal emulator of the Run console)

- `org.jetbrains.jediterm:jediterm-core` and `jediterm-ui` 3.72, https://github.com/JetBrains/jediterm
- Copyright JetBrains s.r.o. and contributors.
- Dual-licensed by its authors under the GNU LGPL v3 and the Apache License 2.0, at the user's option.
  **Infantry AI uses it under the Apache License 2.0** (https://www.apache.org/licenses/LICENSE-2.0).
- JediTerm contains classes derived from jVT220 by J.W. Janssen (https://github.com/jawi/jVT220), Apache License 2.0.
- Used unmodified, as a library.

## pty4j (pseudo-terminal for runs)

- `org.jetbrains.pty4j:pty4j` 0.13.13, https://github.com/JetBrains/pty4j
- Copyright JetBrains s.r.o. and contributors.
- Eclipse Public License 1.0 (https://www.eclipse.org/legal/epl-v10.html). Used unmodified; its source is
  available at the address above.
- Bundles native helpers: winpty (Copyright Ryan Prichard, MIT License, https://github.com/rprichard/winpty) and
  Microsoft's ConPTY / OpenConsole binaries (Copyright Microsoft Corporation, MIT License,
  https://github.com/microsoft/terminal).

## JNA (native calls; used by pty4j and the daemon's process priority)

- `net.java.dev.jna:jna` / `jna-platform`, https://github.com/java-native-access/jna
- Dual-licensed under the LGPL 2.1 or later and the Apache License 2.0; used under the Apache License 2.0.
