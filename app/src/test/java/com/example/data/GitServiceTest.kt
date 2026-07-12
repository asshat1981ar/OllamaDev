package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GitServiceTest {

    private fun newService(): Pair<GitService, File> {
        val dir = Files.createTempDirectory("git_service_test").toFile()
        return GitService(dir) to dir
    }

    @Test
    fun commitAll_withNoChanges_reportsFailureNotFakeSuccess() {
        val (service, dir) = newService()
        try {
            val (result, status) = service.commitAll("Tester", "tester@example.com", "empty commit")
            assertNull(result)
            assertTrue(status is GitOpResult.Failure)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mirrorFiles_thenCommit_producesRealCommitAndCleanTree() {
        val (service, dir) = newService()
        try {
            service.mirrorFiles(
                listOf(
                    WorkspaceFile(filePath = "a.txt", content = "hello"),
                    WorkspaceFile(filePath = "nested/b.txt", content = "world")
                )
            )
            val (result, status) = service.commitAll("Tester", "tester@example.com", "initial import")

            assertTrue(status is GitOpResult.Success)
            assertNotNull(result)
            assertEquals("initial import", result!!.message)
            assertTrue(service.isClean())
            assertEquals(service.localHeadHash()?.take(8), result.hash)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun mirrorFiles_removingAFile_stagesDeletionOnNextCommit() {
        val (service, dir) = newService()
        try {
            service.mirrorFiles(listOf(WorkspaceFile(filePath = "a.txt", content = "hello")))
            service.commitAll("Tester", "tester@example.com", "add a.txt")

            service.mirrorFiles(emptyList())
            val (result, status) = service.commitAll("Tester", "tester@example.com", "remove a.txt")

            assertTrue(status is GitOpResult.Success)
            assertNotNull(result)
            assertTrue(!File(dir, "a.txt").exists())
            assertTrue(service.isClean())
        } finally {
            dir.deleteRecursively()
        }
    }
}
