// SPDX-License-Identifier: GPL-3.0-only
package com.xpresstap.keyboard.latin

import com.xpresstap.keyboard.latin.common.LocaleUtils.constructLocale
import com.xpresstap.keyboard.latin.utils.ScriptUtils.SCRIPT_CYRILLIC
import com.xpresstap.keyboard.latin.utils.ScriptUtils.SCRIPT_DEVANAGARI
import com.xpresstap.keyboard.latin.utils.ScriptUtils.SCRIPT_LATIN
import com.xpresstap.keyboard.latin.utils.ScriptUtils.script
import kotlin.test.Test
import kotlin.test.assertEquals

class ScriptUtilsTest {
    @Test fun defaultScript() {
        assertEquals(SCRIPT_LATIN, "en".constructLocale().script())
        assertEquals(SCRIPT_DEVANAGARI, "hi".constructLocale().script())
        assertEquals(SCRIPT_LATIN, "hi_zz".constructLocale().script())
        assertEquals(SCRIPT_LATIN, "sr-Latn".constructLocale().script())
        assertEquals(SCRIPT_CYRILLIC, "mk".constructLocale().script())
        assertEquals(SCRIPT_CYRILLIC, "fr-Cyrl".constructLocale().script())
    }
}
