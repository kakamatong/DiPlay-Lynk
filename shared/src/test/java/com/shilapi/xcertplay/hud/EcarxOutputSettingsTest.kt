package com.shilapi.xcertplay.hud

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class EcarxOutputSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clearPreferences() {
        context.getSharedPreferences("diplay_ecarx_outputs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun autoPresentationOnCarPlayIsOnByDefaultAndCanBeTurnedOff() {
        assertTrue(EcarxOutputSettings.autoPresentOnCarPlay(context))
        EcarxOutputSettings.setAutoPresentOnCarPlay(context, false)
        assertFalse(EcarxOutputSettings.autoPresentOnCarPlay(context))
    }
}
