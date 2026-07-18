// file: extensions/settings/ExtensionsTvFragment.kt
package com.streamflixreborn.streamflix.extensions.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extensions.db.InstalledExtensionEntity
import com.streamflixreborn.streamflix.extensions.manager.ExtensionUpdate
import com.streamflixreborn.streamflix.fragments.settings.SettingsListStyler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * TV (Leanback-style) fragment that lists installed extensions.
 *
 * Uses a standard RecyclerView with vertical LinearLayoutManager (aligned with
 * the existing TV fragment patterns in the project which use RecyclerView, not
 * BrowseFragment). Rows are styled via [SettingsListStyler] for visual
 * consistency with the settings TV screens.
 */
class ExtensionsTvFragment : Fragment(R.layout.fragment_extensions_tv) {

    private val viewModel: ExtensionSettingsViewModel by viewModels()

    private lateinit var adapter: TvExtensionListAdapter
    private var updatesMap: Map<String, ExtensionUpdate> = emptyMap()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TvExtensionListAdapter(
            onToggle = { ext, enabled -> viewModel.toggleExtension(ext.packageName, enabled) },
            onUninstall = { ext -> viewModel.uninstallExtension(ext.packageName) },
            onUpdate = { ext -> viewModel.updateExtension(ext.packageName) },
            updatesProvider = { updatesMap },
        )

        val rv = view.findViewById<RecyclerView>(R.id.rv_extensions)
        rv.adapter = adapter

        SettingsListStyler.attach(view, isTv = true)

        // ── Header action buttons ──────────────────────────────────────────
        view.findViewById<TextView>(R.id.btn_extensions_check_updates)
            ?.setOnClickListener { viewModel.checkForUpdates() }

        view.findViewById<TextView>(R.id.btn_extensions_browse_repos)
            ?.setOnClickListener {
                parentFragmentManager.beginTransaction()
                    .replace(android.R.id.content, RepositoriesTvFragment())
                    .addToBackStack(null)
                    .commit()
            }

        // ── Observe extensions ─────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.extensions.collectLatest { list ->
                    adapter.submitList(list)
                    updateEmptyState(list.isEmpty())
                }
            }
        }

        // ── Observe updates ────────────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.availableUpdates.collectLatest { updates ->
                    updatesMap = updates.associateBy { it.packageName }
                    adapter.notifyItemRangeChanged(0, adapter.itemCount)
                }
            }
        }

        // ── Observe refreshing state ───────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.isRefreshing.collectLatest { refreshing ->
                    view.findViewById<View>(R.id.is_loading)?.visibility =
                        if (refreshing) View.VISIBLE else View.GONE
                }
            }
        }

        // ── Observe snackbar messages ──────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.snackbarMessage.collect { message ->
                    com.google.android.material.snackbar.Snackbar.make(
                        view, message, com.google.android.material.snackbar.Snackbar.LENGTH_SHORT
                    ).show()
                }
            }
        }

        viewModel.refreshAll()
    }

    private fun updateEmptyState(empty: Boolean) {
        view?.findViewById<TextView>(R.id.tv_extensions_empty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────────

private class TvExtensionListAdapter(
    private val onToggle: (InstalledExtensionEntity, Boolean) -> Unit,
    private val onUninstall: (InstalledExtensionEntity) -> Unit,
    private val onUpdate: (InstalledExtensionEntity) -> Unit,
    private val updatesProvider: () -> Map<String, ExtensionUpdate>,
) : ListAdapter<InstalledExtensionEntity, TvExtensionViewHolder>(TvExtensionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TvExtensionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_tv, parent, false)
        return TvExtensionViewHolder(view, onToggle, onUninstall, onUpdate, updatesProvider)
    }

    override fun onBindViewHolder(holder: TvExtensionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class TvExtensionViewHolder(
    itemView: View,
    private val onToggle: (InstalledExtensionEntity, Boolean) -> Unit,
    private val onUninstall: (InstalledExtensionEntity) -> Unit,
    private val onUpdate: (InstalledExtensionEntity) -> Unit,
    private val updatesProvider: () -> Map<String, ExtensionUpdate>,
) : RecyclerView.ViewHolder(itemView) {

    private val tvName = itemView.findViewById<TextView>(android.R.id.title)
    private val tvSummary = itemView.findViewById<TextView>(android.R.id.summary)

    private var currentEntity: InstalledExtensionEntity? = null

    init {
        itemView.isFocusable = true
        itemView.setOnClickListener {
            currentEntity?.let { ext ->
                // Toggle on click for TV (remote-friendly)
                onToggle(ext, !ext.isEnabled)
            }
        }
        // Long click for uninstall
        itemView.setOnLongClickListener {
            currentEntity?.let { ext ->
                onUninstall(ext)
            }
            true
        }
    }

    fun bind(entity: InstalledExtensionEntity) {
        currentEntity = entity
        tvName.text = entity.name
        val update = updatesProvider()[entity.packageName]
        tvSummary.text = buildString {
            append("v${entity.version}")
            if (entity.isEnabled) append(" • Enabled") else append(" • Disabled")
            if (update != null) append(" • Update: v${update.availableVersion}")
        }
    }
}

private class TvExtensionDiffCallback : DiffUtil.ItemCallback<InstalledExtensionEntity>() {
    override fun areItemsTheSame(
        old: InstalledExtensionEntity,
        new: InstalledExtensionEntity,
    ): Boolean = old.packageName == new.packageName

    override fun areContentsTheSame(
        old: InstalledExtensionEntity,
        new: InstalledExtensionEntity,
    ): Boolean = old == new
}
