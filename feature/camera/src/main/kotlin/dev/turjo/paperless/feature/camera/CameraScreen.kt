package dev.turjo.paperless.feature.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.core.view.WindowCompat
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun CameraScreen(
    targetDocumentId: String?,
    insertAt: Int?,
    singleMode: Boolean,
    onReview: (draftDocumentId: String) -> Unit,
    onClose: () -> Unit,
) {
    val model = hiltViewModel<CameraViewModel, CameraViewModel.Factory> {
        it.create(CameraArgs(targetDocumentId, insertAt, singleMode))
    }
    val state by model.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context.findActivity()
    // The preview is dark whatever the app theme is: force light system-bar icons while the camera shows.
    DisposableEffect(activity) {
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousStatus = controller?.isAppearanceLightStatusBars
        val previousNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            previousStatus?.let { controller.isAppearanceLightStatusBars = it }
            previousNav?.let { controller.isAppearanceLightNavigationBars = it }
        }
    }
    var allowed by remember { mutableStateOf(context.hasCameraPermission()) }
    var requested by rememberSaveable { mutableStateOf<Boolean?>(null) }
    var rationale by remember { mutableStateOf(activity.showsCameraRationale()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(context) {
        val stored = withContext(Dispatchers.IO) { context.cameraPreferences().getBoolean(PermissionRequested, false) }
        if (requested == null) requested = stored
    }
    LaunchedEffect(allowed, requested) {
        if (allowed && requested == true) {
            withContext(Dispatchers.IO) { context.cameraPreferences().edit().remove(PermissionRequested).apply() }
            requested = false
        }
    }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        requested = !granted
        allowed = granted
        rationale = activity.showsCameraRationale()
        scope.launch(Dispatchers.IO) {
            context.cameraPreferences().edit().putBoolean(PermissionRequested, !granted).apply()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        allowed = context.hasCameraPermission()
        rationale = activity.showsCameraRationale()
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { uris ->
        model.importImages(uris) { uri, destination ->
            val input = checkNotNull(context.contentResolver.openInputStream(uri))
            input.use { source -> destination.outputStream().use { source.copyTo(it) } }
        }
    }
    var flash by rememberSaveable { mutableStateOf(CameraFlash.OFF) }
    var controller by remember { mutableStateOf<CaptureController?>(null) }
    var shutterFlash by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val snackbar = remember { SnackbarHostState() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val reviewCallback by rememberUpdatedState(onReview)
    val closeCallback by rememberUpdatedState(onClose)
    val operationError = stringResource(R.string.camera_operation_error)
    val previewError = stringResource(R.string.camera_preview_error)
    LaunchedEffect(model, lifecycle, haptic, operationError) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            model.events.collect { event ->
                when (event) {
                    is CameraEvent.Review -> reviewCallback(event.draftId)
                    CameraEvent.Close -> closeCallback()
                    CameraEvent.Error -> launch {
                        if (snackbar.currentSnackbarData == null) snackbar.showSnackbar(operationError)
                    }
                    CameraEvent.Captured -> {
                        haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                        shutterFlash = true
                        try {
                            delay(120)
                        } finally {
                            shutterFlash = false
                        }
                    }
                }
            }
        }
    }
    BackHandler(onBack = model::close)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (allowed) {
            CameraPreview(
                flash = flash,
                onController = { controller = it },
                onError = { scope.launch { snackbar.showSnackbar(previewError) } },
            )
        }
        // Live page edges can be drawn here in viewfinder coordinates.
        Box(Modifier.matchParentSize()) {
            if (shutterFlash) Box(Modifier.matchParentSize().background(Color.White))
        }
        if (!allowed) {
            val settings = requested == true && !rationale
            Surface(Modifier.align(Alignment.Center).windowInsetsPadding(WindowInsets.safeDrawing).padding(24.dp),
                shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.camera_permission_title), style = MaterialTheme.typography.titleLarge)
                    Text(stringResource(R.string.camera_permission_message))
                    Button(enabled = requested != null, onClick = {
                        if (settings) {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
                            )
                        } else {
                            requestPermission.launch(Manifest.permission.CAMERA)
                        }
                    }) {
                        Text(stringResource(if (settings) R.string.camera_settings else R.string.camera_grant))
                    }
                }
            }
        }

        Row(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Scrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            CameraIconButton(R.drawable.camera_ic_close, R.string.camera_close, model::close)
            SingleChoiceSegmentedButtonRow {
                SegmentedButton(
                    selected = !state.singleMode,
                    onClick = { model.setSingleMode(false) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    enabled = state.canEdit,
                    colors = cameraSegmentColors(),
                ) { Text(stringResource(R.string.camera_batch)) }
                SegmentedButton(
                    selected = state.singleMode,
                    onClick = { model.setSingleMode(true) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    enabled = state.canEdit,
                    colors = cameraSegmentColors(),
                ) { Text(stringResource(R.string.camera_single)) }
            }
            CameraIconButton(flash.icon, flash.label, { flash = flash.next() }, enabled = allowed)
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Scrim)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(horizontal = 24.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            SnackbarHost(snackbar)
            if (state.importing) {
                Row(
                    Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(stringResource(R.string.camera_importing), color = Color.White)
                }
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                CameraIconButton(R.drawable.camera_ic_gallery, R.string.camera_gallery, {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }, enabled = state.canEdit)
                val capture = controller
                val canCapture = allowed && capture != null && state.canEdit
                val captureLabel = stringResource(R.string.camera_capture)
                IconButton(
                    onClick = { capture?.let(model::capture) },
                    enabled = canCapture,
                    modifier = Modifier.size(76.dp)
                        .border(2.dp, Color.White.copy(alpha = if (canCapture) 1f else 0.4f), CircleShape)
                        .semantics { contentDescription = captureLabel },
                ) {
                    Box(Modifier.size(62.dp).background(Color.White.copy(alpha = if (canCapture) 1f else 0.4f), CircleShape))
                }
                ReviewThumbnail(model, state)
            }
        }
    }

    if (state.confirmDiscard) {
        AlertDialog(
            onDismissRequest = model::dismissDiscard,
            title = { Text(pluralStringResource(R.plurals.camera_discard_title, state.pageIds.size, state.pageIds.size)) },
            confirmButton = { TextButton(onClick = model::discard) { Text(stringResource(R.string.camera_discard)) } },
            dismissButton = { TextButton(onClick = model::keep) { Text(stringResource(R.string.camera_keep)) } },
        )
    }
}

@Composable
private fun ReviewThumbnail(model: CameraViewModel, state: CameraState) {
    val pageId = state.pageIds.lastOrNull()
    val busy by model.busyPageIds.collectAsStateWithLifecycle()
    key(pageId) {
        val thumbnail by produceState<Bitmap?>(null, pageId in busy) {
            if (pageId != null) {
                value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(model.thumbnailFile(pageId).path) }
            }
        }
        IconButton(
            onClick = model::review,
            enabled = pageId != null && state.canEdit,
            modifier = Modifier.size(48.dp),
        ) {
            BadgedBox(badge = { if (pageId != null) Badge { Text(state.pageIds.size.toString()) } }) {
                val image = thumbnail
                val description = stringResource(R.string.camera_review)
                if (image != null) {
                    Image(image.asImageBitmap(), description, Modifier.size(36.dp).clip(MaterialTheme.shapes.small),
                        contentScale = ContentScale.Crop)
                } else {
                    Icon(painterResource(R.drawable.camera_ic_gallery), description,
                        tint = Color.White.copy(alpha = if (pageId == null) 0.4f else 1f))
                }
            }
        }
    }
}

@Composable
private fun CameraIconButton(icon: Int, description: Int, onClick: () -> Unit, enabled: Boolean = true) {
    IconButton(onClick, enabled = enabled, modifier = Modifier.size(48.dp)) {
        Icon(painterResource(icon), stringResource(description), tint = Color.White.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun cameraSegmentColors() = SegmentedButtonDefaults.colors(
    inactiveContainerColor = Color.Transparent,
    inactiveContentColor = Color.White,
    inactiveBorderColor = Color.White,
)

private fun Context.hasCameraPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Activity?.showsCameraRationale() =
    this?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true

private fun Context.cameraPreferences() =
    applicationContext.getSharedPreferences("scan_camera", Context.MODE_PRIVATE)

private const val PermissionRequested = "permission_requested"
private val Scrim = Color.Black.copy(alpha = 0.65f)
