package com.example.ui

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Base class for Robolectric + Compose UI tests. Each test gets a fresh in-memory
 * [FakeAppDatabase] and a [SwarmViewModel] wired to lightweight fakes so the suite never hits the
 * network or real Room/SQLite (Robolectric has no SQLite support at all -- native or legacy --
 * on linux-aarch64: https://github.com/robolectric/robolectric/issues/8046).
 *
 * Tests should call [setCompactWidth] or [setExpandedWidth] **before** [setContent] so the
 * composition observes the desired screen size.
 *
 * Conscrypt is disabled: its native JNI library isn't published for linux-aarch64, which makes
 * Robolectric's default security-provider setup crash with `UnsatisfiedLinkError` on that host.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
abstract class UiTestBase {

    @get:Rule
    val composeRule: ComposeContentTestRule = createComposeRule()

    // Unconfined (not Standard): Compose's performClick()/waitForIdle() synchronously polls for
    // idle without ever calling advanceUntilIdle() itself. With a Standard dispatcher, a
    // continuation queued by a click handler (e.g. a viewModelScope launch) sits unexecuted until
    // the test body explicitly pumps it, so Compose's idle-wait spins for the full 60s timeout
    // waiting on a state change that will never arrive. Unconfined executes eagerly instead.
    protected val testDispatcher: CoroutineDispatcher = UnconfinedTestDispatcher()
    protected lateinit var viewModel: SwarmViewModel

    @Before
    open fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        viewModel = SwarmViewModel(
            application = context,
            ollamaService = FakeOllamaService(),
            mcpClient = FakeMcpClient(),
            registryClient = FakeMcpRegistryClient(),
            dispatcher = testDispatcher,
            database = FakeAppDatabase(),
            securePrefs = FakeSecurePrefs()
        )
    }

    @After
    open fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Sets a compact phone-sized display (360x640 dp). Must be called before [setContent]. */
    protected fun setCompactWidth() {
        RuntimeEnvironment.setQualifiers("w360dp-h640dp")
    }

    /** Sets an expanded tablet/foldable display (800x1280 dp). Must be called before [setContent]. */
    protected fun setExpandedWidth() {
        RuntimeEnvironment.setQualifiers("w800dp-h1280dp")
    }

    /** Helper that delegates to the Compose test rule. */
    protected fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent(content)
    }

    /** Blocks until the Compose UI is idle. */
    protected fun waitForIdle() {
        composeRule.waitForIdle()
    }

    /**
     * Polls in real (wall-clock) time until [condition] is true or the timeout elapses. Useful as
     * a defensive wait before reading ViewModel state after an action, independent of whether that
     * state update happens to be synchronous.
     */
    protected fun waitUntilTrue(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        composeRule.waitUntil(timeoutMillis, condition)
    }

    /**
     * Runs [testBody] in a [TestScope] that shares the same dispatcher used by the ViewModel and
     * the Compose main dispatcher. Use [kotlinx.coroutines.test.advanceUntilIdle] inside the block
     * to drain pending coroutines and recompositions.
     */
    protected fun runUiTest(testBody: suspend TestScope.() -> Unit) = runTest(testDispatcher) {
        testBody()
    }
}
