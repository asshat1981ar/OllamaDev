package com.example.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.GitService
import com.example.data.McpClient
import com.example.data.OllamaServiceDefault
import com.example.data.RealSecurePrefs
import com.example.data.SwarmConfig
import com.example.data.SwarmEngine
import com.example.data.SwarmTask
import com.example.ui.AGENTIC_LOOP_NOTIFICATION_ID
import com.example.ui.createAgenticNotificationChannel
import com.example.ui.buildAgenticLoopNotification
import com.example.ui.finalizeAgenticLoopNotification
import com.example.ui.updateAgenticLoopNotification
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps an agentic-loop swarm task alive when the user leaves the app.
 *
 * The service owns its own [SwarmEngine] instance and listens to the active task's steps via
 * Room, updating the persistent notification as the task progresses. When the task finishes or
 * fails, the notification is finalized and the service stops itself.
 *
 * Start with [startAgenticLoop] so the helper takes care of the foreground start rules on
 * different API levels and notification permission checks.
 */
class AgenticLoopService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeTaskId: Int = -1
    private var stepCollectorJob: Job? = null
    private var taskRunnerJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createAgenticNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val configId = intent?.getIntExtra(EXTRA_CONFIG_ID, -1) ?: -1
        val prompt = intent?.getStringExtra(EXTRA_PROMPT).orEmpty()
        val configName = intent?.getStringExtra(EXTRA_CONFIG_NAME).orEmpty()

        if (configId == -1 || prompt.isBlank()) {
            Log.w(TAG, "Started without configId/prompt; stopping.")
            stopForegroundAndSelf()
            return START_NOT_STICKY
        }

        startForegroundWithNotification(configName, prompt)
        runTask(configId, prompt)

        // If the system kills and restarts us, we don't have the original prompt intent data,
        // so non-sticky is the safest behavior.
        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification(configName: String, prompt: String) {
        val title = if (configName.isBlank()) "Agentic loop running" else "Running: $configName"
        val notification = buildAgenticLoopNotification(this, title, prompt.take(80))
        ServiceCompat.startForeground(
            this,
            AGENTIC_LOOP_NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    private fun runTask(configId: Int, prompt: String) {
        taskRunnerJob?.cancel()
        taskRunnerJob = serviceScope.launch {
            val db = AppDatabase.getDatabase(applicationContext)
            val config = db.swarmConfigDao().getSwarmConfigById(configId) ?: run {
                finalizeAgenticLoopNotification(applicationContext, false, "Swarm config not found.")
                stopForegroundAndSelf()
                return@launch
            }

            val engine = buildEngine(db)
            activeTaskId = engine.executeTask(
                config = config,
                userPrompt = prompt,
                onTaskCreated = { taskId ->
                    activeTaskId = taskId
                    startStepCollector(db, taskId)
                }
            )

            val task = db.swarmTaskDao().getTaskById(activeTaskId)
            val succeeded = task?.status == "Completed"
            val summary = task?.result?.take(120) ?: "Task finished."
            finalizeAgenticLoopNotification(applicationContext, succeeded, summary)
            stopForegroundAndSelf()
        }
    }

    private fun buildEngine(db: AppDatabase): SwarmEngine {
        val gitWorkDir = filesDir.resolve("git-service-${System.nanoTime()}")
        gitWorkDir.mkdirs()
        return SwarmEngine(
            db = db,
            gitService = GitService(gitWorkDir),
            mcpClient = McpClient(),
            appContext = applicationContext,
            securePrefs = RealSecurePrefs(applicationContext),
            ollamaService = OllamaServiceDefault,
            dispatcher = Dispatchers.IO
        )
    }

    private fun startStepCollector(db: AppDatabase, taskId: Int) {
        stepCollectorJob?.cancel()
        stepCollectorJob = serviceScope.launch {
            db.taskStepDao().getStepsForTask(taskId).collectLatest { steps ->
                val latest = steps.lastOrNull() ?: return@collectLatest
                val status = "${latest.agentName}: ${latest.actionType}"
                val detail = latest.content.take(80)
                updateAgenticLoopNotification(applicationContext, "$status — $detail")
            }
        }
    }

    private fun stopForegroundAndSelf() {
        stepCollectorJob?.cancel()
        taskRunnerJob?.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "AgenticLoopService"
        private const val EXTRA_CONFIG_ID = "config_id"
        private const val EXTRA_PROMPT = "prompt"
        private const val EXTRA_CONFIG_NAME = "config_name"

        /**
         * Starts a foreground service that executes [config] with [prompt] in the background.
         * On Android 13+ the caller is responsible for having obtained POST_NOTIFICATIONS
         * permission before calling this; otherwise the foreground start may throw.
         */
        fun startAgenticLoop(context: Context, config: SwarmConfig, prompt: String) {
            val intent = Intent(context, AgenticLoopService::class.java).apply {
                putExtra(EXTRA_CONFIG_ID, config.id)
                putExtra(EXTRA_PROMPT, prompt)
                putExtra(EXTRA_CONFIG_NAME, config.name)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
