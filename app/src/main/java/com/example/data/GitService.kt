package com.example.data

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.eclipse.jgit.transport.RemoteRefUpdate
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import java.io.File

sealed class GitOpResult {
    data class Success(val message: String = "") : GitOpResult()
    data class Failure(val error: String) : GitOpResult()
}

data class GitCommitResult(
    val hash: String,
    val author: String,
    val message: String,
    val timestamp: Long
)

/**
 * Wraps a real JGit repository rooted at [workDir]. The app has no resolvable real filesystem
 * path for workspace files (pure Room rows or SAF content:// URIs), so callers mirror the
 * current WorkspaceFile set into [workDir] via [mirrorFiles] before committing.
 */
class GitService(private val workDir: File) {

    init {
        workDir.mkdirs()
    }

    private fun hasRepo(): Boolean = File(workDir, ".git").exists()

    private fun openOrInitGit(): Git {
        return if (hasRepo()) Git.open(workDir) else Git.init().setDirectory(workDir).call()
    }

    /** Writes every file's content into [workDir], removing anything no longer present. */
    fun mirrorFiles(files: List<WorkspaceFile>) {
        val wantedPaths = files.map { it.filePath.replace('\\', '/') }.toSet()

        fun pruneStrayFiles(dir: File, base: String) {
            dir.listFiles()?.forEach { child ->
                if (base.isEmpty() && child.name == ".git") return@forEach
                val relPath = if (base.isEmpty()) child.name else "$base/${child.name}"
                if (child.isDirectory) {
                    pruneStrayFiles(child, relPath)
                    if (child.listFiles()?.isEmpty() == true) child.delete()
                } else if (relPath !in wantedPaths) {
                    child.delete()
                }
            }
        }
        pruneStrayFiles(workDir, "")

        files.forEach { file ->
            val target = File(workDir, file.filePath)
            target.parentFile?.mkdirs()
            target.writeText(file.content)
        }
    }

    /** Stages all adds/modifications/deletions and commits. Returns null result on failure. */
    fun commitAll(authorName: String, authorEmail: String, message: String): Pair<GitCommitResult?, GitOpResult> {
        return try {
            openOrInitGit().use { git ->
                git.add().addFilepattern(".").call()
                git.add().setUpdate(true).addFilepattern(".").call() // stages deletions

                if (git.status().call().isClean) {
                    return null to GitOpResult.Failure("No changes to commit.")
                }

                val commit = git.commit()
                    .setAuthor(authorName, authorEmail)
                    .setMessage(message)
                    .call()

                val result = GitCommitResult(
                    hash = commit.id.abbreviate(8).name(),
                    author = authorName,
                    message = message,
                    timestamp = System.currentTimeMillis()
                )
                result to GitOpResult.Success()
            }
        } catch (e: Exception) {
            null to GitOpResult.Failure(e.localizedMessage ?: "Unknown git error")
        }
    }

    /** Pushes the current branch to [remoteUrl] over HTTPS using a PAT as the password. */
    fun push(remoteUrl: String, token: String): GitOpResult {
        if (!hasRepo()) return GitOpResult.Failure("No local commits yet — commit before pushing.")
        return try {
            openOrInitGit().use { git ->
                val branch = git.repository.branch ?: "master"
                val refSpec = RefSpec("refs/heads/$branch:refs/heads/$branch")

                val pushResults = git.push()
                    .setRemote(remoteUrl)
                    .setRefSpecs(refSpec)
                    .setCredentialsProvider(UsernamePasswordCredentialsProvider("token", token))
                    .call()

                val failures = mutableListOf<String>()
                pushResults.forEach { pr ->
                    pr.remoteUpdates.forEach { update ->
                        if (update.status != RemoteRefUpdate.Status.OK &&
                            update.status != RemoteRefUpdate.Status.UP_TO_DATE
                        ) {
                            failures += "${update.status}${update.message?.let { ": $it" } ?: ""}"
                        }
                    }
                }

                if (failures.isNotEmpty()) {
                    GitOpResult.Failure(failures.joinToString("; "))
                } else {
                    GitOpResult.Success()
                }
            }
        } catch (e: Exception) {
            GitOpResult.Failure(e.localizedMessage ?: "Unknown push error")
        }
    }

    fun isClean(): Boolean {
        if (!hasRepo()) return true
        return try {
            openOrInitGit().use { git -> git.status().call().isClean }
        } catch (e: Exception) {
            false
        }
    }

    fun localHeadHash(): String? {
        if (!hasRepo()) return null
        return try {
            openOrInitGit().use { git -> git.repository.resolve("HEAD")?.name }
        } catch (e: Exception) {
            null
        }
    }

    /** Hard-resets [workDir] to [commitHash], discarding any uncommitted changes -- a local,
     *  destructive rollback. Callers must reconcile WorkspaceFile rows against [readWorkDirFiles]
     *  afterward, since this only touches the real filesystem mirror, not the Room-backed rows. */
    fun revertToCommit(commitHash: String): GitOpResult {
        if (!hasRepo()) return GitOpResult.Failure("No local repository to revert.")
        return try {
            openOrInitGit().use { git ->
                git.reset().setMode(org.eclipse.jgit.api.ResetCommand.ResetType.HARD).setRef(commitHash).call()
                GitOpResult.Success("Reverted to $commitHash")
            }
        } catch (e: Exception) {
            GitOpResult.Failure(e.localizedMessage ?: "Unknown revert error")
        }
    }

    /** Walks [workDir] (excluding `.git`) and returns every file as a relative-path/content pair,
     *  mirroring [mirrorFiles]'s path convention in reverse -- used after [revertToCommit] to
     *  reconcile WorkspaceFile rows with the reverted-to filesystem state. */
    fun readWorkDirFiles(): List<Pair<String, String>> {
        val results = mutableListOf<Pair<String, String>>()
        fun walk(dir: File, base: String) {
            dir.listFiles()?.forEach { child ->
                if (base.isEmpty() && child.name == ".git") return@forEach
                val relPath = if (base.isEmpty()) child.name else "$base/${child.name}"
                if (child.isDirectory) {
                    walk(child, relPath)
                } else {
                    results += relPath to child.readText()
                }
            }
        }
        walk(workDir, "")
        return results
    }
}
