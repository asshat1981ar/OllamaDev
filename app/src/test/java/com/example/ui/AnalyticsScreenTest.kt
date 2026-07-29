package com.example.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.example.data.SwarmTask
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Before
import org.junit.Test
import org.robolectric.annotation.Config

private const val DAY_ONE = 1717977600000L // 2024-06-10 00:00 UTC

class AnalyticsScreenTest : UiTestBase() {

    private lateinit var fakeDb: FakeAppDatabase

    @Before
    override fun setUp() {
        Dispatchers.setMain(testDispatcher)
        val context = ApplicationProvider.getApplicationContext<Application>()
        fakeDb = FakeAppDatabase()
        viewModel = SwarmViewModel(
            application = context,
            ollamaService = FakeOllamaService(),
            mcpClient = FakeMcpClient(),
            registryClient = FakeMcpRegistryClient(),
            dispatcher = testDispatcher,
            database = fakeDb,
            securePrefs = FakeSecurePrefs()
        )
    }

    @Config(qualifiers = "w360dp-h6000dp")
    @Test
    fun analyticsScreen_showsAggregateSummaryAndPerConfigBreakdown() = runUiTest {
        // Seed tasks matched to the default seeded swarm configs.
        (1..3).forEach { i ->
            fakeDb.swarmTaskDao().insertTask(
                SwarmTask(
                    prompt = "spec $i",
                    status = "Completed",
                    swarmName = "SDLC Spec & Design Swarm",
                    executionTimeMs = (i * 1000L),
                    tokenUsage = (i * 100),
                    timestamp = DAY_ONE + i * 100_000L
                )
            )
        }
        fakeDb.swarmTaskDao().insertTask(
            SwarmTask(
                prompt = "feature ok",
                status = "Completed",
                swarmName = "Feature Implementation Swarm",
                executionTimeMs = 5000L,
                tokenUsage = 500,
                timestamp = DAY_ONE + 400_000L
            )
        )
        fakeDb.swarmTaskDao().insertTask(
            SwarmTask(
                prompt = "feature fail",
                status = "Failed",
                swarmName = "Feature Implementation Swarm",
                executionTimeMs = 0L,
                tokenUsage = 0,
                timestamp = DAY_ONE + 500_000L
            )
        )

        setContent { AnalyticsScreen(viewModel = viewModel) }

        advanceUntilIdle()
        composeRule.onNodeWithTag("analytics_screen").assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_total_tasks_card").assertIsDisplayed()
        composeRule.onNodeWithTag("analytics_swarm_breakdown_card").assertIsDisplayed()
        composeRule
            .onNodeWithText("SDLC Spec & Design Swarm", substring = true)
            .assertIsDisplayed()
        composeRule
            .onNodeWithText("Feature Implementation Swarm", substring = true)
            .assertIsDisplayed()
    }
}
