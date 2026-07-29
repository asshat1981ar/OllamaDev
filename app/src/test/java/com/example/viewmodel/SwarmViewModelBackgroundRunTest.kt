package com.example.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeMcpRegistryClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmViewModelBackgroundRunTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun runSwarmInBackground_doesNotCrashAndRecordsUserMessage() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = FakeAppDatabase()
        val viewModel = SwarmViewModel(
            application = context,
            ollamaService = FakeOllamaService(),
            mcpClient = FakeMcpClient(),
            registryClient = FakeMcpRegistryClient(),
            dispatcher = testDispatcher,
            database = db,
            securePrefs = FakeSecurePrefs()
        )

        // allSwarmConfigs is a WhileSubscribed StateFlow — it starts with emptyList() until a
        // subscriber exists. runSwarmInBackground falls back to allSwarmConfigs.value.firstOrNull()
        // which returns null on an unsubscribed flow, causing an early return before the chat
        // message is inserted. Pre-select a config explicitly to bypass the flow subscription race.
        val seedConfig = db.swarmConfigDao().getAllSwarmConfigs().let {
            db.swarmConfigDao().getSwarmConfigById(1)
        }
        assertNotNull("FakeAppDatabase must seed at least one SwarmConfig", seedConfig)
        viewModel.selectSessionSwarmConfig(seedConfig)

        viewModel.runSwarmInBackground("run this in the background")
        advanceUntilIdle()

        val messages = db.chatMessageDao().getRecentMessagesSync(10)
        assertNotNull(messages.find { it.message.contains("[BACKGROUND]") })
    }
}
