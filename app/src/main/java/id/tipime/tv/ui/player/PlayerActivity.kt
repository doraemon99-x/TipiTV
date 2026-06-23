package id.tipime.tv.ui.player

import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import id.tipime.tv.data.model.PlayData
import id.tipime.tv.data.model.Playlist
import id.tipime.tv.data.repository.PlaylistRepository
import id.tipime.tv.databinding.ActivityPlayerBinding
import id.tipime.tv.player.PlayerHelper
import id.tipime.tv.util.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@UnstableApi
class PlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
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

        loadAndPlay()
    }

    private fun loadAndPlay() {
        binding.progressBar.visibility = View.VISIBLE
        CoroutineScope(Dispatchers.IO).launch {
            val repo = PlaylistRepository(applicationContext)
            repo.loadPlaylist().onSuccess { pl ->
                playlist = pl
                withContext(Dispatchers.Main) {
                    initPlayer()
                    playChannel(currentCatIdx, currentChIdx)
                }
            }.onFailure {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@PlayerActivity, "Failed to load playlist", Toast.LENGTH_SHORT).show()
                    finish()
                }
            }
        }
    }

    private fun initPlayer() {
        player = PlayerHelper.buildPlayer(this).also {
            binding.playerView.player = it
            it.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    binding.progressBar.visibility =
                        if (state == Player.STATE_BUFFERING) View.VISIBLE else View.GONE
                }
                override fun onPlayerError(error: PlaybackException) {
                    Toast.makeText(
                        this@PlayerActivity,
                        "Error: ${error.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            })
        }
    }

    private fun playChannel(catIdx: Int, chIdx: Int) {
        val pl = playlist ?: return
        val channel = pl.categories.getOrNull(catIdx)?.channels?.getOrNull(chIdx) ?: return

        currentCatIdx = catIdx
        currentChIdx = chIdx
        prefs.lastCategoryIndex = catIdx
        prefs.lastChannelIndex = chIdx

        binding.tvChannelName.text = channel.name
        binding.tvChannelName.visibility = View.VISIBLE

        val mediaSource = PlayerHelper.buildMediaSource(channel, pl)
        player?.apply {
            setMediaSource(mediaSource)
            prepare()
            playWhenReady = true
        }

        // auto hide channel name after 3s
        binding.tvChannelName.postDelayed({
            binding.tvChannelName.visibility = View.GONE
        }, 3000)
    }

    private fun navigateChannel(delta: Int) {
        val pl = playlist ?: return
        val channels = pl.categories.getOrNull(currentCatIdx)?.channels ?: return
        val newIdx = (currentChIdx + delta + channels.size) % channels.size
        playChannel(currentCatIdx, newIdx)
    }

    private fun navigateCategory(delta: Int) {
        val pl = playlist ?: return
        val newCatIdx = (currentCatIdx + delta + pl.categories.size) % pl.categories.size
        playChannel(newCatIdx, 0)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                navigateChannel(-1); true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_NEXT -> {
                navigateChannel(1); true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                navigateCategory(-1); true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                navigateCategory(1); true
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                player?.let {
                    if (it.isPlaying) it.pause() else it.play()
                }; true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onResume() {
        super.onResume()
        player?.play()
    }

    override fun onPause() {
        super.onPause()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
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
