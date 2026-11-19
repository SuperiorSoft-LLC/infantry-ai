package com.superiorsoft.infantry

// RUST-MOBILE-1: no edge-to-edge, so the system bars never cover the shell's title bar or composer; the manifest's
// adjustResize shrinks the WebView above the on-screen keyboard.
class MainActivity : TauriActivity()
