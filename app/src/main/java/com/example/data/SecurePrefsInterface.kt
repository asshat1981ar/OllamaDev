package com.example.data

import android.content.Context

/** Seam over [SecurePrefs] so tests can avoid real AndroidX Security/Keystore crypto. */
interface SecurePrefsInterface {
    fun getGitToken(): String?
    fun setGitToken(token: String)
    fun getMcpToken(serverId: Int): String?
    fun setMcpToken(serverId: Int, token: String)
    fun clearMcpToken(serverId: Int)
}

class RealSecurePrefs(private val context: Context) : SecurePrefsInterface {
    override fun getGitToken(): String? = SecurePrefs.getGitToken(context)
    override fun setGitToken(token: String) = SecurePrefs.setGitToken(context, token)
    override fun getMcpToken(serverId: Int): String? = SecurePrefs.getMcpToken(context, serverId)
    override fun setMcpToken(serverId: Int, token: String) = SecurePrefs.setMcpToken(context, serverId, token)
    override fun clearMcpToken(serverId: Int) = SecurePrefs.clearMcpToken(context, serverId)
}
