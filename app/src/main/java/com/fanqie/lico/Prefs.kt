package com.fanqie.lico

import android.content.Context

/** 轻量本地配置存储 */
object Prefs {
    private const val FILE = "xiaofanqie"
    private const val KEY_SAVE_DIR = "save_dir"

    private fun sp(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getSaveDir(c: Context): String? = sp(c).getString(KEY_SAVE_DIR, null)

    fun setSaveDir(c: Context, dir: String) {
        sp(c).edit().putString(KEY_SAVE_DIR, dir).apply()
    }
}