package id.tipime.tv.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import id.tipime.tv.data.model.Playlist
import id.tipime.tv.data.repository.PlaylistRepository
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PlaylistRepository(app)

    private val _playlist = MutableLiveData<Playlist>()
    val playlist: LiveData<Playlist> = _playlist

    private val _loading = MutableLiveData(false)
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    fun loadPlaylist() {
        _loading.value = true
        _error.value = null
        viewModelScope.launch {
            repository.loadPlaylist()
                .onSuccess {
                    _playlist.value = it
                    _loading.value = false
                }
                .onFailure {
                    _error.value = it.message
                    _loading.value = false
                }
        }
    }
}
