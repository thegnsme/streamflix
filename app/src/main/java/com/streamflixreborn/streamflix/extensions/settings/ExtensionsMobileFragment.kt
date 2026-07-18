// file: extensions/settings/ExtensionsMobileFragment.kt
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
 * Mobile fragment that lists installed extensions with enable/disable toggles,
 * update badges, and uninstall actions.
 *
 * Follows the existing Streamflix mobile fragment pattern (plain Fragment with
 * RecyclerView + include loading layout).
 */
class ExtensionsMobileFragment : Fragment(R.layout.fragment_extensions_mobile) {

    private val viewModel: ExtensionSettingsViewModel by viewModels()

    private lateinit var adapter: ExtensionListAdapter
    private var updatesMap: Map<String, ExtensionUpdate> = emptyMap()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = ExtensionListAdapter(
            onToggle = { ext, enabled -> viewModel.toggleExtension(ext.packageName, enabled) },
            onUninstall = { ext -> viewModel.uninstallExtension(ext.packageName) },
            onUpdate = { ext -> viewModel.updateExtension(ext.packageName) },
            updatesProvider = { updatesMap },
        )

        val rv = view.findViewById<RecyclerView>(R.id.rv_extensions)
        rv.adapter = adapter

        SettingsListStyler.attach(view, isTv = false)

        // ── Header action buttons ──────────────────────────────────────────
        view.findViewById<TextView>(R.id.btn_extensions_check_updates)
            ?.setOnClickListener { viewModel.checkForUpdates() }

        view.findViewById<TextView>(R.id.btn_extensions_browse_repos)
            ?.setOnClickListener {
                // Navigate to RepositoriesMobileFragment
                parentFragmentManager.beginTransaction()
                    .replace(R.id.container, RepositoriesMobileFragment())
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

        // ── Initial data refresh ───────────────────────────────────────────
        viewModel.refreshAll()
    }

    private fun updateEmptyState(empty: Boolean) {
        view?.findViewById<TextView>(R.id.tv_extensions_empty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────────

private class ExtensionListAdapter(
    private val onToggle: (InstalledExtensionEntity, Boolean) -> Unit,
    private val onUninstall: (InstalledExtensionEntity) -> Unit,
    private val onUpdate: (InstalledExtensionEntity) -> Unit,
    private val updatesProvider: () -> Map<String, ExtensionUpdate>,
) : ListAdapter<InstalledExtensionEntity, ExtensionViewHolder>(ExtensionDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ExtensionViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_extension_mobile, parent, false)
        return ExtensionViewHolder(view, onToggle, onUninstall, onUpdate, updatesProvider)
    }

    override fun onBindViewHolder(holder: ExtensionViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class ExtensionViewHolder(
    itemView: View,
    private val onToggle: (InstalledExtensionEntity, Boolean) -> Unit,
    private val onUninstall: (InstalledExtensionEntity) -> Unit,
    private val onUpdate: (InstalledExtensionEntity) -> Unit,
    private val updatesProvider: () -> Map<String, ExtensionUpdate>,
) : RecyclerView.ViewHolder(itemView) {

    private val switchEnabled = itemView.findViewById<SwitchCompat>(R.id.s_extension_enabled)
    private val tvName = itemView.findViewById<TextView>(R.id.tv_extension_name)
    private val tvVersion = itemView.findViewById<TextView>(R.id.tv_extension_version)
    private val tvUpdateBadge = itemView.findViewById<TextView>(R.id.tv_extension_update_badge)
    private val btnUpdate = itemView.findViewById<ImageButton>(R.id.btn_extension_update)
    private val btnUninstall = itemView.findViewById<ImageButton>(R.id.btn_extension_uninstall)

    private var currentEntity: InstalledExtensionEntity? = null

    fun bind(entity: InstalledExtensionEntity) {
        currentEntity = entity
        tvName.text = entity.name
        tvVersion.text = "v${entity.version}"

        switchEnabled.isChecked = entity.isEnabled
        switchEnabled.setOnCheckedChangeListener(null) // prevent loop
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(entity, isChecked)
        }

        val update = updatesProvider()[entity.packageName]
        if (update != null) {
            tvUpdateBadge.visibility = View.VISIBLE
            tvUpdateBadge.text = "v${update.availableVersion} available"
            btnUpdate.visibility = View.VISIBLE
            btnUpdate.setOnClickListener { onUpdate(entity) }
        } else {
            tvUpdateBadge.visibility = View.GONE
            btnUpdate.visibility = View.GONE
        }

        btnUninstall.setOnClickListener { onUninstall(entity) }
    }
}

private class ExtensionDiffCallback : DiffUtil.ItemCallback<InstalledExtensionEntity>() {
    override fun areItemsTheSame(
        old: InstalledExtensionEntity,
        new: InstalledExtensionEntity,
    ): Boolean = old.packageName == new.packageName

    override fun areContentsTheSame(
        old: InstalledExtensionEntity,
        new: InstalledExtensionEntity,
    ): Boolean = old == new
}
