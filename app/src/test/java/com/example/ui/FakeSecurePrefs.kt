package com.example.ui

import com.example.data.SecurePrefsInterface

/**
 * In-memory stand-in for [com.example.data.SecurePrefs]. AndroidX Security's real
 * MasterKey/EncryptedSharedPreferences requires genuine AndroidKeyStore crypto, which is
 * unreliable under Robolectric on this device (intermittent `NoSuchAlgorithmException`/
 * `KeyStoreException`) -- tests must never touch it.
 */
class FakeSecurePrefs : SecurePrefsInterface {
    private var gitToken: String? = null
    private val mcpTokens = mutableMapOf<Int, String>()

    override fun getGitToken(): String? = gitToken
    override fun setGitToken(token: String) {
        gitToken = token
    }

    override fun getMcpToken(serverId: Int): String? = mcpTokens[serverId]
    override fun setMcpToken(serverId: Int, token: String) {
        mcpTokens[serverId] = token
    }

    override fun clearMcpToken(serverId: Int) {
        mcpTokens.remove(serverId)
    }
}
