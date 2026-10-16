package com.example.persiandubber

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

class MainActivity : ComponentActivity() {

    private var captureOnlyMode = true
    private var proxyWsUrl = "ws://10.0.2.2:8080/ws/live-dub"

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_CAPTURE_ONLY_TEST, captureOnlyMode)
                putExtra(AudioCaptureService.EXTRA_PROXY_URL, proxyWsUrl)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val exoPlayer = ExoPlayer.Builder(this).build().apply {
            // Short open-source English speech/video clip for Stage 1 AudioPlaybackCapture test
            val sampleUri = "https://commondatastorage.googleapis.com/gtv-videos-bucket/sample/ForBiggerBlazes.mp4"
            setMediaItem(MediaItem.fromUri(sampleUri))
            prepare()
        }

        setContent {
            MaterialTheme {
                val telemetry by AudioCaptureService.telemetryFlow.collectAsState()
                var localTestChecked by remember { mutableStateOf(true) }
                var serverUrl by remember { mutableStateOf("ws://10.0.2.2:8080/ws/live-dub") }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("استودیو دوبله هم‌زمان فارسی (Android 10+)", style = MaterialTheme.typography.titleLarge)

                    // Built-in ExoPlayer for testing short video capture before testing external apps
                    Card(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply { player = exoPlayer }
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }

                    OutlinedTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it },
                        label = { Text("آدرس سرور پروکسی (بدون کلید در APK)") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Checkbox(
                            checked = localTestChecked,
                            onCheckedChange = { localTestChecked = it }
                        )
                        Text("فقط تست مرحله ۱ (کپچر صدای ویدیو بدون Gemini)")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = {
                                captureOnlyMode = localTestChecked
                                proxyWsUrl = serverUrl
                                requestCapturePermission()
                            },
                            enabled = !telemetry.isCapturing
                        ) {
                            Text("شروع کپچر / دوبله")
                        }

                        OutlinedButton(
                            onClick = {
                                startService(Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                                    action = AudioCaptureService.ACTION_STOP
                                })
                            },
                            enabled = telemetry.isCapturing
                        ) {
                            Text("توقف")
                        }
                    }

                    HorizontalDivider()
                    Text("وضعیت: ${telemetry.status}")
                    Text("شدت صدای دریافتی از ویدیو (RMS): ${telemetry.inputRms}")
                    Text("تأخیر واقعی (End-to-End): ${telemetry.e2eLatencyMs} ms")
                }
            }
        }
    }

    private fun requestCapturePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
