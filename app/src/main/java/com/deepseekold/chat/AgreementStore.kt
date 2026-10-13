package com.deepseekold.chat

import android.content.Context

/** 记录用户是否已同意用户协议与隐私政策。 */
object AgreementStore {
    private const val PREFERENCES_NAME = "deepseek_old_agreement"
    private const val KEY_ACCEPTED = "agreement_accepted"

    fun isAccepted(context: Context): Boolean {
        return preferences(context).getBoolean(KEY_ACCEPTED, false)
    }

    fun accept(context: Context) {
        preferences(context).edit().putBoolean(KEY_ACCEPTED, true).apply()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )
}
