package com.example.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted local storage for secrets that must not live in a plain Room column or plain
 * SharedPreferences: the git remote PAT and per-server MCP auth tokens.
 */
object SecurePrefs {
    private const val PREFS_NAME = "secure_git_mcp_prefs"
    private const val KEY_GIT_TOKEN = "git_remote_token"
    private const val KEY_MCP_TOKEN_PREFIX = "mcp_token_"

    @Volatile
    private var cached: SharedPreferences? = null

    private fun prefs(context: Context): SharedPreferences {
        return cached ?: synchronized(this) {
            cached ?: buildPrefs(context).also { cached = it }
        }
    }

    private fun buildPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context.applicationContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context.applicationContext,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getGitToken(context: Context): String? = prefs(context).getString(KEY_GIT_TOKEN, null)

    fun setGitToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_GIT_TOKEN, token).apply()
    }

    fun getMcpToken(context: Context, serverId: Int): String? =
        prefs(context).getString("$KEY_MCP_TOKEN_PREFIX$serverId", null)

    fun setMcpToken(context: Context, serverId: Int, token: String) {
        prefs(context).edit().putString("$KEY_MCP_TOKEN_PREFIX$serverId", token).apply()
    }

    fun clearMcpToken(context: Context, serverId: Int) {
        prefs(context).edit().remove("$KEY_MCP_TOKEN_PREFIX$serverId").apply()
    }
}
