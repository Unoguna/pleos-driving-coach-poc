package com.example.pleos_driving_coach_poc.speech

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.car.Car
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import com.example.pleos_driving_coach_poc.vehicle.SpeedReader
import java.util.Locale
import kotlin.math.abs

internal class SpeechProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var speedReader: SpeedReader? = null
    private var speed: Float? = null
    private var speedTime = 0L
    private var running = false
    private var closed = false
    private var finished = false
    private var attempt = 0
    private var speakerType: String? = null
    private var voiceTrial = false
    private var totalAttempts = 3
    private val voiceLabel get() = speakerType?.let { "요청 ID=$it" } ?: "목소리 ID 미지정"
    private var startSpeed = "속도 미확인"
    private val audioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val audioManager by lazy { getSystemService(AudioManager::class.java) }
    private val focusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(audioAttributes)
            .setOnAudioFocusChangeListener({ change ->
                if (change != AudioManager.AUDIOFOCUS_GAIN) onMain {
                    fail("오디오 포커스 상실 · 재생 종료 ($change)")
                }
            }, handler).build()
    }
    private val requestSpeech = Runnable {
        if (!closed) {
            attempt++
            if (voiceTrial) voiceResults[speakerType.orEmpty()] = "음성 요청 중"
            report("$attempt/$totalAttempts 음성 요청 · $voiceLabel · ${speedDescription()}")
            try {
                val focus = audioManager.requestAudioFocus(focusRequest)
                Log.i(TAG, "AUDIO_FOCUS result=$focus")
                if (focus != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    fail("오디오 포커스 요청 거절 · 음성 재생하지 않음")
                    return@Runnable
                }
                val text = if (voiceTrial) "안녕하세요. 나의 운전 선생님입니다. 부드럽게 출발하고 천천히 감속해 주세요."
                    else "음성 안내 시험 ${attempt}회차입니다. 부드럽게 감속해 주세요."
                tts?.speak(text)
            } catch (error: RuntimeException) {
                fail("음성 요청 실패: ${error.message}")
            }
        }
    }
    private val listener = object : EventListener {
        override fun onReady() { onMain { Log.i(TAG, "READY") } }
        override fun onUpdatedRms(rms: Double) = Unit
        override fun onStart() { onMain {
            startSpeed = speedDescription()
            result = "$attempt/$totalAttempts 재생 시작 · $voiceLabel · $startSpeed"
            if (voiceTrial) voiceResults[speakerType.orEmpty()] = "재생 시작 · $startSpeed"
            report(result)
        } }
        override fun onDone() { onMain {
            audioManager.abandonAudioFocusRequest(focusRequest)
            result = "$attempt/$totalAttempts 재생 완료 · $voiceLabel · 시작 시 $startSpeed"
            if (voiceTrial) voiceResults[speakerType.orEmpty()] = "재생 완료 · 실제 목소리 차이는 청취 확인 필요"
            report(result)
            if (attempt < totalAttempts) {
                report("다음 안내 10초 후 · $attempt/$totalAttempts 완료")
                handler.postDelayed(requestSpeech, 10_000)
            } else {
                finished = true
                report("${totalAttempts}회 시험 완료 · 실제 소리 청취 여부 확인")
                stopSelf()
            }
        } }
        override fun onStop() { onMain { fail("음성 재생 중단") } }
        override fun onError(errorType: ErrorType) { onMain { fail("TTS 오류: $errorType") } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (running) { report("이미 시험 실행 중 · $voiceLabel · $attempt/$totalAttempts · 종료 후 다시 선택"); return START_NOT_STICKY }
        running = true
        voiceTrial = intent.getBooleanExtra(EXTRA_VOICE_TRIAL, false)
        speakerType = intent.getStringExtra(EXTRA_SPEAKER_TYPE)?.trim()?.takeIf { it.isNotEmpty() }
        totalAttempts = if (voiceTrial) 1 else 3
        if (voiceTrial) voiceResults[speakerType.orEmpty()] = "초기화 대기 · 지원 여부 미확인"
        result = "재생 이벤트 대기"
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "음성 시험 실행 상태",
                NotificationManager.IMPORTANCE_LOW))
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("주행 중 음성 안내 시험")
                .setContentText("${totalAttempts}회 안내 후 자동 종료 · 최대 90초")
                .setOngoing(true).build()
            startForeground(203, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
            if (voiceTrial && speakerType !in KOREAN_SPEAKERS) {
                fail("한국어 시험에 지원하지 않는 ID: $speakerType")
                return START_NOT_STICKY
            }
            handler.postDelayed({ fail("90초 제한으로 시험 종료 · 완료 콜백 확인 필요") }, 90_000)
            if (checkSelfPermission(Car.PERMISSION_SPEED) == PackageManager.PERMISSION_GRANTED) {
                speedReader = SpeedReader(this, onValue = { value -> onMain {
                    speed = value
                    speedTime = SystemClock.elapsedRealtime()
                } }, onStatus = { Log.i(TAG, "SPEED $it") }).also { it.start() }
            }
            if (checkSelfPermission("pleos.car.permission.TTS_SERVICE") != PackageManager.PERMISSION_GRANTED) {
                fail("Pleos TTS_SERVICE 권한 없음")
                return START_NOT_STICKY
            }
            report("Pleos TTS 초기화 중 · ON_DEVICE")
            val engine = TextToSpeech(applicationContext, Mode.ON_DEVICE)
            tts = engine
            engine.initialize(object : OnInitializeListener {
                override fun onSuccess() { onMain {
                    try {
                        engine.addEventListener(listener)
                        engine.setLocale(Locale.KOREA)
                        engine.setAudioAttributes(audioAttributes)
                        // This setter has no success callback or supported-ID query API.
                        // Playback completion alone does not prove the engine changed voices.
                        speakerType?.let {
                            engine.setSpeakerType(it)
                            Log.i(TAG, "SET_SPEAKER_TYPE requested=$it locale=ko-KR mode=ON_DEVICE")
                        }
                        val delay = if (voiceTrial) 2_000L else 10_000L
                        report("준비 완료 · ${delay / 1_000}초 후 안내 · $voiceLabel")
                        handler.postDelayed(requestSpeech, delay)
                    } catch (error: RuntimeException) {
                        fail("TTS 설정 실패: ${error.message}")
                    }
                } }
                override fun onFailure(errorType: ErrorType) { onMain { fail("TTS 초기화 실패: $errorType") } }
            })
        } catch (error: RuntimeException) {
            fail("음성 시험 실패: ${error.message}")
        }
        return START_NOT_STICKY
    }

    private fun speedDescription(): String {
        val value = speed
        if (value == null || SystemClock.elapsedRealtime() - speedTime > 3_000) return "속도 미확인"
        val state = if (abs(value) > 0.1f) "이동 중" else "정차 추정"
        return String.format(Locale.ROOT, "%s %.1f km/h", state, value * 3.6)
    }

    private fun onMain(block: () -> Unit) {
        handler.post { if (!closed && !finished) block() }
    }

    private fun fail(message: String) {
        finished = true
        if (voiceTrial) voiceResults[speakerType.orEmpty()] = message
        result = message
        report(message)
        stopSelf()
    }

    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        try {
            speedReader?.close()
        } finally {
            try {
                tts?.let { engine ->
                    try { engine.removeEventListener(listener) }
                    finally { try { engine.stop() } finally { engine.release() } }
                }
            } catch (error: RuntimeException) {
                Log.w(TAG, "TTS cleanup failed", error)
            } finally {
                audioManager.abandonAudioFocusRequest(focusRequest)
                if (!finished) {
                    if (voiceTrial) voiceResults[speakerType.orEmpty()] = "시험 중단 · 재생 완료 미확인"
                    report("사용자 또는 시스템에 의해 시험 종료")
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                super.onDestroy()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "SpeechProbe"
        private const val CHANNEL = "speech_probe_service"
        const val EXTRA_VOICE_TRIAL = "voice_trial"
        const val EXTRA_SPEAKER_TYPE = "speaker_type"
        val KOREAN_SPEAKERS = listOf("seoyoon", "eunwoo", "haeun", "sanghyeok")
        private val voiceResults = java.util.concurrent.ConcurrentHashMap<String, String>()

        fun voiceResult(speakerType: String) = voiceResults[speakerType] ?: "미실행"
        @Volatile var status = "미실행"
            private set
        @Volatile var result = "재생 기록 없음"
            private set

        fun report(message: String) {
            status = message
            Log.i(TAG, message)
        }
    }
}
