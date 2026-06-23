package id.tipime.tv.ui.main

import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.tabs.TabLayout
import id.tipime.tv.R
import id.tipime.tv.data.model.Channel
import id.tipime.tv.data.model.Playlist
import id.tipime.tv.data.model.PlayData
import id.tipime.tv.databinding.ActivityMainBinding
import id.tipime.tv.ui.player.PlayerActivity
import id.tipime.tv.ui.settings.SettingsActivity
import id.tipime.tv.util.Prefs

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var channelAdapter: ChannelAdapter
    private lateinit var prefs: Prefs
    private var currentPlaylist: Playlist? = null
    private var currentCategoryIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        prefs = Prefs(this)
        setupRecyclerView()
        setupObservers()
        viewModel.loadPlaylist()
    }

    private fun setupRecyclerView() {
        channelAdapter = ChannelAdapter { channel, catIdx, chIdx ->
            openPlayer(catIdx, chIdx)
        }
        binding.rvChannels.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = channelAdapter
        }
    }

    private fun setupObservers() {
        viewModel.loading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(this) { error ->
            error?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        }

        viewModel.playlist.observe(this) { playlist ->
            currentPlaylist = playlist
            setupTabs(playlist)
        }
    }

    private fun setupTabs(playlist: Playlist) {
        binding.tabLayout.removeAllTabs()
        playlist.categories.forEach { category ->
            binding.tabLayout.addTab(
                binding.tabLayout.newTab().setText(category.name)
            )
        }
        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                currentCategoryIndex = tab.position
                showCategory(tab.position)
            }
            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        // restore last position
        val lastCat = prefs.lastCategoryIndex.coerceAtMost(playlist.categories.size - 1)
        binding.tabLayout.getTabAt(lastCat)?.select()
        showCategory(lastCat)
    }

    private fun showCategory(index: Int) {
        val channels = currentPlaylist?.categories?.getOrNull(index)?.channels ?: return
        channelAdapter.submitList(channels, index)
    }

    private fun openPlayer(catIdx: Int, chIdx: Int) {
        prefs.lastCategoryIndex = catIdx
        prefs.lastChannelIndex = chIdx
        Intent(this, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_PLAY_DATA, PlayData(catIdx, chIdx))
        }.also { startActivity(it) }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_settings -> {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            }
            R.id.action_refresh -> {
                viewModel.loadPlaylist()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }
}
