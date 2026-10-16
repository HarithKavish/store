package com.harithkavish.store.ui

import android.text.Editable
import android.text.TextWatcher
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.harithkavish.store.R
import kotlinx.coroutines.Job

/** Which apps the list shows. */
enum class Filter { ALL, UPDATES, INSTALLED }

/**
 * The title, search box and filter chips. It is one item that is never rebound, so the search
 * box keeps its focus and cursor while the list below it changes on every keystroke.
 */
class HeaderAdapter(
    private val onQuery: (String) -> Unit,
    private val onFilter: (Filter) -> Unit
) : RecyclerView.Adapter<HeaderAdapter.ViewHolder>() {

    private var filter = Filter.ALL
    private var updateCount = 0
    private var query = ""
    private var holder: ViewHolder? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val search: EditText = view.findViewById(R.id.searchField)
        val chips: Map<Filter, TextView> = mapOf(
            Filter.ALL to view.findViewById(R.id.chipAll),
            Filter.UPDATES to view.findViewById(R.id.chipUpdates),
            Filter.INSTALLED to view.findViewById(R.id.chipInstalled)
        )
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val h = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_header, parent, false))
        // If RecyclerView ever recreates the header, it comes back with the search still in it.
        h.search.setText(query)
        h.search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString().orEmpty()
                onQuery(query)
            }
        })
        h.chips.forEach { (f, chip) -> chip.setOnClickListener { onFilter(f) } }
        holder = h
        return h
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = applyChips(holder)

    /** Updates the chips in place: notifying a change would rebind and drop the search focus. */
    fun setState(filter: Filter, updateCount: Int) {
        this.filter = filter
        this.updateCount = updateCount
        holder?.let { applyChips(it) }
    }

    private fun applyChips(h: ViewHolder) {
        val context = h.itemView.context
        h.chips.forEach { (f, chip) -> chip.isSelected = f == filter }
        h.chips.getValue(Filter.UPDATES).text =
            if (updateCount > 0) context.getString(R.string.filter_updates_count, updateCount)
            else context.getString(R.string.filter_updates)
    }
}

/** "N updates available · Update all", shown only while there are updates. */
class UpdatesBannerAdapter(private val onUpdateAll: () -> Unit) :
    RecyclerView.Adapter<UpdatesBannerAdapter.ViewHolder>() {

    private var updates: List<AppRowState> = emptyList()
    /** Set while Update all runs: "2 of 3…", and the button cannot be pressed again. */
    private var progress: String? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.bannerTitle)
        val subtitle: TextView = view.findViewById(R.id.bannerSubtitle)
        val action: TextView = view.findViewById(R.id.bannerAction)
    }

    fun submit(updates: List<AppRowState>, progress: String?) {
        val wasShown = itemCount == 1
        this.updates = updates
        this.progress = progress
        val shown = itemCount == 1
        when {
            wasShown && shown -> notifyItemChanged(0)
            wasShown -> notifyItemRemoved(0)
            shown -> notifyItemInserted(0)
        }
    }

    override fun getItemCount() = if (updates.isNotEmpty() || progress != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_updates_banner, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val res = holder.itemView.resources
        holder.title.text = res.getQuantityString(R.plurals.updates_available, updates.size, updates.size)
        holder.subtitle.text = updates.joinToString(", ") { it.app.name }
        holder.action.text = progress ?: res.getString(R.string.update_all)
        holder.action.isEnabled = progress == null
        holder.action.alpha = if (progress == null) 1f else 0.7f
        holder.action.setOnClickListener { if (progress == null) onUpdateAll() }
    }
}

/** The website's featured-app hero: big icon, name, tagline, one wide button. */
class FeaturedAdapter(
    private val icons: IconLoader,
    private val onAction: (AppRowState) -> Unit
) : RecyclerView.Adapter<FeaturedAdapter.ViewHolder>() {

    private var row: AppRowState? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.featuredIcon)
        val name: TextView = view.findViewById(R.id.featuredName)
        val tagline: TextView = view.findViewById(R.id.featuredTagline)
        val action: TextView = view.findViewById(R.id.featuredAction)
        val meta: TextView = view.findViewById(R.id.featuredMeta)
        var iconJob: Job? = null
    }

    fun submit(row: AppRowState?) {
        val was = this.row
        this.row = row
        when {
            was != null && row != null -> if (was != row) notifyItemChanged(0)
            was != null -> notifyItemRemoved(0)
            row != null -> notifyItemInserted(0)
        }
    }

    override fun getItemCount() = if (row != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_featured, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = row ?: return
        val context = holder.itemView.context
        holder.name.text = row.app.name
        holder.tagline.text = row.app.tagline
        ActionButtons.style(holder.action, row.displayStatus())
        holder.action.setOnClickListener { onAction(row) }
        val manifest = row.manifest
        holder.meta.text = if (manifest == null) "" else context.getString(
            R.string.meta_featured, manifest.version,
            if (manifest.sizeBytes > 0) Formatter.formatShortFileSize(context, manifest.sizeBytes) else "—"
        )
        holder.iconJob = icons.load(holder.icon, row.app.iconUrl, holder.iconJob)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        holder.iconJob?.cancel()
    }
}

/** A small uppercase label above the cards: "All apps", "Updates available", "Installed". */
class SectionAdapter : RecyclerView.Adapter<SectionAdapter.ViewHolder>() {

    private var title: Int? = null

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.sectionTitle)
    }

    fun submit(title: Int?) {
        val was = this.title
        this.title = title
        when {
            was != null && title != null -> if (was != title) notifyItemChanged(0)
            was != null -> notifyItemRemoved(0)
            title != null -> notifyItemInserted(0)
        }
    }

    override fun getItemCount() = if (title != null) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_section, parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        title?.let { holder.title.setText(it) }
    }
}
