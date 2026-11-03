package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.UiFlags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModalBlurStateTest {
    @Test fun opening_and_closing_a_modal_toggles_the_blur() {
        val s = ModalBlurState()
        assertFalse(s.blurActive)
        s.opened()
        assertTrue(s.blurActive)
        s.closed()
        assertFalse(s.blurActive)
    }

    @Test fun nested_modals_keep_the_blur_until_the_last_closes() {
        val s = ModalBlurState()
        s.opened(); s.opened()
        s.closed()
        assertTrue(s.blurActive, "one modal still open")
        s.closed()
        assertFalse(s.blurActive)
    }

    @Test fun an_extra_close_never_leaves_the_count_negative() {
        val s = ModalBlurState()
        s.closed()
        s.opened()
        assertEquals(1, s.openCount)
        assertTrue(s.blurActive)
    }

    @Test fun the_setting_off_uses_the_scrim() {
        val s = ModalBlurState(enabled = false)
        s.opened()
        assertFalse(s.blurActive)
        assertFalse(s.blurStyle)
    }

    @Test fun an_unsupported_platform_falls_back_to_the_scrim() {
        val s = ModalBlurState(supported = false)
        s.opened()
        assertFalse(s.blurActive)
        assertFalse(s.blurStyle)
    }

    @Test fun blur_behind_dialogs_defaults_on() {
        assertTrue(UiFlags().blurBehindDialogs)
    }
}
