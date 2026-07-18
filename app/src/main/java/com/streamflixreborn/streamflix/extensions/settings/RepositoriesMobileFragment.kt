// file: extensions/settings/RepositoriesMobileFragment.kt
package com.streamflixreborn.streamflix.extensions.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
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
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extensions.db.ExtensionRepoEntity
import com.streamflixreborn.streamflix.extensions.repo.ExtensionMetadata
import com.streamflixreborn.streamflix.fragments.settings.SettingsListStyler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Mobile fragment that lists extension repositories with enable/disable toggles,
 * refresh/delete actions, and an "Add Repository" FAB.
 */
class RepositoriesMobileFragment : Fragment(R.layout.fragment_repositories_mobile) {

    private val viewModel: ExtensionSettingsViewModel by viewModels()

    private lateinit var adapter: RepositoryListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = RepositoryListAdapter(
            onToggle = { repo, enabled ->
                viewModel.setRepositoryEnabled(repo.url, enabled)
            },
            onRefresh = { repo -> viewModel.refreshRepository(repo.url) },
            onDelete = { repo -> showDeleteConfirm(repo) },
            onBrowse = { repo -> viewModel.browseRepository(repo.url); showBrowseDialog(repo) },
        )

        val rv = view.findViewById<RecyclerView>(R.id.rv_repositories)
        rv.adapter = adapter

        SettingsListStyler.attach(view, isTv = false)

        // ── FAB: Add Repository ────────────────────────────────────────────
        view.findViewById<FloatingActionButton>(R.id.fab_add_repository)
            ?.setOnClickListener { showAddRepositoryDialog() }

        // ── Observe repositories ───────────────────────────────────────────
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.repositories.collectLatest { list ->
                    adapter.submitList(list)
                    updateEmptyState(list.isEmpty())
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
    }

    private fun updateEmptyState(empty: Boolean) {
        view?.findViewById<TextView>(R.id.tv_repositories_empty)?.visibility =
            if (empty) View.VISIBLE else View.GONE
    }

    // ── Dialogs ─────────────────────────────────────────────────────────────

    private fun showAddRepositoryDialog() {
        val input = EditText(requireContext()).apply {
            hint = "https://example.com/repo.json"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_URI
        }

        AlertDialog.Builder(requireContext())
            .setTitle("Add Repository")
            .setMessage("Enter the repository URL")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotBlank()) {
                    viewModel.addRepository(url)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirm(repo: ExtensionRepoEntity) {
        AlertDialog.Builder(requireContext())
            .setTitle("Remove Repository")
            .setMessage("Remove \"${repo.name}\"? This will also uninstall its extensions.")
            .setPositiveButton("Remove") { _, _ -> viewModel.removeRepository(repo.url) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBrowseDialog(repo: ExtensionRepoEntity) {
        // Observe browse result once
        val job = viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browseResult.collect { metadataList ->
                if (metadataList.isEmpty()) return@collect
                showBrowseResultDialog(repo.name, metadataList)
                // Clear so the dialog only shows once per browse trigger
                viewModel.clearBrowseResult()
            }
        }

        // If the dialog is dismissed before result arrives, cancel observation
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Browse: ${repo.name}")
            .setMessage("Fetching available extensions...")
            .setNegativeButton("Cancel") { _, _ -> job.cancel() }
            .show()

        // Actually trigger the browse (this will emit to browseResult)
        // The browse was already triggered in the click handler
        // but we watch for the result here
    }

    private fun showBrowseResultDialog(repoName: String, metadataList: List<ExtensionMetadata>) {
        val names = metadataList.map { "${it.name} v${it.version}" }.toTypedArray()
        AlertDialog.Builder(requireContext())
            .setTitle("Extensions in $repoName")
            .setItems(names) { _, which ->
                val metadata = metadataList[which]
                showInstallConfirm(metadata)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showInstallConfirm(metadata: ExtensionMetadata) {
        AlertDialog.Builder(requireContext())
            .setTitle("Install ${metadata.name}")
            .setMessage(
                buildString {
                    append("Version: ${metadata.version}\n")
                    if (metadata.description != null) append("${metadata.description}\n")
                    if (metadata.authors.isNotEmpty()) append("Author: ${metadata.authors.joinToString()}\n")
                    appendLine()
                    append("Install this extension?")
                }
            )
            .setPositiveButton("Install") { _, _ ->
                viewModel.installExtension(metadata)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

// ── Adapter ──────────────────────────────────────────────────────────────────

private class RepositoryListAdapter(
    private val onToggle: (ExtensionRepoEntity, Boolean) -> Unit,
    private val onRefresh: (ExtensionRepoEntity) -> Unit,
    private val onDelete: (ExtensionRepoEntity) -> Unit,
    private val onBrowse: (ExtensionRepoEntity) -> Unit,
) : ListAdapter<ExtensionRepoEntity, RepositoryViewHolder>(RepositoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RepositoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_repository_mobile, parent, false)
        return RepositoryViewHolder(view, onToggle, onRefresh, onDelete, onBrowse)
    }

    override fun onBindViewHolder(holder: RepositoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class RepositoryViewHolder(
    itemView: View,
    private val onToggle: (ExtensionRepoEntity, Boolean) -> Unit,
    private val onRefresh: (ExtensionRepoEntity) -> Unit,
    private val onDelete: (ExtensionRepoEntity) -> Unit,
    private val onBrowse: (ExtensionRepoEntity) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val switchEnabled = itemView.findViewById<SwitchCompat>(R.id.s_repository_enabled)
    private val tvName = itemView.findViewById<TextView>(R.id.tv_repository_name)
    private val tvUrl = itemView.findViewById<TextView>(R.id.tv_repository_url)
    private val tvBuiltInBadge = itemView.findViewById<TextView>(R.id.tv_repository_built_in_badge)
    private val btnBrowse = itemView.findViewById<ImageButton>(R.id.btn_repository_browse)
    private val btnRefresh = itemView.findViewById<ImageButton>(R.id.btn_repository_refresh)
    private val btnDelete = itemView.findViewById<ImageButton>(R.id.btn_repository_delete)

    private var currentEntity: ExtensionRepoEntity? = null

    fun bind(entity: ExtensionRepoEntity) {
        currentEntity = entity
        tvName.text = entity.name
        tvUrl.text = entity.url
        tvBuiltInBadge.visibility = if (entity.isBuiltIn) View.VISIBLE else View.GONE
        btnDelete.visibility = if (entity.isBuiltIn) View.GONE else View.VISIBLE

        switchEnabled.isChecked = entity.enabled
        switchEnabled.setOnCheckedChangeListener(null)
        switchEnabled.setOnCheckedChangeListener { _, isChecked ->
            onToggle(entity, isChecked)
        }

        btnBrowse.setOnClickListener { onBrowse(entity) }
        btnRefresh.setOnClickListener { onRefresh(entity) }
        btnDelete.setOnClickListener { onDelete(entity) }
    }
}

private class RepositoryDiffCallback : DiffUtil.ItemCallback<ExtensionRepoEntity>() {
    override fun areItemsTheSame(
        old: ExtensionRepoEntity,
        new: ExtensionRepoEntity,
    ): Boolean = old.url == new.url

    override fun areContentsTheSame(
        old: ExtensionRepoEntity,
        new: ExtensionRepoEntity,
    ): Boolean = old == new
}
