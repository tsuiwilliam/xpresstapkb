// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package com.xpresstap.keyboard.latin.utils

import android.content.Context
import android.os.Build
import com.xpresstap.keyboard.latin.settings.Settings
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Automatically extracts libjni_latinimegoogle.so from Gboard into our files dir so that
 * JniUtils can load it as the "user-supplied" gesture library on the next process start.
 * This works even on Android 6+ where Gboard uses extractNativeLibs=false (lib stays in APK).
 *
 * Call from a background thread only.
 */
object GestureLibExtractor {
    private const val TAG = "GestureLibExtractor"

    private val GBOARD_PACKAGES = arrayOf(
        "com.google.android.inputmethod.latin",
        "com.google.android.inputmethod.latin.go"
    )

    fun extractIfNeeded(context: Context) {
        val dest = File(context.filesDir, JniUtils.JNI_LIB_IMPORT_FILE_NAME)
        if (dest.exists()) return  // already present (user-placed or previously extracted)

        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return
        val libEntry = "lib/$abi/libjni_latinimegoogle.so"

        for (pkg in GBOARD_PACKAGES) {
            try {
                val ai = context.packageManager.getApplicationInfo(pkg, 0)

                // First try: extracted native lib dir (Android < 6 or extractNativeLibs=true)
                val extracted = File(ai.nativeLibraryDir, "libjni_latinimegoogle.so")
                if (extracted.exists() && extracted.length() > 1024) {
                    extracted.copyTo(dest, overwrite = true)
                    saveChecksum(context, dest)
                    Log.i(TAG, "Copied gesture lib from $pkg native dir")
                    return
                }

                // Second try: inside APK zip (extractNativeLibs=false, modern Gboard)
                val apkPaths = mutableListOf(ai.sourceDir)
                ai.splitSourceDirs?.let { apkPaths.addAll(it) }

                for (apkPath in apkPaths) {
                    if (extractFromZip(apkPath, libEntry, dest)) {
                        saveChecksum(context, dest)
                        Log.i(TAG, "Extracted gesture lib from $pkg APK: $apkPath")
                        return
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not extract from $pkg: ${e.message}")
            }
        }
    }

    private fun extractFromZip(apkPath: String, entry: String, dest: File): Boolean {
        return runCatching {
            ZipFile(apkPath).use { zip ->
                val e = zip.getEntry(entry) ?: return false
                if (e.size < 1024) return false
                zip.getInputStream(e).use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                true
            }
        }.getOrDefault(false)
    }

    private fun saveChecksum(context: Context, lib: File) {
        val checksum = ChecksumCalculator.checksum(lib) ?: return
        context.protectedPrefs()
            .edit()
            .putString(Settings.PREF_LIBRARY_CHECKSUM, checksum)
            .apply()
        Log.i(TAG, "Saved gesture lib checksum: $checksum")
    }
}
