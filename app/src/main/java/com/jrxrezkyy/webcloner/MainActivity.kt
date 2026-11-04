package com.jrxrezkyy.webcloner

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jrxrezkyy.webcloner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SiteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SiteAdapter(
            onOpen = { site -> open(site) },
            onMenu = { site, anchor -> showMenu(site, anchor) }
        )
        binding.sitesList.layoutManager = LinearLayoutManager(this)
        binding.sitesList.adapter = adapter

        binding.openButton.setOnClickListener { submit() }
        binding.urlInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO) {
                submit()
                true
            } else {
                false
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val sites = SiteStore.all(this)
        adapter.submit(sites)
        binding.emptyView.visibility = if (sites.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun submit() {
        val url = UrlUtils.normalize(binding.urlInput.text?.toString().orEmpty())
        if (url == null) {
            binding.urlLayout.error = getString(R.string.invalid_url)
            return
        }
        binding.urlLayout.error = null

        val host = UrlUtils.host(url)
        val existing = SiteStore.countForHost(this, host)
        if (existing == 0) {
            openNew(url)
        } else {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.new_account_title)
                .setMessage(getString(R.string.new_account_message, host, existing))
                .setPositiveButton(R.string.new_account_create) { _, _ -> openNew(url) }
                .setNegativeButton(R.string.cancel, null)
                .show()
        }
    }

    private fun openNew(url: String) {
        val site = SiteStore.create(this, url)
        binding.urlInput.setText("")
        refreshList()
        open(site)
    }

    private fun open(site: Site) {
        startActivity(BrowserActivity.createIntent(this, site))
    }

    private fun showMenu(site: Site, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_RENAME, 0, R.string.rename)
        popup.menu.add(0, MENU_SHORTCUT, 1, R.string.add_shortcut)
        popup.menu.add(0, MENU_DELETE, 2, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_RENAME -> showRename(site)
                MENU_SHORTCUT -> ShortcutHelper.pin(this, site)
                MENU_DELETE -> confirmDelete(site)
            }
            true
        }
        popup.show()
    }

    private fun showRename(site: Site) {
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_TEXT
        input.setText(site.name)
        input.setSelection(input.text.length)

        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = FrameLayout(this)
        box.setPadding(pad, pad / 2, pad, 0)
        box.addView(
            input,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.rename)
            .setView(box)
            .setPositiveButton(R.string.save) { _, _ ->
                SiteStore.rename(this, site.id, input.text.toString())
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(site: Site) {
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.delete_confirm, site.name))
            .setPositiveButton(R.string.delete) { _, _ ->
                SiteStore.remove(this, site)
                refreshList()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private companion object {
        const val MENU_RENAME = 1
        const val MENU_SHORTCUT = 2
        const val MENU_DELETE = 3
    }
}
