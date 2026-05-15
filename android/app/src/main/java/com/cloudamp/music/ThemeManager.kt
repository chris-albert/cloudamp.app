package com.cloudamp.music

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.TypedValue

object ThemeManager {

    private const val PREFS_NAME = "cloudamp_theme"
    private const val KEY_THEME = "selected_theme"

    const val THEME_CLASSIC = "classic"
    const val THEME_DARK_SIDE = "dark_side"
    const val THEME_VAPOR = "vapor"
    const val THEME_NUCLEAR = "nuclear"

    private val THEME_MAP = mapOf(
        THEME_CLASSIC to R.style.Theme_CloudAmp,
        THEME_DARK_SIDE to R.style.Theme_CloudAmp_DarkSide,
        THEME_VAPOR to R.style.Theme_CloudAmp_Vapor,
        THEME_NUCLEAR to R.style.Theme_CloudAmp_Nuclear,
    )

    val THEME_LABELS = listOf(
        THEME_CLASSIC to "Classic Green",
        THEME_DARK_SIDE to "Dark Side",
        THEME_VAPOR to "Vaporwave",
        THEME_NUCLEAR to "Nuclear Orange",
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getThemeId(context: Context): String =
        prefs(context).getString(KEY_THEME, THEME_CLASSIC) ?: THEME_CLASSIC

    fun setThemeId(context: Context, themeId: String) {
        prefs(context).edit().putString(KEY_THEME, themeId).apply()
    }

    /**
     * Call in onCreate BEFORE super.onCreate().
     * Returns the theme ID that was applied (store it to detect changes later).
     */
    fun applyTheme(activity: Activity): String {
        val themeId = getThemeId(activity)
        val resId = THEME_MAP[themeId] ?: R.style.Theme_CloudAmp
        activity.setTheme(resId)
        return themeId
    }

    /**
     * Call in onResume. If the persisted theme changed since onCreate,
     * recreate the activity so the new theme takes effect.
     */
    fun recreateIfThemeChanged(activity: Activity, appliedThemeId: String) {
        if (getThemeId(activity) != appliedThemeId) {
            activity.recreate()
        }
    }

    fun getStyleRes(context: Context): Int {
        val themeId = getThemeId(context)
        return THEME_MAP[themeId] ?: R.style.Theme_CloudAmp
    }

    /** Resolve a theme attribute (e.g. R.attr.caText) to a color int. */
    fun resolveColor(context: Context, attrRes: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attrRes, tv, true)
        return context.getColor(tv.resourceId)
    }
}
