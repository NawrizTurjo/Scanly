package dev.turjo.paperless

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dagger.hilt.android.AndroidEntryPoint
import dev.turjo.paperless.core.designsystem.ScanlyTheme
import dev.turjo.paperless.core.designsystem.R as DesignR
import dev.turjo.paperless.feature.library.LibraryScreen
import dev.turjo.paperless.feature.reader.ReaderScreen
import dev.turjo.paperless.feature.settings.SettingsScreen
import dev.turjo.paperless.feature.tools.ToolsScreen

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
                ScanlyShell()
            }
        }
    }
}

private enum class Tab(val label: Int, val icon: Int) {
    LIBRARY(R.string.tab_library, DesignR.drawable.ic_library),
    READER(R.string.tab_reader, DesignR.drawable.ic_reader),
    TOOLS(R.string.tab_tools, DesignR.drawable.ic_tools),
    SETTINGS(R.string.tab_settings, DesignR.drawable.ic_settings),
}

@Composable
private fun ScanlyShell() {
    var selectedTab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                for (tab in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == selectedTab,
                        onClick = { if (tab != selectedTab) selectedTab = tab },
                        icon = { Icon(painterResource(tab.icon), contentDescription = null) },
                        label = { Text(stringResource(tab.label)) },
                    )
                }
            }
        },
    ) { padding ->
        NavDisplay(
            backStack = listOf(selectedTab),
            onBack = {},
            modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding),
            entryDecorators = listOf(
                rememberSaveableStateHolderNavEntryDecorator(),
                rememberViewModelStoreNavEntryDecorator(),
            ),
            entryProvider = entryProvider {
                entry(Tab.LIBRARY) { LibraryScreen() }
                entry(Tab.READER) { ReaderScreen() }
                entry(Tab.TOOLS) { ToolsScreen() }
                entry(Tab.SETTINGS) { SettingsScreen() }
            },
        )
    }
}
