// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoft.infantry

import android.app.Activity
import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.tauri.annotation.Command
import app.tauri.annotation.InvokeArg
import app.tauri.annotation.TauriPlugin
import app.tauri.plugin.Invoke
import app.tauri.plugin.JSObject
import app.tauri.plugin.Plugin
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

@InvokeArg
class KeyArgs {
    lateinit var key: String
}

@InvokeArg
class PutArgs {
    lateinit var key: String
    lateinit var value: String
}

/**
 * RUST-MOBILE-1: the daemon token at rest. An AES-256-GCM key is generated inside the Android Keystore (it never leaves
 * it); each value is encrypted with it and only the ciphertext (IV + data, base64) is kept in the app's private
 * preferences. Called from Rust (`mobile.rs`), never from the WebView. A value is never logged.
 */
@TauriPlugin
class KeystorePlugin(private val activity: Activity) : Plugin(activity) {
    private val alias = "infantry.daemon"
    private val prefs by lazy { activity.getSharedPreferences("infantry.keystore", Context.MODE_PRIVATE) }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Command
    fun put(invoke: Invoke) {
        val args = invoke.parseArgs(PutArgs::class.java)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
            val sealed = cipher.iv + cipher.doFinal(args.value.toByteArray(Charsets.UTF_8))
            prefs.edit().putString(args.key, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
            invoke.resolve()
        } catch (e: Exception) {
            invoke.reject("the keystore refused the value (${e.javaClass.simpleName})")
        }
    }

    @Command
    fun get(invoke: Invoke) {
        val args = invoke.parseArgs(KeyArgs::class.java)
        val out = JSObject()
        val stored = prefs.getString(args.key, null)
        if (stored == null) {
            out.put("value", null)
            invoke.resolve(out)
            return
        }
        try {
            val sealed = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, sealed, 0, 12))
            out.put("value", String(cipher.doFinal(sealed, 12, sealed.size - 12), Charsets.UTF_8))
            invoke.resolve(out)
        } catch (e: Exception) {
            invoke.reject("the keystore could not open the value (${e.javaClass.simpleName})")
        }
    }

    @Command
    fun remove(invoke: Invoke) {
        val args = invoke.parseArgs(KeyArgs::class.java)
        prefs.edit().remove(args.key).apply()
        invoke.resolve()
    }
}
