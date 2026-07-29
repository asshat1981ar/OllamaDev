package com.example.ui

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config

/** Voice capture now lives inline in Session's input bar (VoiceScreen was retired) -- tapping the
 *  mic starts listening, tapping again stops and routes a (simulated) spoken command into the
 *  same input field a typed prompt would use. */
class SessionVoiceInputTest : UiTestBase() {

    @Config(qualifiers = "w360dp-h2400dp")
    @Test
    fun tapMic_startsListening_tapAgain_populatesInputWithSpokenCommand() = runUiTest {
        setContent { SessionScreen(viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("session_mic_button").performClick()
        advanceUntilIdle()

        assert(viewModel.isVoiceListening.value)
        composeRule.onNodeWithTag("session_input_field").assertTextEquals("Listening for swarm command...")

        composeRule.onNodeWithTag("session_mic_button").performClick()
        advanceUntilIdle()

        assert(!viewModel.isVoiceListening.value)
        assert(viewModel.voiceTranscript.value.isNotBlank())
        assert(viewModel.voiceTranscript.value != "Listening for swarm command...")
    }
}
