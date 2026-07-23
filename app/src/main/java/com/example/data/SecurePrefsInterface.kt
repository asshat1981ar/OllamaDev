package com.example.data

import android.app.Application

/** Seam over [SecurePrefs] so tests can avoid real AndroidX Security/Keystore crypto. */
interface SecurePrefsInterface {
    fun getGitToken(): String?
    fun setGitToken(token: String)
    fun getMcpToken(serverId: Int): String?
    fun setMcpToken(serverId: Int, token: String)
    fun clearMcpToken(serverId: Int)
}

class RealSecurePrefs(private val application: Application) : SecurePrefsInterface {
    override fun getGitToken(): String? = SecurePrefs.getGitToken(application)
    override fun setGitToken(token: String) = SecurePrefs.setGitToken(application, token)
    override fun getMcpToken(serverId: Int): String? = SecurePrefs.getMcpToken(application, serverId)
    override fun setMcpToken(serverId: Int, token: String) = SecurePrefs.setMcpToken(application, serverId, token)
    override fun clearMcpToken(serverId: Int) = SecurePrefs.clearMcpToken(application, serverId)
}
