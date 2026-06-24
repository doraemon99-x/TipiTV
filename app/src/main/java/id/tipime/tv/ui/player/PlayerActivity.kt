package id.tipime.tv.ui.player

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import id.tipime.tv.data.model.PlayData
import id.tipime.tv.data.model.Playlist
import id.tipime.tv.databinding.ActivityPlayerBinding
import id.tipime.tv.player.PlayerManager
import id.tipime.tv.util.PlaylistCache
import id.tipime.tv.util.Prefs

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private lateinit var playerManager: PlayerManager
    private var playlist: Playlist? = null
    private var currentCatIdx = 0
    private var currentChIdx = 0
    private lateinit var prefs: Prefs

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        prefs = Prefs(this)
        hideSystemUI()

        val playData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(EXTRA_PLAY_DATA, PlayData::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_PLAY_DATA)
        }

        currentCatIdx = playData?.categoryIndex ?: 0
        currentChIdx = playData?.channelIndex ?: 0

        playerManager = PlayerManager(this).also { pm ->
            pm.onBuffering = { buffering ->
                binding.progressBar.visibility = if (buffering) View.VISIBLE else View.GONE
            }
            pm.onError = { msg ->
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
            pm.onReady = {
                // bisa tambah animasi atau log di sini
            }
            pm.attachView(binding.playerView)
        }

        // Gunakan PlaylistCache supaya tidak load ulang dari disk
        val cached = PlaylistCache.get()
        if (cached != null) {
            playlist = cached
            playChannel(currentCatIdx, currentChIdx)
        } else {
            binding.progressBar.visibility = View.VISIBLE
            // fallback load dari repo
            loadPlaylistAndPlay()
        }
    }

    private fun loadPlaylistAndPlay() {
        val repo = id.tipime.tv.data.repository.PlaylistRepository(applicationContext)
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            repo.loadPlaylist().onSuccess { pl ->
                PlaylistCache.set(pl)
                playlist = pl
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    playChannel(currentCatIdx, currentChIdx)
                }
            }.onFailure {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Gagal load playlist", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun playChannel(catIdx: Int, chIdx: Int) {
        val pl = playlist ?: return
        val channel = pl.categories.getOrNull(catIdx)?.channels?.getOrNull(chIdx) ?: return

        currentCatIdx = catIdx
        currentChIdx = chIdx
        prefs.lastCategoryIndex = catIdx
        prefs.lastChannelIndex = chIdx

        // tampilkan nama channel
        binding.tvChannelName.text = channel.name
        binding.tvChannelName.visibility = View.VISIBLE
        binding.tvChannelName.removeCallbacks(hideNameRunnable)
        binding.tvChannelName.postDelayed(hideNameRunnable, 3000)

        playerManager.play(channel, pl)
    }

    private val hideNameRunnable = Runnable {
        binding.tvChannelName.visibility = View.GONE
    }

    private fun navigateChannel(delta: Int) {
        val pl = playlist ?: return
        val channels = pl.categories.getOrNull(currentCatIdx)?.channels ?: return
        playChannel(currentCatIdx, (currentChIdx + delta + channels.size) % channels.size)
    }

    private fun navigateCategory(delta: Int) {
        val pl = playlist ?: return
        val newCat = (currentCatIdx + delta + pl.categories.size) % pl.categories.size
        playChannel(newCat, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> { navigateChannel(-1); true }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_NEXT -> { navigateChannel(1); true }

            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_CHANNEL_UP -> { navigateCategory(-1); true }

            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_CHANNEL_DOWN -> { navigateCategory(1); true }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER -> {
                if (playerManager.isPlaying()) playerManager.pause()
                else playerManager.resume()
                true
            }

            KeyEvent.KEYCODE_INFO,
            KeyEvent.KEYCODE_MENU -> {
                // toggle tampilkan nama channel
                val isVisible = binding.tvChannelName.visibility == View.VISIBLE
                binding.tvChannelName.visibility = if (isVisible) View.GONE else View.VISIBLE
                true
            }

            KeyEvent.KEYCODE_BACK -> {
                finish(); true
            }

            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onResume() {
        super.onResume()
        playerManager.resume()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.tvChannelName.removeCallbacks(hideNameRunnable)
        playerManager.release()
    }

    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    companion object {
        const val EXTRA_PLAY_DATA = "extra_play_data"
    }
}
