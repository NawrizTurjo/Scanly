package dev.turjo.paperless.feature.camera

import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.io.File
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

internal enum class CameraFlash(val icon: Int, val label: Int, val captureMode: Int) {
    OFF(R.drawable.camera_ic_flash_off, R.string.camera_flash_off, ImageCapture.FLASH_MODE_OFF),
    ON(R.drawable.camera_ic_flash_on, R.string.camera_flash_on, ImageCapture.FLASH_MODE_ON),
    AUTO(R.drawable.camera_ic_flash_auto, R.string.camera_flash_auto, ImageCapture.FLASH_MODE_AUTO),
    TORCH(R.drawable.camera_ic_torch, R.string.camera_flash_torch, ImageCapture.FLASH_MODE_OFF);

    fun next() = entries[(ordinal + 1) % entries.size]
}

@Composable
internal fun CameraPreview(flash: CameraFlash, onController: (CaptureController?) -> Unit, onError: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val displayRotation = LocalView.current.display?.rotation ?: Surface.ROTATION_0
    val controllerCallback by rememberUpdatedState(onController)
    val errorCallback by rememberUpdatedState(onError)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    var request by remember { mutableStateOf<SurfaceRequest?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    val selector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO))
            .build()
    }
    val preview = remember { Preview.Builder().setResolutionSelector(selector).build() }
    val capture = remember {
        ImageCapture.Builder()
            .setResolutionSelector(selector)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(95)
            .setTargetRotation(displayRotation)
            .build()
    }
    DisposableEffect(context, capture) {
        val orientation = object : OrientationEventListener(context) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                capture.targetRotation = when (degrees) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        orientation.enable()
        onDispose { orientation.disable() }
    }
    LaunchedEffect(context, lifecycleOwner, preview, capture) {
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        try {
            val cameraProvider = suspendCancellableCoroutine<ProcessCameraProvider> { continuation ->
                future.addListener({ continuation.resumeWith(runCatching { future.get() }) }, mainExecutor)
            }
            provider = cameraProvider
            preview.setSurfaceProvider(mainExecutor) { request = it }
            camera = cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
            controllerCallback(CaptureController { destination -> capture.save(destination, mainExecutor) })
            awaitCancellation()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            errorCallback()
        } finally {
            controllerCallback(null)
            camera = null
            request = null
            preview.setSurfaceProvider(null)
            provider?.unbind(preview, capture)
        }
    }
    LaunchedEffect(camera, flash) {
        camera?.let {
            capture.flashMode = flash.captureMode
            if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(flash == CameraFlash.TORCH)
        }
    }
    request?.let { CameraXViewfinder(surfaceRequest = it, modifier = Modifier.fillMaxSize()) }
}

private suspend fun ImageCapture.save(destination: File, executor: Executor) = withContext(Dispatchers.Main.immediate) {
    suspendCoroutine { continuation ->
        takePicture(
            ImageCapture.OutputFileOptions.Builder(destination).build(),
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) = continuation.resume(Unit)
                override fun onError(exception: ImageCaptureException) = continuation.resumeWithException(exception)
            },
        )
    }
}
