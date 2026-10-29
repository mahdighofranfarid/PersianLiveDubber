package com.example.persiandubber

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
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
            val modelName = prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash"
            val targetLang = prefs.getString("target_lang", "فارسی") ?: "فارسی"
            val voiceStyle = prefs.getString("voice_style", "زن ایرانی استودیویی (دلارا - بدون تأخیر)") ?: "زن ایرانی استودیویی (دلارا - بدون تأخیر)"
            val origVol = prefs.getFloat("orig_vol", 0.0f)
            val dubVol = prefs.getFloat("dub_vol", 1.0f)
            val chunkSec = prefs.getInt("chunk_sec", 2)
            val showSubs = prefs.getBoolean("show_subs", true)
            val enableTts = prefs.getBoolean("enable_tts", true)

            val intent = Intent(this, AudioCaptureService::class.java).apply {
                action = AudioCaptureService.ACTION_START
                putExtra(AudioCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(AudioCaptureService.EXTRA_RESULT_DATA, result.data)
                putExtra(AudioCaptureService.EXTRA_API_KEY, apiKey)
                putExtra(AudioCaptureService.EXTRA_MODEL_NAME, modelName)
                putExtra(AudioCaptureService.EXTRA_TARGET_LANG, targetLang)
                putExtra(AudioCaptureService.EXTRA_VOICE_STYLE, voiceStyle)
                putExtra(AudioCaptureService.EXTRA_ORIG_VOL, origVol)
                putExtra(AudioCaptureService.EXTRA_DUB_VOL, dubVol)
                putExtra(AudioCaptureService.EXTRA_CHUNK_SEC, chunkSec)
                putExtra(AudioCaptureService.EXTRA_SHOW_SUBS, showSubs)
                putExtra(AudioCaptureService.EXTRA_ENABLE_TTS, enableTts)
            }
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "دوبله بدون تأخیر و زیرنویس شناور فعال شد! وارد یوتیوب شوید.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("dubber_prefs", Context.MODE_PRIVATE)

        setContent {
            var apiKey by remember { mutableStateOf(prefs.getString("gemini_api_key", "") ?: "") }
            var passwordVisible by remember { mutableStateOf(false) }
            var modelName by remember { mutableStateOf(prefs.getString("model_name", "gemini-2.5-flash") ?: "gemini-2.5-flash") }
            var selectedLanguage by remember { mutableStateOf(prefs.getString("target_lang", "فارسی") ?: "فارسی") }
            var selectedVoice by remember { mutableStateOf(prefs.getString("voice_style", "زن ایرانی استودیویی (دلارا - بدون تأخیر)") ?: "زن ایرانی استودیویی (دلارا - بدون تأخیر)") }
            var originalVolume by remember { mutableStateOf(prefs.getFloat("orig_vol", 0.0f)) }
            var dubVolume by remember { mutableStateOf(prefs.getFloat("dub_vol", 1.0f)) }
            var chunkSeconds by remember { mutableStateOf(prefs.getInt("chunk_sec", 2).toFloat()) }
            var showSubtitles by remember { mutableStateOf(prefs.getBoolean("show_subs", true)) }
            var enableTts by remember { mutableStateOf(prefs.getBoolean("enable_tts", true)) }
            var expandedLangMenu by remember { mutableStateOf(false) }
            var expandedVoiceMenu by remember { mutableStateOf(false) }
            var expandedModelMenu by remember { mutableStateOf(false) }
            var hasOverlayPerm by remember { mutableStateOf(Settings.canDrawOverlays(this)) }

            val telemetry by AudioCaptureService.telemetryFlow.collectAsState()
            val languages = listOf("فارسی", "انگلیسی", "عربی", "ترکی استانبولی", "فرانسوی", "آلمانی")
            val voiceStyles = listOf(
                "زن ایرانی استودیویی (دلارا - بدون تأخیر)",
                "مرد ایرانی استودیویی (فرید - بدون تأخیر)",
                "موتور سریع گوشی (تأخیر صفر - هماهنگ با زیرنویس)"
            )
            val models = listOf(
                "gemini-2.5-flash",
                "gemini-2.5-flash-lite",
                "gemini-3.5-flash-lite",
                "gemini-3.8-flash",
                "gemini-3.1-pro",
                "gemini-2.0-flash-lite",
                "gemini-1.5-flash"
            )

            fun saveSettings() {
                prefs.edit().apply {
                    putString("gemini_api_key", apiKey.trim())
                    putString("model_name", modelName.trim())
                    putString("target_lang", selectedLanguage)
                    putString("voice_style", selectedVoice)
                    putFloat("orig_vol", originalVolume)
                    putFloat("dub_vol", dubVolume)
                    putInt("chunk_sec", chunkSeconds.toInt())
                    putBoolean("show_subs", showSubtitles)
                    putBoolean("enable_tts", enableTts)
                    apply()
                }
                AudioCaptureService.updateLiveConfig(
                    apiKey = apiKey.trim(),
                    modelName = modelName.trim(),
                    targetLang = selectedLanguage,
                    voiceStyle = selectedVoice,
                    origVol = originalVolume,
                    dubVol = dubVolume,
                    chunkSec = chunkSeconds.toInt(),
                    showSubs = showSubtitles,
                    enableTts = enableTts
                )
            }

            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("استودیو دوبله زنده (نسخه ۱۷ - هماهنگی آنی صدا و زیرنویس)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("قطع ۱۰۰٪ صدای اصلی در حالت صفر + سوکت دائمی ضد تأخیر صدا", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                        }
                    }

                    if (!hasOverlayPerm) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("مجوز نمایش زیرنویس روی یوتیوب", fontWeight = FontWeight.Bold, color = Color(0xFFE65100))
                                Button(
                                    onClick = {
                                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                                        startActivity(intent)
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text("فعال‌سازی مجوز زیرنویس شناور (Overlay)")
                                }
                            }
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

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("انتخاب گوینده و میکسر واقعی دو کاناله صدا", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)

                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedVoiceMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("گوینده: " + selectedVoice)
                                }
                                DropdownMenu(expanded = expandedVoiceMenu, onDismissRequest = { expandedVoiceMenu = false }) {
                                    voiceStyles.forEach { vStyle ->
                                        DropdownMenuItem(
                                            text = { Text(vStyle) },
                                            onClick = {
                                                selectedVoice = vStyle
                                                expandedVoiceMenu = false
                                                saveSettings()
                                            }
                                        )
                                    }
                                }
                            }

                            val volModeDesc = when {
                                originalVolume <= 0.02f -> "قطع ۱۰۰٪ صدای اصلی ویدیو (صفر مطلق - فقط پخش صدای دوبله)"
                                originalVolume < 0.50f -> "صدای ملایم ویدیو در پس‌زمینه + صدای بلند دوبله"
                                else -> "پخش صدای اصلی ویدیو با حجم بالا"
                            }
                            Column {
                                Text("صدای اصلی ویدیو یوتیوب: " + (originalVolume * 100).toInt() + "% (" + volModeDesc + ")", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Slider(value = originalVolume, onValueChange = { originalVolume = it; saveSettings() }, valueRange = 0f..1f)
                            }
                            Column {
                                Text("بلندی صدای گوینده دوبله (کانال مستقل): " + (dubVolume * 100).toInt() + "%", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Slider(value = dubVolume, onValueChange = { dubVolume = it; saveSettings() }, valueRange = 0f..1f)
                            }

                            OutlinedButton(
                                onClick = {
                                    saveSettings()
                                    val testIntent = Intent(this@MainActivity, AudioCaptureService::class.java).apply {
                                        action = AudioCaptureService.ACTION_TEST_VOICE
                                        putExtra(AudioCaptureService.EXTRA_API_KEY, apiKey.trim())
                                        putExtra(AudioCaptureService.EXTRA_TARGET_LANG, selectedLanguage)
                                        putExtra(AudioCaptureService.EXTRA_VOICE_STYLE, selectedVoice)
                                        putExtra(AudioCaptureService.EXTRA_DUB_VOL, dubVolume)
                                    }
                                    startService(testIntent)
                                    Toast.makeText(this@MainActivity, "در حال پخش فوری صدای تست گوینده...", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Icon(Icons.Default.VolumeUp, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("🔊 تست فوری صدای گوینده انتخاب‌شده")
                            }
                        }
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text("تنظیمات زبان و زیرنویس شناور (زوم ۲ انگشتی + دکمه ✕)", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedLangMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Language, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("زبان مقصد دوبله و زیرنویس: " + selectedLanguage)
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
                                Text("پخش صدای گوینده دوبله")
                                Switch(checked = enableTts, onCheckedChange = { enableTts = it; saveSettings() })
                            }

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("نمایش کادر زیرنویس شناور روی ویدیو")
                                Switch(checked = showSubtitles, onCheckedChange = { showSubtitles = it; saveSettings() })
                            }
                        }
                    }

                    OutlinedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("تنظیمات مدل و سرعت واکنش", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            
                            Box(modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(
                                    onClick = { expandedModelMenu = true },
                                    modifier = Modifier.fillMaxWidth(),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.SmartToy, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("انتخاب مدل هوش مصنوعی")
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

                            OutlinedTextField(
                                value = modelName,
                                onValueChange = { modelName = it; saveSettings() },
                                label = { Text("نام مدل فعال") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            )

                            Column {
                                Text("حداکثر بازه دریافت جملات: هر " + chunkSeconds.toInt() + " ثانیه (۲ ثانیه = کمترین تأخیر ممکن)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                Slider(
                                    value = chunkSeconds,
                                    onValueChange = { chunkSeconds = it; saveSettings() },
                                    valueRange = 2f..6f,
                                    steps = 3
                                )
                            }
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("وضعیت: " + telemetry.status, fontWeight = FontWeight.Medium)
                            Text("سیگنال صدا (RMS): " + telemetry.inputRms + " | جملات دوبله‌شده: " + telemetry.requestCount + " | زوم زیرنویس: " + String.format("%.1fx", telemetry.subtitleScale), style = MaterialTheme.typography.bodySmall)
                            
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = {
                                        hasOverlayPerm = Settings.canDrawOverlays(this@MainActivity)
                                        if (showSubtitles && !hasOverlayPerm) {
                                            Toast.makeText(this@MainActivity, "لطفاً ابتدا مجوز نمایش زیرنویس روی سایر برنامه‌ها را فعال کنید", Toast.LENGTH_LONG).show()
                                            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName))
                                            startActivity(intent)
                                        } else {
                                            saveSettings()
                                            requestCapturePermission()
                                        }
                                    },
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
