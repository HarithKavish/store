package com.harithkavish.store.ui

import android.widget.TextView
import com.harithkavish.store.R

/**
 * One pill per state, so the action reads at a glance:
 *   Get       -- the website's light pill;
 *   Update    -- the ecosystem's icon blue, the one thing on the screen asking to be pressed;
 *   Open      -- an outline, since the app is already there;
 *   Downloading / Soon -- greyed, not pressable.
 */
object ActionButtons {

    fun style(button: TextView, status: AppStatus) {
        val context = button.context
        val (text, background, textColor, enabled) = when (status) {
            AppStatus.NOT_INSTALLED -> Style(R.string.action_get, R.drawable.pill_accent, R.color.on_accent, true)
            AppStatus.UPDATE_AVAILABLE -> Style(R.string.action_update, R.drawable.pill_update, R.color.on_update, true)
            AppStatus.UP_TO_DATE -> Style(R.string.action_open, R.drawable.pill_outline, R.color.text, true)
            AppStatus.DOWNLOADING -> Style(R.string.action_downloading, R.drawable.pill_disabled, R.color.text_muted, false)
            AppStatus.UNKNOWN -> Style(R.string.action_soon, R.drawable.pill_disabled, R.color.text_faint, false)
        }
        button.text = context.getString(text)
        button.setBackgroundResource(background)
        button.setTextColor(context.getColor(textColor))
        button.isEnabled = enabled
        button.isClickable = enabled
    }

    private data class Style(val text: Int, val background: Int, val textColor: Int, val enabled: Boolean)
}
