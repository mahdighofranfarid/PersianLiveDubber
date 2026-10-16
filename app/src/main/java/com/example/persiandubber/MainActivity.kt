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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val prefs = getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)
            val apiKey = prefs.getString("gemini_api_key", "") ?: ""
            val modelName = prefs.getString("model_name", "gemini-3.8-flash") ?: "gemini-3.8-flash"
            val targetLang = prefs.getString("target_lang", "فارسی") ?: "فارسی"
            val origVol = prefs.getFloat("orig_vol", 0.2f)
            val dubVol = prefs.getFloat("dub_vol", 1.0f)

            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_API_KEY, apiKey)
                putExtra(AudioCaptureService.EXTRA_MODEL_NAME, modelName)
                putExtra(AudioCaptureService.EXTRA_TARGET_LANG, targetLang)
                putExtra(AudioCaptureService.EXTRA_ORIG_VOL, origVol)
                putExtra(AudioCaptureService.EXTRA_DUB_VOL, dubVol)
            }
            ContextCompat.startForegroundService(this, intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)

        setContent {
            var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }
            var modelName by remember { mutableStateOf(prefs.getString("model_name", "gemini-3.8-flash") ?: "gemini-3.8-flash") }
            var selectedLanguage by remember { mutableStateOf(prefs.getString("target_lang", "فارسی") ?: "فارسی") }
            var originalVolume by remember { mutableStateOf(prefs.getFloat("orig_vol", 0.2f)) }
            var dubVolume by remember { mutableStateOf(prefs.getFloat("dub_vol", 1.0f)) }
            var showSubtitles by remember { mutableStateOf(prefs.getBoolean("show_subs", true)) }
            var expandedLangMenu by remember { mutableStateOf(false) }
            var expandedModelMenu by remember { mutableStateOf(false) }

            val telemetry by AudioCaptureService.telemetryFlow.collectAsState()
            val languages = listOf("فارسی", "انگلیسی", "عربی", "ترکی استانبولی", "فرانسوی", "آلمانی")
            val models = listOf(
                "gemini-3.8-flash",
                "gemini-3.5-flash-lite",
                "gemini-3.1-pro",
                "gemini-4-argon",
                "gemini-2.5-flash",
                "gemini-2.5-pro",
                "gemini-2.0-flash",
                "gemini-1.5-flash"
            )

            fun saveSettings() {
                prefs.edit().apply {
                    putString("gemini_api_key", apiKey)
                    putString("model_name", modelName)
                    putString("target_lang", selectedLanguage)
                    putFloat("orig_vol", originalVolume)
                    putFloat("dub_vol", dubVolume)
                    putBoolean("show_subs", showSubtitles)
                    apply()
                }
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("استودیو دوبله هوشمند (انتخاب دستی و پیش‌فرض مدل)", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("پشتیبانی کامل از تمام مدل‌ها + ورود دستی نام مدل", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                        }
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("کلید API شخصی Gemini", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            OutlinedTextField(
                                value = apiKey,
                                onValueChange = { apiKey = it; saveSettings() },
                                label = { Text("کلید API خود را اینجا وارد کنید") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                trailingIcon = {
                                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                                        Icon(if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = null)
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            )
                        }
                    }

                    // Model Selection & Manual Entry Card
                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("انتخاب یا ورود دستی نام مدل هوش مصنوعی", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            // Dropdown for predefined models
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedModelMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.SmartToy, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("انتخاب از لیست سریع مدل‌ها")
                                }
                                DropdownMenu(expanded = expandedModelMenu, onDismissRequest = { expandedModelMenu = false }) {
                                    models.forEach { mod ->
                                        DropdownMenuItem(
                                            text = { Text(mod) },
                                            onClick = {
                                                modelName = mod
                                                expandedModelMenu = false
                                                saveSettings()
                                            }
                                        )
                                    }
                                }
                            }

                            // Manual Text Field for custom model name
                            OutlinedTextField(
                                value = modelName,
                                onValueChange = { modelName = it; saveSettings() },
                                label = { Text("نام مدل (دستی یا انتخابی)") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp),
                                supportingText = { Text("می‌توانید مدل دلخواه را خودتان تایپ کنید یا از لیست بالا انتخاب کنید") }
                            )
                        }
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("تنظیمات زبان و زیرنویس", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedLangMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("زبان مقصد دوبله: $selectedLanguage")
                                }
                                DropdownMenu(expanded = expandedLangMenu, onDismissRequest = { expandedLangMenu = false }) {
                                    languages.forEach { lang ->
                                        DropdownMenuItem(
                                            text = { Text(lang) },
                                            onClick = { selectedLanguage = lang; expandedLangMenu = false; saveSettings() }
                                        )
                                    }
                                }
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("نمایش زیرنویس زنده روی صفحه")
                                Switch(checked = showSubtitles, onCheckedChange = { showSubtitles = it; saveSettings() })
                            }
                        }
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("میکسر صدا", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            Column {
                                Text("صدای اصلی ویدیو: ${(originalVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                Slider(value = originalVolume, onValueChange = { originalVolume = it; saveSettings() }, valueRange = 0f..1f)
                            }
                            Column {
                                Text("صدای دوبله هوش مصنوعی: ${(dubVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                                Slider(value = dubVolume, onValueChange = { dubVolume = it; saveSettings() }, valueRange = 0f..1f)
                            }
                        }
                    }

                    if (showSubtitles && telemetry.isCapturing) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Box(modifier = Modifier.padding(12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = telemetry.latestTranscript.ifEmpty { "در حال گوش دادن و ترجمه صدای یوتیوب..." },
                                    color = Color.Yellow,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("وضعیت سیستم: ${telemetry.status}", fontWeight = FontWeight.Medium)
                            Text("سیگنال صدا (RMS): ${telemetry.inputRms}", style = MaterialTheme.typography.bodySmall)
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = { saveSettings(); requestCapturePermission() },
                                    enabled = !telemetry.isCapturing,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("شروع دوبله زنده")
                                }

                                OutlinedButton(
                                    onClick = {
                                        val intent = Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                                            action = AudioCaptureService.ACTION_STOP
                                        }
                                        startService(intent)
                                    },
                                    enabled = telemetry.isCapturing,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("توقف")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun requestCapturePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
