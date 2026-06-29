// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package com.xpresstap.keyboard.latin

import android.app.Application
import android.os.Build
import com.xpresstap.keyboard.keyboard.emoji.SupportedEmojis
import com.xpresstap.keyboard.latin.define.DebugFlags
import com.xpresstap.keyboard.latin.settings.Defaults
import com.xpresstap.keyboard.latin.settings.Settings
import com.xpresstap.keyboard.latin.utils.FoldableUtils
import com.xpresstap.keyboard.latin.utils.GestureLibExtractor
import com.xpresstap.keyboard.latin.utils.LayoutUtilsCustom
import com.xpresstap.keyboard.latin.utils.Log
import com.xpresstap.keyboard.latin.utils.SubtypeSettings
import com.xpresstap.keyboard.latin.utils.prefs
import com.xpresstap.keyboard.latin.utils.upgradeToolbarPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        DebugFlags.init(this)
        FoldableUtils.init(this)
        Settings.init(this)
        SubtypeSettings.init(this)

        val scope = CoroutineScope(Dispatchers.Default)
        scope.launch { // do some uncritical work in background for faster startup
            GestureLibExtractor.extractIfNeeded(this@App)
            SupportedEmojis.load(this@App)
            LayoutUtilsCustom.removeMissingLayouts(this@App)
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            @Suppress("DEPRECATION")
            Log.i(
                "startup", "Starting ${applicationInfo.processName} version ${packageInfo.versionName} (${
                    packageInfo.versionCode
                }) on Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
            )
        }

        RichInputMethodManager.init(this)
        checkVersionUpgrade(this)
        if (BuildConfig.DEBUG) // do this on every debug apk start because we may work on adding a new toolbar key
            upgradeToolbarPrefs(prefs())
        transferOldPinnedClips(this) // todo: remove in a few months, maybe end 2026
        app = this
        Defaults.initDynamicDefaults(this)
    }

    companion object {
        // used so JniUtils can access application once
        private var app: App? = null
        fun getApp(): App? {
            val application = app
            app = null
            return application
        }
    }
}
