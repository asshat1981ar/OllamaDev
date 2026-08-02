package com.example.data

import android.content.Context
import com.example.BuildConfig
import com.mixpanel.android.mpmetrics.MixpanelAPI
import org.json.JSONObject

/**
 * Thin wrapper around the Mixpanel SDK. This app has no user-account system, so events are
 * tracked against Mixpanel's anonymous, device-based distinct_id -- there is deliberately no
 * identify()/people.set() call anywhere here (Mixpanel's own guidance: don't build User
 * Profiles for anonymous users).
 *
 * Tracking defaults to on but is gated by [KEY_ENABLED] in SharedPreferences so it can be
 * switched off (Settings screen, support request, etc.) without a rebuild. EU/CA user status
 * for this app was unconfirmed at integration time -- if that changes, wire a real consent UI
 * to [setEnabled] before shipping.
 */
object AnalyticsTracker {
    private const val PREFS_NAME = "analytics_prefs"
    private const val KEY_ENABLED = "analytics_enabled"

    @Volatile
    private var mixpanel: MixpanelAPI? = null

    fun init(context: Context) {
        if (!isEnabled(context)) return
        mixpanel = MixpanelAPI.getInstance(context.applicationContext, BuildConfig.MIXPANEL_PROJECT_TOKEN, false)
    }

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (enabled) {
            if (mixpanel == null) init(context)
        } else {
            mixpanel?.flush()
            mixpanel = null
        }
    }

    fun track(event: String, properties: Map<String, Any> = emptyMap()) {
        val mp = mixpanel ?: return
        if (properties.isEmpty()) mp.track(event) else mp.track(event, JSONObject(properties))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
