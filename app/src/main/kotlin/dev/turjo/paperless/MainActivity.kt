package dev.turjo.paperless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.luminance
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.AndroidEntryPoint
import dev.turjo.paperless.core.designsystem.ScanlyTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            val model: MainViewModel = viewModel()
            val settings by model.settings.collectAsStateWithLifecycle()
            ScanlyTheme(settings.themeMode, settings.dynamicColor) {
                val lightStatusBar = MaterialTheme.colorScheme.background.luminance() > 0.5f
                val lightNavBar = NavigationBarDefaults.containerColor.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightStatusBar
                        isAppearanceLightNavigationBars = lightNavBar
                    }
                }
                ScanlyNavigation()
            }
        }
    }
}
