package com.example.persiandubber

import android.content.Context
import android.media.*
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

sealed class PreparedAudioClip {
    data class Mp3Clip(val bytes: ByteArray, val label: String) : PreparedAudioClip()
    data class LocalFallbackClip(val text: String, val targetLang: String) : PreparedAudioClip()
}

data class ReadyPlaybackItem(
    val clip: PreparedAudioClip,
    val dubVolume: Float,
    val origVolume: Float,
    val onStatus: (String) -> Unit
)

class VoiceSynthesizer(private val context: Context) : TextToSpeech.OnInitListener {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var localTts: TextToSpeech? = null
    private var localTtsReady = false
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var activeFocusRequest: AudioFocusRequest? = null

    private var savedMusicVolume: Int = -1
    private var savedAlarmVolume: Int = -1
    private var wasMutedToZero = false

    private val playbackQueue = Channel<ReadyPlaybackItem>(Channel.UNLIMITED)
    private val pendingPlaybackCount = AtomicInteger(0)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // Dictionary of common Persian words to exact Tehrani phonetic spelling (in Turkish orthography)
    // so Google WaveNet (tl=tr) reads Persian sentences with a warm, natural Tehrani accent!
    private val tehraniDictionary = mapOf(
        "سلام" to "selam",
        "من" to "men",
        "تو" to "to",
        "او" to "u",
        "ما" to "ma",
        "شما" to "şoma",
        "آنها" to "anha",
        "اینجا" to "inca",
        "آنجا" to "anca",
        "این" to "in",
        "آن" to "an",
        "که" to "ke",
        "از" to "ez",
        "به" to "be",
        "با" to "ba",
        "در" to "der",
        "بر" to "ber",
        "برای" to "beraye",
        "همه" to "heme",
        "همیشه" to "hemişe",
        "هرگز" to "hergez",
        "خیلی" to "heyli",
        "خوب" to "hub",
        "عالی" to "ali",
        "است" to "est",
        "هست" to "hest",
        "هستم" to "hestem",
        "هستی" to "hesti",
        "هستیم" to "hestim",
        "هستید" to "hestid",
        "هستند" to "hestend",
        "نیست" to "nist",
        "بود" to "bud",
        "شد" to "şod",
        "میشود" to "mişeved",
        "می‌شود" to "mişeved",
        "میکنم" to "mikonem",
        "می‌کنم" to "mikonem",
        "میکند" to "mikoned",
        "می‌کند" to "mikoned",
        "میکنیم" to "mikonim",
        "می‌کنیم" to "mikonim",
        "میگوید" to "miguyed",
        "می‌گوید" to "miguyed",
        "گفت" to "goft",
        "گفتم" to "goftem",
        "پخش" to "pehş",
        "صدا" to "seda",
        "صدای" to "sedaye",
        "گوینده" to "guyende",
        "طبیعی" to "tebii",
        "فارسی" to "farsi",
        "دوبله" to "duble",
        "زنده" to "zende",
        "بدون" to "bedune",
        "تاخیر" to "tehir",
        "تأخیر" to "tehir",
        "کیفیت" to "keyfiyet",
        "استودیویی" to "estudyoyi",
        "جدید" to "cedid",
        "فعلا" to "felen",
        "الان" to "elan",
        "حالا" to "hala",
        "چرا" to "çera",
        "چطور" to "çetor",
        "کجا" to "koca",
        "وقتی" to "vehti",
        "اگر" to "eger",
        "اما" to "emma",
        "ولی" to "veli",
        "چون" to "çon",
        "دوست" to "dust",
        "دارم" to "darem",
        "دارد" to "dared",
        "داریم" to "darim",
        "عشق" to "eşg",
        "زندگی" to "zendegi",
        "دل" to "del",
        "قلب" to "gelb",
        "چشم" to "çeşm",
        "دست" to "dest",
        "روز" to "ruz",
        "شب" to "şeb",
        "آسمان" to "aseman",
        "جهان" to "cehan",
        "دنیا" to "donya",
        "خدا" to "hoda",
        "مرسی" to "mersi",
        "ممنون" to "memnun",
        "لطفا" to "lotfen",
        "لطفاً" to "lotfen",
        "بله" to "bele",
        "نه" to "ne",
        "آره" to "are",
        "بریم" to "berim",
        "بیا" to "biya",
        "برو" to "boro",
        "ببین" to "bebin",
        "فکر" to "fekr",
        "کار" to "kar",
        "زمان" to "zeman",
        "راه" to "rah",
        "خانه" to "hane",
        "شهر" to "şehr",
        "کشور" to "keşver",
        "مردم" to "merdom",
        "بچه" to "beçe",
        "پسر" to "peser",
        "دختر" to "dohter",
        "مادر" to "mader",
        "پدر" to "peder",
        "برادر" to "berader",
        "خواهر" to "haher"
    )

    init {
        localTts = TextToSpeech(context, this)
        startPlaybackQueueConsumer()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            localTtsReady = true
        }
    }

    fun saveInitialVolumes() {
        try {
            if (savedMusicVolume == -1) {
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                // Never save 0 as initial music volume!
                savedMusicVolume = if (cur > 1) cur else (max * 0.65f).roundToInt().coerceAtLeast(2)
            }
            if (savedAlarmVolume == -1) {
                savedAlarmVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
        } catch (_: Exception) {}
    }

    fun restoreInitialVolumes() {
        try {
            abandonYoutubeDucking()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            }
            if (savedMusicVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedMusicVolume, 0)
                savedMusicVolume = -1
            }
            if (savedAlarmVolume != -1) {
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
                savedAlarmVolume = -1
            }
            wasMutedToZero = false
        } catch (_: Exception) {}
    }

    // Clean, conflict-free Volume Policy:
    // - We do NOT constantly overwrite STREAM_MUSIC on every percent change because the Dubbed Voice ALSO plays on STREAM_MUSIC!
    // - ONLY when the user sets Original Video Volume to 0% (<= 0.03f), we mute STREAM_MUSIC to 0 and route Dubbed Voice to STREAM_ALARM.
    // - As soon as the user moves Original Video Volume above 3% (> 0.03f), we IMMEDIATELY restore STREAM_MUSIC to savedMusicVolume and use AudioFocus Ducking!
    fun applyOriginalVideoVolumePolicy(origVolRatio: Float, dubVolRatio: Float) {
        try {
            saveInitialVolumes()
            val maxMusic = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val maxAlarm = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)

            val targetAlarm = (dubVolRatio.coerceIn(0.25f, 1f) * maxAlarm).roundToInt().coerceAtLeast(1)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, targetAlarm, 0)

            if (origVolRatio <= 0.03f) {
                // Complete Mute of Original Video
                wasMutedToZero = true
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                requestYoutubeDucking()
            } else {
                // If we were muted (or if STREAM_MUSIC is currently 0), immediately restore STREAM_MUSIC so YouTube plays again!
                val curMusic = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (wasMutedToZero || curMusic == 0) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
                    }
                    val restored = if (savedMusicVolume > 1) savedMusicVolume else (maxMusic * 0.65f).roundToInt().coerceAtLeast(2)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restored, 0)
                    wasMutedToZero = false
                }

                // Use Android AudioFocus Ducking to lower YouTube's background sound without touching phone master volume!
                if (origVolRatio < 0.75f) {
                    requestYoutubeDucking()
                } else {
                    abandonYoutubeDucking()
                }
            }
        } catch (_: Exception) {}
    }

    fun requestYoutubeDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (activeFocusRequest == null) {
                    val focusAttrs = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                    activeFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                        .setAudioAttributes(focusAttrs)
                        .setWillPauseWhenDucked(false)
                        .build()
                }
                audioManager.requestAudioFocus(activeFocusRequest!!)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            }
        } catch (_: Exception) {}
    }

    fun abandonYoutubeDucking() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (_: Exception) {}
    }

    fun speak(
        text: String,
        phoneticHint: String,
        targetLang: String,
        voiceStyle: String,
        dubVolume: Float,
        origVolume: Float,
        onStatus: (String) -> Unit = {}
    ) {
        val cleanText = text.replace("*", "").replace("\"", "").replace("'", "").replace("\n", " ").trim()
        if (cleanText.length < 2) return

        if (pendingPlaybackCount.get() > 2) return

        scope.launch {
            onStatus("⏳ در حال تولید صدای گوینده...")
            val clip = synthesizeGuaranteedClip(cleanText, phoneticHint, targetLang, voiceStyle)
            pendingPlaybackCount.incrementAndGet()
            playbackQueue.trySend(ReadyPlaybackItem(clip, dubVolume, origVolume, onStatus))
        }
    }

    private fun startPlaybackQueueConsumer() {
        scope.launch {
            for (item in playbackQueue) {
                try {
                    if (item.origVolume in 0.04f..0.75f) {
                        requestYoutubeDucking()
                    }
                    when (val c = item.clip) {
                        is PreparedAudioClip.Mp3Clip -> {
                            item.onStatus(c.label)
                            playMp3Blocking(c.bytes, item.dubVolume, item.origVolume)
                        }
                        is PreparedAudioClip.LocalFallbackClip -> {
                            item.onStatus("🔊 پخش با موتور TTS داخلی گوشی")
                            playLocalAndroidTtsBlocking(c.text, c.targetLang, item.dubVolume, item.origVolume)
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    if (pendingPlaybackCount.decrementAndGet() == 0 && item.origVolume >= 0.75f) {
                        abandonYoutubeDucking()
                    }
                }
            }
        }
    }

    private fun synthesizeGuaranteedClip(
        text: String,
        phoneticHint: String,
        targetLang: String,
        voiceStyle: String
    ): PreparedAudioClip {
        val prefersFastWaveNet = voiceStyle.contains("WaveNet") || voiceStyle.contains("گوگل")
        val prefersDirectGtx = voiceStyle.contains("GTX")

        // Option A: If user chose Fast Google WaveNet Tehrani (or for non-Persian languages), try it first (<250ms latency!)
        if (prefersFastWaveNet && targetLang == "فارسی") {
            val phoneticText = if (phoneticHint.isNotBlank()) phoneticHint else persianToTurkishPhonetic(text)
            val waveMp3 = synthesizeGoogleWaveNetMp3(phoneticText, "tr")
            if (waveMp3 != null && waveMp3.size > 200) {
                return PreparedAudioClip.Mp3Clip(waveMp3, "🔊 گوینده طبیعی WaveNet (لهجه تهرانی)")
            }
        }

        if (prefersDirectGtx) {
            val gtxMp3 = synthesizeGoogleGtxPersianMp3(text, targetLang)
            if (gtxMp3 != null && gtxMp3.size > 200) {
                return PreparedAudioClip.Mp3Clip(gtxMp3, "🔊 گوینده سریع GTX")
            }
        }

        // Option B: Try Microsoft Edge Neural TTS (fa-IR-DilaraNeural / fa-IR-FaridNeural) with UTC timezone fix!
        val edgeMp3 = synthesizeEdgeNeuralMp3(text, targetLang, voiceStyle)
        if (edgeMp3 != null && edgeMp3.size > 200) {
            val vLabel = if (voiceStyle.contains("مرد")) "فرید - استودیویی" else "دلارا - استودیویی"
            return PreparedAudioClip.Mp3Clip(edgeMp3, "🔊 گوینده ایرانی (" + vLabel + ")")
        }

        // Option C: Guaranteed Fast Fallback 1 -> Google WaveNet Tehrani Phonetic (for Persian) or Native targetLang (for English/Arabic/Turkish/etc.)
        if (targetLang == "فارسی") {
            val phoneticText = if (phoneticHint.isNotBlank()) phoneticHint else persianToTurkishPhonetic(text)
            val waveMp3 = synthesizeGoogleWaveNetMp3(phoneticText, "tr")
            if (waveMp3 != null && waveMp3.size > 200) {
                return PreparedAudioClip.Mp3Clip(waveMp3, "🔊 گوینده طبیعی WaveNet (تهرانی)")
            }
        } else {
            val langCode = getGoogleLangCode(targetLang)
            val waveMp3 = synthesizeGoogleWaveNetMp3(text, langCode)
            if (waveMp3 != null && waveMp3.size > 200) {
                return PreparedAudioClip.Mp3Clip(waveMp3, "🔊 گوینده طبیعی WaveNet")
            }
        }

        // Option D: Guaranteed Fast Fallback 2 -> Google GTX Direct Stream
        val gtxMp3 = synthesizeGoogleGtxPersianMp3(text, targetLang)
        if (gtxMp3 != null && gtxMp3.size > 200) {
            return PreparedAudioClip.Mp3Clip(gtxMp3, "🔊 گوینده پشتیبان آنلاین")
        }

        // Option E: Offline Android TTS
        return PreparedAudioClip.LocalFallbackClip(text, targetLang)
    }

    // Converts Persian script into Turkish phonetic orthography so Google's Neural WaveNet (tl=tr)
    // pronounces Persian words with natural Tehrani vowels and consonants!
    fun persianToTurkishPhonetic(persian: String): String {
        val cleaned = persian
            .replace("،", ", ")
            .replace("؛", ", ")
            .replace("؟", "?")
            .replace("!", "!")
            .replace("می‌", "می")
            .replace("نمی‌", "نمی")
            .replace("\u200C", " ")

        val words = cleaned.split(Regex("\\s+"))
        val outWords = ArrayList<String>(words.size)

        for (rawWord in words) {
            val stripped = rawWord.trim { !it.isLetterOrDigit() }
            if (stripped.isEmpty()) continue

            val dictHit = tehraniDictionary[stripped]
            if (dictHit != null) {
                outWords.add(dictHit)
                continue
            }

            // Rule-based Tehrani phonetic transliteration for unknown words
            val sb = StringBuilder()
            val len = stripped.length
            for (i in 0 until len) {
                val ch = stripped[i]
                when (ch) {
                    'آ', 'ا' -> if (i == 0) sb.append("a") else sb.append("a")
                    'ب' -> sb.append("b")
                    'پ' -> sb.append("p")
                    'ت', 'ط' -> sb.append("t")
                    'ث', 'س', 'ص' -> sb.append("s")
                    'ج' -> sb.append("c")
                    'چ' -> sb.append("ç")
                    'ح', 'ه' -> if (i == len - 1 && len > 2) sb.append("e") else sb.append("h")
                    'خ' -> sb.append("h")
                    'د' -> sb.append("d")
                    'ذ', 'ز', 'ظ', 'ض' -> sb.append("z")
                    'ر' -> sb.append("r")
                    'ژ' -> sb.append("j")
                    'ش' -> sb.append("ş")
                    'ع', 'ء', 'ئ', 'أ', 'إ' -> if (i == 0) sb.append("e") else sb.append("a")
                    'غ', 'ق' -> sb.append("g")
                    'ف' -> sb.append("f")
                    'ک', 'ك' -> sb.append("k")
                    'گ' -> sb.append("g")
                    'ل' -> sb.append("l")
                    'م' -> sb.append("m")
                    'ن' -> sb.append("n")
                    'و' -> if (i == 0) sb.append("v") else sb.append("o")
                    'ی', 'ي' -> if (i == 0) sb.append("y") else sb.append("i")
                    'َ' -> sb.append("e")
                    'ِ' -> sb.append("e")
                    'ُ' -> sb.append("o")
                    else -> if (ch.isLetterOrDigit()) sb.append(ch)
                }
                // Insert natural Tehrani short vowel 'e' between consecutive consonant clusters
                if (i < len - 1) {
                    val curIsConsonant = isPersianConsonant(ch)
                    val nextCh = stripped[i + 1]
                    val nextIsConsonant = isPersianConsonant(nextCh)
                    if (curIsConsonant && nextIsConsonant && (i % 2 == 0)) {
                        sb.append("e")
                    }
                }
            }
            outWords.add(sb.toString())
        }
        return outWords.joinToString(" ")
    }

    private fun isPersianConsonant(c: Char): Boolean {
        return c !in charArrayOf('آ', 'ا', 'و', 'ی', 'ي', 'َ', 'ِ', 'ُ', ' ')
    }

    private fun getGoogleLangCode(langName: String): String {
        return when (langName) {
            "انگلیسی" -> "en"
            "عربی" -> "ar"
            "ترکی استانبولی" -> "tr"
            "فرانسوی" -> "fr"
            "آلمانی" -> "de"
            else -> "tr"
        }
    }

    private fun synthesizeGoogleWaveNetMp3(text: String, langCode: String): ByteArray? {
        return try {
            val encoded = URLEncoder.encode(text.take(200), "UTF-8")
            val url = "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=gtx&tl=" + langCode + "&q=" + encoded
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun synthesizeGoogleGtxPersianMp3(text: String, targetLang: String): ByteArray? {
        return try {
            val tl = if (targetLang == "فارسی") "ur" else getGoogleLangCode(targetLang)
            val encoded = URLEncoder.encode(text.take(180), "UTF-8")
            val url = "https://translate.googleapis.com/translate_tts?ie=UTF-8&client=tw-ob&tl=" + tl + "&q=" + encoded
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun generateSecMsGec(): String {
        val unixSeconds = System.currentTimeMillis() / 1000L
        var ticks = unixSeconds + 11644473600L
        ticks -= (ticks % 300L)
        val fileTimeTicks = ticks * 10_000_000L
        val strToHash = fileTimeTicks.toString() + "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun getEdgeVoiceName(langName: String, voiceStyle: String): Pair<String, String> {
        val isMale = voiceStyle.contains("مرد") || voiceStyle.contains("فرید")
        return when (langName) {
            "فارسی" -> if (isMale) Pair("fa-IR", "fa-IR-FaridNeural") else Pair("fa-IR", "fa-IR-DilaraNeural")
            "انگلیسی" -> if (isMale) Pair("en-US", "en-US-GuyNeural") else Pair("en-US", "en-US-AriaNeural")
            "عربی" -> if (isMale) Pair("ar-SA", "ar-SA-HamedNeural") else Pair("ar-SA", "ar-SA-ZariyahNeural")
            "ترکی استانبولی" -> if (isMale) Pair("tr-TR", "tr-TR-AhmetNeural") else Pair("tr-TR", "tr-TR-EmelNeural")
            "فرانسوی" -> if (isMale) Pair("fr-FR", "fr-FR-HenriNeural") else Pair("fr-FR", "fr-FR-DeniseNeural")
            "آلمانی" -> if (isMale) Pair("de-DE", "de-DE-ConradNeural") else Pair("de-DE", "de-DE-KatjaNeural")
            else -> if (isMale) Pair("fa-IR", "fa-IR-FaridNeural") else Pair("fa-IR", "fa-IR-DilaraNeural")
        }
    }

    private fun synthesizeEdgeNeuralMp3(text: String, targetLang: String, voiceStyle: String): ByteArray? {
        return try {
            val pair = getEdgeVoiceName(targetLang, voiceStyle)
            val langLocale = pair.first
            val voiceName = pair.second
            val secMsGec = generateSecMsGec()
            val connId = UUID.randomUUID().toString().replace("-", "")
            val muid = UUID.randomUUID().toString().replace("-", "").uppercase(Locale.US)
            val wsUrl = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                    "?TrustedClientToken=6A5AA1D4EAFF4E9FB37E23D68491D6F4" +
                    "&Sec-MS-GEC=" + secMsGec +
                    "&Sec-MS-GEC-Version=1-130.0.2849.68" +
                    "&ConnectionId=" + connId

            val request = Request.Builder()
                .url(wsUrl)
                .header("Pragma", "no-cache")
                .header("Cache-Control", "no-cache")
                .header("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 Edg/130.0.0.0")
                .header("Cookie", "MUID=" + muid + ";")
                .build()

            val audioOut = ByteArrayOutputStream()
            val latch = CountDownLatch(1)
            val escapedText = text.take(240)
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")

            val configJson = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("synthesis", JSONObject().apply {
                        put("audio", JSONObject().apply {
                            put("metadataoptions", JSONObject().apply {
                                put("sentenceBoundaryEnabled", "false")
                                put("wordBoundaryEnabled", "false")
                            })
                            put("outputFormat", "audio-24khz-48kbitrate-mono-mp3")
                        })
                    })
                })
            }.toString()

            val ws = httpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    // CRITICAL FIX: Set UTC timezone explicitly so Iran (+03:30) local time is not sent as GMT+0000!
                    val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US).apply {
                        timeZone = TimeZone.getTimeZone("UTC")
                    }
                    val timestamp = sdf.format(Date())
                    val crlf = "\r\n"
                    val configMsg = "X-Timestamp:" + timestamp + crlf +
                            "Content-Type:application/json; charset=utf-8" + crlf +
                            "Path:speech.config" + crlf + crlf +
                            configJson
                    webSocket.send(configMsg)

                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    val ssmlBody = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='" + langLocale + "'>" +
                            "<voice name='" + voiceName + "'><prosody pitch='+0Hz' rate='+4%' volume='+0%'>" + escapedText + "</prosody></voice></speak>"
                    val ssmlMsg = "X-RequestId:" + reqId + crlf +
                            "Content-Type:application/ssml+xml" + crlf +
                            "X-Timestamp:" + timestamp + "Z" + crlf +
                            "Path:ssml" + crlf + crlf +
                            ssmlBody
                    webSocket.send(ssmlMsg)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    val arr = bytes.toByteArray()
                    if (arr.size > 2) {
                        val headerLen = ((arr[0].toInt() and 0xFF) shl 8) or (arr[1].toInt() and 0xFF)
                        if (arr.size > 2 + headerLen) {
                            val headerStr = String(arr, 2, headerLen, Charsets.UTF_8)
                            if (headerStr.contains("Path:audio")) {
                                audioOut.write(arr, 2 + headerLen, arr.size - (2 + headerLen))
                            }
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) {
                        webSocket.close(1000, null)
                        latch.countDown()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    latch.countDown()
                }
            })

            latch.await(2800, TimeUnit.MILLISECONDS)
            ws.cancel()
            val result = audioOut.toByteArray()
            if (result.size > 300) result else null
        } catch (_: Exception) {
            null
        }
    }

    // Clean, distortion-free MP3 playback at natural 1.0x speed!
    private suspend fun playMp3Blocking(mp3Bytes: ByteArray, dubVolume: Float, origVolume: Float) {
        val tempFile = File(context.cacheDir, "dub_" + System.currentTimeMillis() + ".mp3")
        val doneLatch = CountDownLatch(1)
        var mpInstance: MediaPlayer? = null
        var enhancerInstance: LoudnessEnhancer? = null

        try {
            FileOutputStream(tempFile).use { it.write(mp3Bytes) }

            // If Original Video Volume is muted (<= 3%), play on USAGE_ALARM so it is loud even while STREAM_MUSIC is 0!
            // Otherwise play on USAGE_MEDIA (which works on both phone speaker and Bluetooth headphones)!
            val audioUsage = if (origVolume <= 0.03f) {
                AudioAttributes.USAGE_ALARM
            } else {
                AudioAttributes.USAGE_MEDIA
            }

            mainHandler.post {
                try {
                    mpInstance = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(audioUsage)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        setDataSource(tempFile.absolutePath)
                        val vol = dubVolume.coerceIn(0.1f, 1f)
                        setVolume(vol, vol)
                        prepare()

                        // Gentle, clean gain boost (max +350 mB = +3.5 dB) so voice is loud WITHOUT digital clipping/distortion!
                        if (dubVolume > 0.6f) {
                            try {
                                enhancerInstance = LoudnessEnhancer(audioSessionId).apply {
                                    val boostMb = ((dubVolume - 0.6f) * 850).toInt()
                                    setTargetGain(boostMb)
                                    enabled = true
                                }
                            } catch (_: Exception) {}
                        }

                        setOnCompletionListener { doneLatch.countDown() }
                        setOnErrorListener { _, _, _ ->
                            doneLatch.countDown()
                            true
                        }
                        start()
                    }
                } catch (_: Exception) {
                    doneLatch.countDown()
                }
            }

            withContext(Dispatchers.IO) {
                doneLatch.await(12, TimeUnit.SECONDS)
            }
        } catch (_: Exception) {
        } finally {
            mainHandler.post {
                try {
                    enhancerInstance?.release()
                    mpInstance?.release()
                } catch (_: Exception) {}
            }
            tempFile.delete()
        }
    }

    private suspend fun playLocalAndroidTtsBlocking(text: String, targetLang: String, dubVolume: Float, origVolume: Float) {
        if (!localTtsReady) return
        val locale = when (targetLang) {
            "فارسی" -> Locale("fa", "IR")
            "انگلیسی" -> Locale.US
            "عربی" -> Locale("ar")
            "ترکی استانبولی" -> Locale("tr", "TR")
            "فرانسوی" -> Locale.FRENCH
            "آلمانی" -> Locale.GERMAN
            else -> Locale("fa", "IR")
        }
        val audioUsage = if (origVolume <= 0.03f) AudioAttributes.USAGE_ALARM else AudioAttributes.USAGE_MEDIA
        localTts?.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(audioUsage)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val res = localTts?.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
            localTts?.setLanguage(Locale("tr", "TR"))
            val phonetic = persianToTurkishPhonetic(text)
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0.1f, 1f))
            localTts?.speak(phonetic, TextToSpeech.QUEUE_ADD, params, "utt_" + System.currentTimeMillis())
        } else {
            val params = Bundle()
            params.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, dubVolume.coerceIn(0.1f, 1f))
            localTts?.speak(text, TextToSpeech.QUEUE_ADD, params, "utt_" + System.currentTimeMillis())
        }
        val estMs = (text.length * 60L).coerceIn(1000L, 5000L)
        delay(estMs)
    }

    fun stopAll() {
        restoreInitialVolumes()
        mainHandler.post {
            try {
                localTts?.stop()
            } catch (_: Exception) {}
        }
    }

    fun shutdown() {
        stopAll()
        playbackQueue.close()
        scope.cancel()
        localTts?.shutdown()
    }
}
