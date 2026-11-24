package dev.turjo.paperless

import android.content.ContentResolver
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import dev.turjo.paperless.core.designsystem.ScanlyTheme
import dev.turjo.paperless.core.model.AppLock
import dev.turjo.paperless.core.model.UserSettings
import dev.turjo.paperless.feature.settings.UpdatesViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val model by lazy { ViewModelProvider(this)[MainViewModel::class.java] }
    private var resumed by mutableStateOf(false)
    private var promptSignal by mutableStateOf<CancellationSignal?>(null)
    private var unlockError by mutableStateOf<String?>(null)
    private var attemptedUnlock = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ViewModelProvider(this)[UpdatesViewModel::class.java].checkOnStartup()
        enableEdgeToEdge()
        setRecentsPreviewAllowed(false)
        val initialStack = startBackStack(intent.getStringExtra(ROUTE_EXTRA))
        if (savedInstanceState == null) receiveFileIntent(intent)

        setContent {
            val storedSettings by model.settings.collectAsStateWithLifecycle()
            val locked by model.locked.collectAsStateWithLifecycle()
            val settings = storedSettings ?: UserSettings()
            val navigationState = rememberSaveableStateHolder()
            ScanlyTheme(
                themeMode = settings.themeMode,
                dynamicColor = settings.dynamicColor,
                amoledBlack = settings.amoledBlack,
                accent = settings.accent,
            ) {
                val lightStatusBar = MaterialTheme.colorScheme.background.luminance() > 0.5f
                val lightNavBar = NavigationBarDefaults.containerColor.luminance() > 0.5f
                SideEffect {
                    WindowCompat.getInsetsController(window, window.decorView).apply {
                        isAppearanceLightStatusBars = lightStatusBar
                        isAppearanceLightNavigationBars = lightNavBar
                    }
                    val recentsAllowed = storedSettings?.appLock == AppLock.OFF
                    setRecentsPreviewAllowed(recentsAllowed)
                }
                when {
                    storedSettings == null -> Surface(Modifier.fillMaxSize()) {
                        Box(contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    }
                    locked -> {
                        LockScreen(promptSignal == null, unlockError, ::authenticate)
                        LaunchedEffect(resumed) {
                            if (resumed && !attemptedUnlock) authenticate()
                        }
                    }
                    else -> {
                        SideEffect { attemptedUnlock = false }
                        navigationState.SaveableStateProvider("navigation") {
                            val preparingPdf by model.preparingPdf.collectAsStateWithLifecycle()
                            ScanlyNavigation(
                                initialStack, model.entries, model::documentOpened,
                                model::openDocumentInReader, model::openPdf, preparingPdf,
                                model::openFileInReader, model::openPdfAtPage, model::importImagesAsScan,
                                onPickPdf = { uri ->
                                    model.receiveFileIntent(Intent(Intent.ACTION_VIEW).setDataAndType(uri, PDF_MIME_TYPE))
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!receiveFileIntent(intent)) model.openRoute(intent.getStringExtra(ROUTE_EXTRA))
    }

    override fun onStart() {
        model.onStart()
        super.onStart()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onStop() {
        if (!isChangingConfigurations) model.onStop()
        super.onStop()
    }

    override fun onDestroy() {
        val signal = promptSignal
        promptSignal = null
        signal?.cancel()
        super.onDestroy()
    }

    private fun setRecentsPreviewAllowed(allowed: Boolean) {
        if (Build.VERSION.SDK_INT >= 33) {
            setRecentsScreenshotEnabled(allowed)
        } else if (allowed) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun receiveFileIntent(intent: Intent): Boolean {
        val needsContentType = intent.action == Intent.ACTION_VIEW && intent.type == null &&
            intent.scheme == ContentResolver.SCHEME_CONTENT
        if (!needsContentType && !acceptsFileIntent(intent.action, intent.type)) return false
        model.receiveFileIntent(intent)
        return true
    }

    private fun authenticate() {
        if (!resumed || promptSignal != null) return
        attemptedUnlock = true
        unlockError = null
        val signal = CancellationSignal()
        promptSignal = signal
        BiometricPrompt.Builder(this)
            .setTitle(getString(R.string.app_name))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
            .authenticate(signal, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (promptSignal !== signal) return
                    promptSignal = null
                    model.unlock()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    if (promptSignal !== signal) return
                    promptSignal = null
                    unlockError = errString.toString().ifBlank { getString(R.string.unlock_error) }
                }
            })
    }
}

@Composable
private fun LockScreen(canUnlock: Boolean, error: String?, onUnlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(painterResource(R.drawable.ic_lock), contentDescription = null)
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Button(onClick = onUnlock, enabled = canUnlock) { Text(stringResource(R.string.unlock)) }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
