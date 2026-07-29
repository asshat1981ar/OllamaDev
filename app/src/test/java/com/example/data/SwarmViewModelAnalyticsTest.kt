package com.example.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.ui.FakeAppDatabase
import com.example.ui.FakeMcpClient
import com.example.ui.FakeMcpRegistryClient
import com.example.ui.FakeOllamaService
import com.example.ui.FakeSecurePrefs
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

private const val DAY_ONE = 1717977600000L // 2024-06-10 00:00 UTC
private const val DAY_TWO = 1718064000000L // 2024-06-11 00:00 UTC

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@ConscryptMode(ConscryptMode.Mode.OFF)
class SwarmViewModelAnalyticsTest {

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
    fun analyticsSummary_exposesUnresolvedRateAndTimeSeriesGrouping() = runTest(testDispatcher) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val db = FakeAppDatabase()
        seedAnalyticsData(db)

        val viewModel = SwarmViewModel(
            application = context,
            ollamaService = FakeOllamaService(),
            mcpClient = FakeMcpClient(),
            registryClient = FakeMcpRegistryClient(),
            dispatcher = testDispatcher,
            database = db,
            securePrefs = FakeSecurePrefs()
        )

        backgroundScope.launch { viewModel.analyticsSummary.collect { } }
        backgroundScope.launch { viewModel.analyticsTimeSeries.collect { } }
        backgroundScope.launch { viewModel.analyticsPerConfig.collect { } }
        advanceUntilIdle()

        val summary = viewModel.analyticsSummary.value
        assertEquals(7, summary.totalTasks)
        // 2 unresolved: one Completed task with [UNRESOLVED] in result + one Failed task
        assertEquals(2, summary.unresolvedCount)
        assertEquals(2f / 7f, summary.unresolvedRate, 0.001f)

        val series = viewModel.analyticsTimeSeries.value
        assertEquals(2, series.size)
        val totalInSeries = series.sumOf { it.taskCount }
        assertEquals(summary.totalTasks, totalInSeries)

        val perConfig = viewModel.analyticsPerConfig.value
        assertEquals(6, perConfig.size) // six seeded swarm configs
        val featureConfig = perConfig.first { it.configName == "Feature Implementation Swarm" }
        assertEquals(2, featureConfig.taskCount)
        assertEquals(1, featureConfig.unresolvedCount)
    }

    private fun seedAnalyticsData(db: FakeAppDatabase) {
        // Day one tasks
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d1 spec a", status = "Completed", swarmName = "SDLC Spec & Design Swarm", executionTimeMs = 1000L, tokenUsage = 100, timestamp = DAY_ONE + 1_000_000)
        )
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d1 spec b", status = "Completed", swarmName = "SDLC Spec & Design Swarm", executionTimeMs = 2000L, tokenUsage = 200, timestamp = DAY_ONE + 2_000_000)
        )
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d1 feature", status = "Completed", swarmName = "Feature Implementation Swarm", executionTimeMs = 5000L, tokenUsage = 500, timestamp = DAY_ONE + 3_000_000)
        )
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d1 harness unresolved", status = "Completed", swarmName = "Autonomous Coding Harness", executionTimeMs = 4000L, tokenUsage = 400, timestamp = DAY_ONE + 4_000_000, result = "[UNRESOLVED] security review needed")
        )

        // Day two tasks
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d2 spec", status = "Completed", swarmName = "SDLC Spec & Design Swarm", executionTimeMs = 1500L, tokenUsage = 150, timestamp = DAY_TWO + 1_000_000)
        )
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d2 feature failed", status = "Failed", swarmName = "Feature Implementation Swarm", executionTimeMs = 0L, tokenUsage = 0, timestamp = DAY_TWO + 2_000_000)
        )
        db.swarmTaskDao().insertTask(
            SwarmTask(prompt = "d2 harness clean", status = "Completed", swarmName = "Autonomous Coding Harness", executionTimeMs = 3500L, tokenUsage = 350, timestamp = DAY_TWO + 3_000_000)
        )
    }
}
