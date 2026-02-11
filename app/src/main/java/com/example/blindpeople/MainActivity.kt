package com.example.blindpeople

import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.blindpeople.ui.theme.BlindPeopleTheme
import com.example.blindpeople.camera.FrameAnalyzer
import com.example.blindpeople.ui.AppUiState
import com.example.blindpeople.ui.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import androidx.hilt.navigation.compose.hiltViewModel
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d("BlindPeopleLog", "[MainActivity.onCreate]")
        enableEdgeToEdge()
        setContent {
            BlindPeopleTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
private fun MainScreen(
    vm: MainViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasCamPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("BlindPeopleLog", "[MainScreen.permissionResult] granted=$granted")
        hasCamPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCamPermission) {
            Log.d("BlindPeopleLog", "[MainScreen] requesting CAMERA permission")
            permissionLauncher.launch(android.Manifest.permission.CAMERA)
        }
    }

    var executor: ExecutorService? by remember { mutableStateOf(null) }
    LaunchedEffect(Unit) {
        Log.d("BlindPeopleLog", "[MainScreen] creating camera executor")
        executor = Executors.newSingleThreadExecutor()
    }
    DisposableEffect(Unit) {
        onDispose {
            Log.d("BlindPeopleLog", "[MainScreen] disposing camera executor")
            executor?.shutdown()
            executor = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Preview
        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                PreviewView(ctx).apply {
                    implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
            },
            update = { previewView ->
                if (!hasCamPermission) {
                    Log.d("BlindPeopleLog", "[MainScreen.AndroidView] no camera permission, skipping bind")
                    return@AndroidView
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    Log.d("BlindPeopleLog", "[MainScreen.AndroidView] got cameraProvider, binding use cases")
                    cameraProvider.unbindAll()

                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    val exec = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
                    Log.d("BlindPeopleLog", "[MainScreen.AndroidView] using executor=$exec")
                    analysis.setAnalyzer(
                        exec,
                        FrameAnalyzer(maxFps = 2.0) { bmp -> vm.onFrame(bmp) }
                    )

                    val selector = CameraSelector.DEFAULT_BACK_CAMERA
                    cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // Controls
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(
                onClick = { vm.start() },
                enabled = hasCamPermission
            ) { Text("Start") }

            Button(
                onClick = { vm.stop() }
            ) { Text("Stop") }

            Spacer(modifier = Modifier.weight(1f))

            val audioOn = when (state) {
                is AppUiState.Running -> (state as AppUiState.Running).audioEnabled
                else -> true
            }
            Text("Audio")
            Switch(
                checked = audioOn,
                onCheckedChange = { vm.setAudioEnabled(it) }
            )
        }

        // Status
        val statusText = when (state) {
            is AppUiState.Idle -> if (hasCamPermission) "Idle" else "Camera permission required"
            is AppUiState.Running -> (state as AppUiState.Running).status
            is AppUiState.Error -> "Error: ${(state as AppUiState.Error).message}"
        }
        Text(text = statusText)

        if (!hasCamPermission) {
            Spacer(modifier = Modifier.height(4.dp))
            val permanentlyDenied = !ActivityCompat.shouldShowRequestPermissionRationale(
                context as android.app.Activity,
                android.Manifest.permission.CAMERA
            )
            val msg = if (permanentlyDenied) {
                "Camera permission denied. Enable it in system settings."
            } else {
                "Grant camera permission to start."
            }
            Text(msg, color = MaterialTheme.colorScheme.error)
        }
    }
}