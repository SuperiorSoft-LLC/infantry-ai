// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.shell

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary

/**
 * NATIVE-PICKER-1: the Windows Explorer-style common item dialog (`IFileOpenDialog`, Vista+), reached through JNA's COM
 * plumbing (JNA already ships with the desktop app: no new native library). Files, folders (`FOS_PICKFOLDERS`), file-type
 * filters, multi-select, an initial folder, and the app window as owner so the dialog is modal to it.
 *
 * [open] blocks its caller: it runs the dialog on its own STA thread (COM needs one) and joins it. Null = cancelled; a COM
 * failure throws, so the caller can fall back and log why.
 */
internal object WindowsFileDialog {
    private val CLSID_FILE_OPEN_DIALOG = Guid.CLSID("{DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7}")
    private val IID_FILE_OPEN_DIALOG = Guid.IID("{d57c7288-d4ad-4768-be02-9d969532d960}")
    private val IID_SHELL_ITEM = Guid.IID("{43826d1e-e718-42ee-bc55-a1e261c37bfe}")

    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val COINIT_DISABLE_OLE1DDE = 0x4
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val FOS_NOCHANGEDIR = 0x8
    private const val FOS_PICKFOLDERS = 0x20
    private const val FOS_FORCEFILESYSTEM = 0x40
    private const val FOS_ALLOWMULTISELECT = 0x200
    private const val FOS_PATHMUSTEXIST = 0x800
    private const val FOS_FILEMUSTEXIST = 0x1000
    private const val SIGDN_FILESYSPATH = 0x80058000.toInt()
    /** HRESULT_FROM_WIN32(ERROR_CANCELLED): the user closed the dialog without choosing. */
    private const val HR_CANCELLED = 0x800704C7.toInt()

    // vtable slots: IUnknown 0-2, IModalWindow 3, IFileDialog 4-26, IFileOpenDialog 27-28; IShellItem / IShellItemArray.
    private const val RELEASE = 2
    private const val SHOW = 3
    private const val SET_FILE_TYPES = 4
    private const val SET_OPTIONS = 9
    private const val GET_OPTIONS = 10
    private const val SET_FOLDER = 12
    private const val SET_TITLE = 17
    private const val GET_RESULT = 20
    private const val GET_RESULTS = 27
    private const val ITEM_GET_DISPLAY_NAME = 5
    private const val ARRAY_GET_COUNT = 7
    private const val ARRAY_GET_ITEM_AT = 8

    @Suppress("FunctionName")
    private interface Shell32X : StdCallLibrary {
        fun SHCreateItemFromParsingName(path: WString, bindCtx: Pointer?, riid: Guid.REFIID, out: PointerByReference): Int
    }
    private val shell32 by lazy { Native.load("shell32", Shell32X::class.java) }

    /** One file-type row: what the dialog shows ("Images") and its pattern ("*.png;*.jpg"). */
    data class Filter(val name: String, val spec: String)

    fun open(folders: Boolean, title: String, initialDir: String?, filters: List<Filter>, multi: Boolean, owner: Pointer?): List<String>? {
        var result: Result<List<String>?> = Result.failure(IllegalStateException("dialog thread did not run"))
        val thread = Thread({ result = runCatching { showOnThisThread(folders, title, initialDir, filters, multi, owner) } }, "native-file-dialog")
        thread.isDaemon = true
        thread.start()
        thread.join()
        return result.getOrThrow()
    }

    private fun showOnThisThread(folders: Boolean, title: String, initialDir: String?, filters: List<Filter>, multi: Boolean, owner: Pointer?): List<String>? {
        val init = Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED or COINIT_DISABLE_OLE1DDE).toInt()
        check(init >= 0) { "CoInitializeEx failed: 0x%08X".format(init) }
        try {
            val ref = PointerByReference()
            hr("CoCreateInstance(FileOpenDialog)", Ole32.INSTANCE.CoCreateInstance(CLSID_FILE_OPEN_DIALOG, null, CLSCTX_INPROC_SERVER, IID_FILE_OPEN_DIALOG, ref).toInt())
            val dialog = ref.value
            try {
                val opts = IntByReference()
                hr("GetOptions", call(dialog, GET_OPTIONS, opts))
                var flags = opts.value or FOS_FORCEFILESYSTEM or FOS_NOCHANGEDIR or FOS_PATHMUSTEXIST
                flags = if (folders) flags or FOS_PICKFOLDERS else flags or FOS_FILEMUSTEXIST
                if (multi) flags = flags or FOS_ALLOWMULTISELECT
                hr("SetOptions", call(dialog, SET_OPTIONS, flags))
                if (title.isNotBlank()) hr("SetTitle", call(dialog, SET_TITLE, WString(title)))
                // The COMDLG_FILTERSPEC array and its strings must stay reachable until Show returns.
                val keep = ArrayList<Memory>()
                if (!folders && filters.isNotEmpty()) {
                    val specs = Memory(filters.size * 2L * Native.POINTER_SIZE)
                    filters.forEachIndexed { i, f ->
                        val name = wide(f.name).also(keep::add)
                        val spec = wide(f.spec).also(keep::add)
                        specs.setPointer((2L * i) * Native.POINTER_SIZE, name)
                        specs.setPointer((2L * i + 1) * Native.POINTER_SIZE, spec)
                    }
                    keep.add(specs)
                    hr("SetFileTypes", call(dialog, SET_FILE_TYPES, filters.size, specs))
                }
                initialDir?.let { dir ->
                    val item = PointerByReference()
                    // A folder that cannot be parsed just leaves the dialog where Windows last left it.
                    if (shell32.SHCreateItemFromParsingName(WString(dir), null, Guid.REFIID(IID_SHELL_ITEM), item) >= 0 && item.value != null) {
                        call(dialog, SET_FOLDER, item.value)
                        call(item.value, RELEASE)
                    }
                }
                val shown = call(dialog, SHOW, owner)
                if (shown == HR_CANCELLED) return null
                hr("Show", shown)
                keep.clear()
                return if (multi) results(dialog) else listOfNotNull(result(dialog))
            } finally {
                call(dialog, RELEASE)
            }
        } finally {
            Ole32.INSTANCE.CoUninitialize()
        }
    }

    private fun result(dialog: Pointer): String? {
        val item = PointerByReference()
        hr("GetResult", call(dialog, GET_RESULT, item))
        return try { path(item.value) } finally { call(item.value, RELEASE) }
    }

    private fun results(dialog: Pointer): List<String> {
        val array = PointerByReference()
        hr("GetResults", call(dialog, GET_RESULTS, array))
        try {
            val count = IntByReference()
            hr("GetCount", call(array.value, ARRAY_GET_COUNT, count))
            return (0 until count.value).mapNotNull { i ->
                val item = PointerByReference()
                hr("GetItemAt", call(array.value, ARRAY_GET_ITEM_AT, i, item))
                try { path(item.value) } finally { call(item.value, RELEASE) }
            }
        } finally {
            call(array.value, RELEASE)
        }
    }

    /** An IShellItem's file-system path (null for a virtual item, which FOS_FORCEFILESYSTEM should already rule out). */
    private fun path(item: Pointer): String? {
        val name = PointerByReference()
        if (call(item, ITEM_GET_DISPLAY_NAME, SIGDN_FILESYSPATH, name) < 0 || name.value == null) return null
        return try { name.value.getWideString(0) } finally { Ole32.INSTANCE.CoTaskMemFree(name.value) }
    }

    /** Calls vtable slot [index] of COM object [obj] (`this` first), returning its HRESULT. */
    private fun call(obj: Pointer, index: Int, vararg args: Any?): Int {
        val fn = Function.getFunction(obj.getPointer(0).getPointer(index.toLong() * Native.POINTER_SIZE), Function.ALT_CONVENTION)
        return fn.invokeInt(arrayOf<Any?>(obj, *args))
    }

    private fun hr(what: String, code: Int) = check(code >= 0) { "$what failed: 0x%08X".format(code) }

    private fun wide(s: String): Memory = Memory((s.length + 1L) * Native.WCHAR_SIZE).apply { setWideString(0, s) }
}
