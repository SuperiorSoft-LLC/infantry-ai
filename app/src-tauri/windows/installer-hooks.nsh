; SPDX-License-Identifier: Apache-2.0
; Copyright (c) SuperiorSoft LLC
; RUST-UPD-1: NSIS hooks for the Infantry AI Rust installer (tauri.bundle.conf.json).
; PATH is opt-in: the app adds its folder only when the user turns on paths.addCli (Settings > Daemon). Uninstalling
; takes the entry off again; `uninstall-cli` touches only an entry it recorded, so it is a no-op when none was added.
!macro NSIS_HOOK_PREUNINSTALL
  IfFileExists "$INSTDIR\infantry.exe" 0 +2
    nsExec::Exec '"$INSTDIR\infantry.exe" uninstall-cli --dir "$INSTDIR"'
!macroend
