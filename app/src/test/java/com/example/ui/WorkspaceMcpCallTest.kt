package com.example.ui

import com.example.data.McpServer
import com.example.viewmodel.SwarmViewModel
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.annotation.Config

@Config(qualifiers = "w360dp-h6000dp")
class WorkspaceMcpCallTest : UiTestBase() {

    private val fakeMcpClient: FakeMcpClient
        get() = SwarmViewModel::class.java
            .getDeclaredField("mcpClient")
            .apply { isAccessible = true }
            .get(viewModel) as FakeMcpClient

    @Test
    fun loadWorkspaceFiles_parsesFileListAndAppliesQueryFilter() = runUiTest {
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/build.gradle.kts\", \"README.md\", \"docs/setup.md\"]"

        viewModel.loadWorkspaceFiles(serverId = 7, query = "README")
        advanceUntilIdle()

        assertEquals(listOf("README.md"), viewModel.workspaceBrowserFiles.value)
        assertTrue(fakeMcpClient.initializedUrls.contains("http://localhost:5000/mcp"))
    }

    @Test
    fun readWorkspaceFile_loadsContentIntoState() = runUiTest {
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = "package com.example"

        viewModel.readWorkspaceFile(serverId = 7, path = "app/src/Main.kt")
        advanceUntilIdle()

        assertEquals("app/src/Main.kt", viewModel.selectedWorkspaceFile.value)
        assertEquals("package com.example", viewModel.workspaceFileContent.value)
    }

    @Test
    fun saveWorkspaceFile_reportsVoiceFeedbackOnSuccess() = runUiTest {
        fakeMcpClient.scriptedToolResults["write_workspace_file"] = "OK"

        viewModel.saveWorkspaceFile(serverId = 7, path = "app/src/Main.kt", content = "new content")
        advanceUntilIdle()

        assertEquals("new content", viewModel.workspaceFileContent.value)
        assertEquals("Saved app/src/Main.kt", viewModel.voiceFeedback.value)
    }

    @Test
    fun createWorkspaceFile_createsAndSelectsNewFile() = runUiTest {
        fakeMcpClient.scriptedToolResults["write_workspace_file"] = "OK"
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"app/src/New.kt\"]"
        fakeMcpClient.scriptedToolResults["read_workspace_file"] = "package com.example"

        viewModel.createWorkspaceFile(serverId = 7, path = "app/src/New.kt")
        advanceUntilIdle()

        assertEquals("Created app/src/New.kt", viewModel.voiceFeedback.value)
        assertEquals("app/src/New.kt", viewModel.selectedWorkspaceFile.value)
        assertEquals("package com.example", viewModel.workspaceFileContent.value)
    }

    @Test
    fun deleteWorkspaceFile_removesFileAndReloadsList() = runUiTest {
        fakeMcpClient.scriptedToolResults["delete_workspace_file"] = "Deleted"
        fakeMcpClient.scriptedToolResults["list_workspace_files"] = "[\"README.md\"]"

        viewModel.readWorkspaceFile(serverId = 7, path = "app/src/Old.kt")
        advanceUntilIdle()
        assertEquals("app/src/Old.kt", viewModel.selectedWorkspaceFile.value)

        viewModel.deleteWorkspaceFile(serverId = 7, path = "app/src/Old.kt")
        advanceUntilIdle()

        assertEquals("Deleted app/src/Old.kt", viewModel.voiceFeedback.value)
        assertEquals(null, viewModel.selectedWorkspaceFile.value)
    }

    @Test
    fun loadWorkspaceFiles_skipsWhenServerNotConnected() = runUiTest {
        // Update server status to Disconnected
        viewModel.updateMcpServer(
            McpServer(
                id = 7,
                name = "OllamaDev Tools",
                type = "Filesystem",
                sourceUrl = "http://localhost:5000/mcp",
                status = "Disconnected",
                toolsCount = 0,
                configuredParams = "{}"
            )
        )
        advanceUntilIdle()

        viewModel.loadWorkspaceFiles(serverId = 7)
        advanceUntilIdle()

        assertTrue(viewModel.workspaceBrowserFiles.value.isEmpty())
        assertEquals("Server is not connected", viewModel.workspaceError.value)
    }
}
