package com.jrxrezkyy.webcloner

import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.PopupMenu
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.jrxrezkyy.webcloner.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var adapter: SiteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        adapter = SiteAdapter(
            onOpen = { site -> open(site.url) },
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
        open(url)
    }

    private fun open(url: String) {
        startActivity(BrowserActivity.createIntent(this, url))
    }

    private fun showMenu(site: Site, anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, MENU_SHORTCUT, 0, R.string.add_shortcut)
        popup.menu.add(0, MENU_DELETE, 1, R.string.delete)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                MENU_SHORTCUT -> ShortcutHelper.pin(this, site.url, site.name)
                MENU_DELETE -> {
                    SiteStore.remove(this, site)
                    refreshList()
                }
            }
            true
        }
        popup.show()
    }

    private companion object {
        const val MENU_SHORTCUT = 1
        const val MENU_DELETE = 2
    }
}
