package com.harithkavish.store.ui

import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harithkavish.store.R
import kotlinx.coroutines.Job

/**
 * What the button and status line should say. UNKNOWN (no manifest yet) for an app that is
 * installed still opens it, so it is shown as up to date rather than as "Soon".
 */
fun AppRowState.displayStatus(): AppStatus =
    if (status == AppStatus.UNKNOWN && installedVersion != null) AppStatus.UP_TO_DATE else status

/** The app cards. */
class AppAdapter(
    private val icons: IconLoader,
    private val onAction: (AppRowState) -> Unit
) : ListAdapter<AppRowState, AppAdapter.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<AppRowState>() {
        override fun areItemsTheSame(old: AppRowState, new: AppRowState) = old.app.slug == new.app.slug
        override fun areContentsTheSame(old: AppRowState, new: AppRowState) = old == new
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val tagline: TextView = view.findViewById(R.id.appTagline)
        val meta: TextView = view.findViewById(R.id.appMeta)
        val action: TextView = view.findViewById(R.id.appAction)
        val category: TextView = view.findViewById(R.id.appCategory)
        val status: TextView = view.findViewById(R.id.appStatus)
        val notes: TextView = view.findViewById(R.id.appNotes)
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = getItem(position)
        val context = holder.itemView.context
        val status = row.displayStatus()

        holder.name.text = row.app.name
        holder.tagline.text = row.app.tagline
        holder.meta.text = metaLine(row, context)
        holder.category.text = row.app.category
        holder.category.visibility = if (row.app.category.isBlank()) View.GONE else View.VISIBLE

        ActionButtons.style(holder.action, status)
        holder.action.setOnClickListener { onAction(row) }
        holder.itemView.setOnClickListener(if (status == AppStatus.UP_TO_DATE) View.OnClickListener { onAction(row) } else null)

        // An update edges the card in blue and says what it is from and to, plus what changed.
        holder.itemView.setBackgroundResource(
            if (status == AppStatus.UPDATE_AVAILABLE) R.drawable.bg_card_update else R.drawable.bg_card
        )
        when (status) {
            AppStatus.UPDATE_AVAILABLE -> setStatus(holder.status,
                context.getString(R.string.status_update, row.installedVersion, row.manifest?.version), R.color.update, false)
            AppStatus.UP_TO_DATE -> setStatus(holder.status,
                context.getString(R.string.status_installed, row.installedVersion), R.color.installed, true)
            AppStatus.DOWNLOADING -> setStatus(holder.status,
                context.getString(R.string.status_downloading, row.manifest?.version ?: ""), R.color.text_muted, false)
            AppStatus.UNKNOWN -> setStatus(holder.status, context.getString(R.string.status_no_build), R.color.text_faint, false)
            AppStatus.NOT_INSTALLED -> setStatus(holder.status, "", R.color.text_muted, false)
        }
        val notes = row.manifest?.releaseNotes
        if (status == AppStatus.UPDATE_AVAILABLE && !notes.isNullOrBlank()) {
            holder.notes.text = context.getString(R.string.whats_new, notes)
            holder.notes.visibility = View.VISIBLE
        } else {
            holder.notes.visibility = View.GONE
        }

        holder.iconJob = icons.load(holder.icon, row.app.iconUrl, holder.iconJob)
    }

    private fun setStatus(view: TextView, text: String, color: Int, dot: Boolean) {
        view.text = text
        view.setTextColor(view.context.getColor(color))
        view.setCompoundDrawablesRelativeWithIntrinsicBounds(if (dot) R.drawable.dot_installed else 0, 0, 0, 0)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
    }

    companion object {
        /** "v1.0.0 · 221 KB", or just the size or version if one is missing. */
        fun metaLine(row: AppRowState, context: android.content.Context): String {
            val manifest = row.manifest ?: return ""
            val size = if (manifest.sizeBytes > 0) Formatter.formatShortFileSize(context, manifest.sizeBytes) else ""
            return if (size.isEmpty()) "v${manifest.version}"
            else context.getString(R.string.meta_version_size, manifest.version, size)
        }
    }
}
