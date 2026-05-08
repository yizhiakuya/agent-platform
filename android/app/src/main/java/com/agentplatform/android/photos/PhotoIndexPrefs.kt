package com.agentplatform.android.photos

import android.content.Context

internal class PhotoIndexPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    var lastIndexedModifiedSec: Long
        get() = prefs.getLong(KEY_LAST_INDEXED_MODIFIED_SEC, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_INDEXED_MODIFIED_SEC, value.coerceAtLeast(0L)).apply() }

    var lastIndexedIdAtModified: Long
        get() = prefs.getLong(KEY_LAST_INDEXED_ID_AT_MODIFIED, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_INDEXED_ID_AT_MODIFIED, value.coerceAtLeast(0L)).apply() }

    var lastRunMs: Long
        get() = prefs.getLong(KEY_LAST_RUN_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_RUN_MS, value.coerceAtLeast(0L)).apply() }

    var lastReconcileMs: Long
        get() = prefs.getLong(KEY_LAST_RECONCILE_MS, 0L)
        set(value) { prefs.edit().putLong(KEY_LAST_RECONCILE_MS, value.coerceAtLeast(0L)).apply() }

    companion object {
        private const val NAME = "photo.index.prefs"
        private const val KEY_LAST_INDEXED_MODIFIED_SEC = "lastIndexedModifiedSec"
        private const val KEY_LAST_INDEXED_ID_AT_MODIFIED = "lastIndexedIdAtModified"
        private const val KEY_LAST_RUN_MS = "lastRunMs"
        private const val KEY_LAST_RECONCILE_MS = "lastReconcileMs"
    }
}
