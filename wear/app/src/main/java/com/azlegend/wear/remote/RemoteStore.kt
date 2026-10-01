package com.azlegend.wear.remote

import android.content.Context
import androidx.core.content.edit

/** Remembers which desktop this watch is paired with, so the code is typed once. */
class RemoteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("remote", Context.MODE_PRIVATE)

    /** The paired desktop's code, or "" when unpaired. Anything that is not a code reads as unpaired. */
    var pairCode: String
        get() = prefs.getString(KEY_CODE, null).takeIf(PairCode::isValid)?.let(PairCode::normalize).orEmpty()
        set(value) = prefs.edit {
            if (PairCode.isValid(value)) putString(KEY_CODE, PairCode.normalize(value)) else remove(KEY_CODE)
        }

    private companion object {
        const val KEY_CODE = "pairCode"
    }
}
