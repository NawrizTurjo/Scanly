package dev.turjo.paperless

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.turjo.paperless.core.data.ApplicationScope
import dev.turjo.paperless.core.data.enqueueOrphanSweep
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@HiltAndroidApp
class PaperlessApp : Application() {
    @Inject @ApplicationScope lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch(Dispatchers.IO) { enqueueOrphanSweep(this@PaperlessApp) }
    }
}
