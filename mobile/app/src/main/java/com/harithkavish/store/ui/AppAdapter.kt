package com.harithkavish.store.ui

import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.harithkavish.store.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.net.URL

class AppAdapter(
    private val scope: CoroutineScope,
    private val onAction: (AppRowState) -> Unit
) : ListAdapter<AppRowState, AppAdapter.ViewHolder>(Diff) {

    private object Diff : DiffUtil.ItemCallback<AppRowState>() {
        override fun areItemsTheSame(old: AppRowState, new: AppRowState) = old.app.slug == new.app.slug
        override fun areContentsTheSame(old: AppRowState, new: AppRowState) = old == new
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
        val tagline: TextView = view.findViewById(R.id.appTagline)
        val status: TextView = view.findViewById(R.id.appStatus)
        val action: android.widget.Button = view.findViewById(R.id.appAction)
        var iconJob: Job? = null
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val state = getItem(position)
        val context = holder.itemView.context

        holder.name.text = state.app.name
        holder.tagline.text = state.app.tagline

        val (statusText, statusColor) = statusFor(state, context)
        holder.status.text = statusText
        holder.status.setTextColor(statusColor)

        holder.action.isEnabled = state.status != AppStatus.DOWNLOADING
        holder.action.text = when (state.status) {
            AppStatus.NOT_INSTALLED -> context.getString(R.string.action_install)
            AppStatus.UPDATE_AVAILABLE -> context.getString(R.string.action_update)
            AppStatus.DOWNLOADING -> context.getString(R.string.downloading, state.manifest?.version ?: "")
            AppStatus.UP_TO_DATE -> context.getString(R.string.action_open)
            AppStatus.UNKNOWN -> context.getString(R.string.action_open)
        }
        holder.action.setOnClickListener { onAction(state) }

        holder.icon.setImageDrawable(null)
        holder.iconJob?.cancel()
        val iconUrl = state.app.iconUrl
        if (iconUrl != null) {
            holder.iconJob = scope.launch {
                val bitmap = withDispatcher { runCatching { BitmapFactory.decodeStream(URL(iconUrl).openStream()) }.getOrNull() }
                if (bitmap != null && holder.bindingAdapterPosition == position) {
                    holder.icon.setImageBitmap(bitmap)
                }
            }
        }
    }

    private suspend fun <T> withDispatcher(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.IO) { block() }

    private fun statusFor(state: AppRowState, context: android.content.Context): Pair<String, Int> = when (state.status) {
        AppStatus.NOT_INSTALLED -> context.getString(R.string.status_not_installed) to
            context.getColor(R.color.status_missing)
        AppStatus.UPDATE_AVAILABLE -> context.getString(
            R.string.status_update_available,
            state.installedVersion,
            state.manifest?.version
        ) to context.getColor(R.color.status_update)
        AppStatus.UP_TO_DATE -> context.getString(R.string.status_up_to_date, state.installedVersion) to
            context.getColor(R.color.status_current)
        AppStatus.DOWNLOADING -> context.getString(R.string.downloading, state.manifest?.version ?: "") to
            context.getColor(R.color.status_missing)
        AppStatus.UNKNOWN -> context.getString(R.string.status_unknown) to
            context.getColor(R.color.status_missing)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
    }
}
