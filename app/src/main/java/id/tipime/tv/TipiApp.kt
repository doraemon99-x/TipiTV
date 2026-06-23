package id.tipime.tv

import android.app.Application
import coil.Coil
import coil.ImageLoader

class TipiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Coil.setImageLoader(
            ImageLoader.Builder(this)
                .crossfade(true)
                .build()
        )
    }
}
