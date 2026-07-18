// file: extensions/settings/RepositoriesTvFragment.kt
package com.streamflixreborn.streamflix.extensions.settings

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.extensions.db.ExtensionRepoEntity
import com.streamflixreborn.streamflix.extensions.repo.ExtensionMetadata
import com.streamflixreborn.streamflix.fragments.settings.SettingsListStyler
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * TV (Leanback-style) fragment that lists extension repositories.
 *
 * Uses RecyclerView with vertical LinearLayoutManager (following the existing
 * TV fragment patterns). Rows are styled via [SettingsListStyler].
 */
class RepositoriesTvFragment : Fragment(R.layout.fragment_repositories_tv) {

    private val viewModel: ExtensionSettingsViewModel by viewModels()

    private lateinit var adapter: TvRepositoryListAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = TvRepositoryListAdapter(
            onToggle = { repo, enabled ->
                viewModel.setRepositoryEnabled(repo.url, enabled)
            },
            onRefresh = { repo -> viewModel.refreshRepository(repo.url) },
            onDelete = { repo -> showDeleteConfirm(repo) },
            onBrowse = { repo ->
                viewModel.browseRepository(repo.url)
                showBrowseDialog(repo)
            },
        )

        val rv = view.findViewById<RecyclerView>(R.id.rv_repositories)
        rv.adapter = adapter

        SettingsListStyler.attach(view, isTv = true)

        // ── Add Repository button ──────────────────────────────────────────
        view.findViewById<TextView>(R.id.btn_repositories_add)
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
        val dialog = AlertDialog.Builder(requireContext())
            .setTitle("Browse: ${repo.name}")
            .setMessage("Fetching available extensions...")
            .setNegativeButton("Cancel", null)
            .show()

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.browseResult.collect { metadataList ->
                if (metadataList.isEmpty()) return@collect
                dialog.dismiss()
                showBrowseResultDialog(repo.name, metadataList)
                viewModel.clearBrowseResult()
            }
        }
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

private class TvRepositoryListAdapter(
    private val onToggle: (ExtensionRepoEntity, Boolean) -> Unit,
    private val onRefresh: (ExtensionRepoEntity) -> Unit,
    private val onDelete: (ExtensionRepoEntity) -> Unit,
    private val onBrowse: (ExtensionRepoEntity) -> Unit,
) : ListAdapter<ExtensionRepoEntity, TvRepositoryViewHolder>(TvRepositoryDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TvRepositoryViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_setting_tv, parent, false)
        return TvRepositoryViewHolder(view, onToggle, onRefresh, onDelete, onBrowse)
    }

    override fun onBindViewHolder(holder: TvRepositoryViewHolder, position: Int) {
        holder.bind(getItem(position))
    }
}

private class TvRepositoryViewHolder(
    itemView: View,
    private val onToggle: (ExtensionRepoEntity, Boolean) -> Unit,
    private val onRefresh: (ExtensionRepoEntity) -> Unit,
    private val onDelete: (ExtensionRepoEntity) -> Unit,
    private val onBrowse: (ExtensionRepoEntity) -> Unit,
) : RecyclerView.ViewHolder(itemView) {

    private val tvName = itemView.findViewById<TextView>(android.R.id.title)
    private val tvSummary = itemView.findViewById<TextView>(android.R.id.summary)

    private var currentEntity: ExtensionRepoEntity? = null

    init {
        itemView.isFocusable = true
        itemView.setOnClickListener {
            currentEntity?.let { repo -> onBrowse(repo) }
        }
        itemView.setOnLongClickListener {
            currentEntity?.let { repo ->
                if (!repo.isBuiltIn) onDelete(repo)
            }
            true
        }
    }

    fun bind(entity: ExtensionRepoEntity) {
        currentEntity = entity
        tvName.text = entity.name
        tvSummary.text = buildString {
            append(entity.url)
            if (entity.isBuiltIn) append(" • Built-in")
            append(if (entity.enabled) " • Enabled" else " • Disabled")
        }
    }
}

private class TvRepositoryDiffCallback : DiffUtil.ItemCallback<ExtensionRepoEntity>() {
    override fun areItemsTheSame(
        old: ExtensionRepoEntity,
        new: ExtensionRepoEntity,
    ): Boolean = old.url == new.url

    override fun areContentsTheSame(
        old: ExtensionRepoEntity,
        new: ExtensionRepoEntity,
    ): Boolean = old == new
}
