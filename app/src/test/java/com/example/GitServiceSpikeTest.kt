package com.example

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Spike: confirms JGit can init a repo, stage a file, and commit on this toolchain
 * before building the real GitService around it.
 */
class GitServiceSpikeTest {
    @Test
    fun initAddCommit_producesRealCommit() {
        val workDir = Files.createTempDirectory("jgit_spike").toFile()
        try {
            Git.init().setDirectory(workDir).call().use { git ->
                File(workDir, "hello.txt").writeText("hello from jgit spike")
                git.add().addFilepattern(".").call()
                val commit = git.commit()
                    .setAuthor("Spike Test", "spike@example.com")
                    .setMessage("spike: initial commit")
                    .call()

                assertTrue(commit.id.name.isNotBlank())
                assertEquals("spike: initial commit", commit.fullMessage)

                val log = git.log().call().toList()
                assertEquals(1, log.size)
            }
        } finally {
            workDir.deleteRecursively()
        }
    }
}
