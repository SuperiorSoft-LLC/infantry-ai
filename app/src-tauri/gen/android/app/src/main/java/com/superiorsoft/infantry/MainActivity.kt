package com.superiorsoft.infantry

import android.os.Bundle
import android.view.View
import android.webkit.WebView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

// RUST-MOBILE-1: Android 15 forces edge-to-edge (the WebView draws under the status and nav bars). Pad the content
// view by the system bars, display cutout and on-screen keyboard so the shell's title bar and composer stay clear.
// Done natively because the WebView's env(safe-area-inset-*) is 0 on many versions; desktop is untouched.
class MainActivity : TauriActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content: View = findViewById(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(content)
    }

    // ANDROID-PINCH-1: the app page never zooms (pinch, double-tap or zoom buttons) and the system font size does not
    // distort the layout; Settings > Appearance > UI size is the size control. The viewport meta locks the page too.
    override fun onWebViewCreate(webView: WebView) {
        super.onWebViewCreate(webView)
        webView.settings.apply {
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            textZoom = 100
        }
    }
}
