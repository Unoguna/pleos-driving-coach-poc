package com.example.pleos_driving_coach_poc.vehicle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

internal data class ImuReading(
    val name: String,
    val type: Int,
    val values: List<Float>,
    val timestampNs: Long,
    val intervalMs: Double?,
    val accuracy: Int
)

internal class ImuReader(
    context: Context,
    private val onReading: (String, ImuReading) -> Unit,
    private val onStatus: (String, String) -> Unit
) : SensorEventListener {
    private val manager = context.getSystemService(SensorManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val channels = mutableMapOf<Int, String>()
    private val previousTimestamps = mutableMapOf<Int, Long>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        if (manager == null) {
            onStatus(ACCELERATION, "센서 서비스 없음")
            onStatus(GYROSCOPE, "센서 서비스 없음")
            return
        }
        manager.getSensorList(Sensor.TYPE_ALL).forEach { sensor ->
            Log.i("ImuProbe", "SENSOR name=${sensor.name} type=${sensor.type} minDelayUs=${sensor.minDelay}")
        }
        subscribe(ACCELERATION, listOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER_UNCALIBRATED))
        subscribe(GYROSCOPE, listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GYROSCOPE_UNCALIBRATED))
    }

    private fun subscribe(channel: String, types: List<Int>) {
        val sensor = types.firstNotNullOfOrNull { manager?.getDefaultSensor(it) }
        if (sensor == null) {
            report(channel, "지원 센서 없음")
            return
        }
        try {
            channels[sensor.type] = channel
            // 50 Hz request. The actual delivery interval is measured from sensor timestamps.
            val registered = manager?.registerListener(this, sensor, 20_000, handler) == true
            if (!registered) channels.remove(sensor.type)
            report(channel, "${sensor.name} · 등록 $registered · 첫 이벤트 대기")
        } catch (error: RuntimeException) {
            channels.remove(sensor.type)
            Log.w("ImuProbe", "SUBSCRIBE failed channel=$channel", error)
            report(channel, "구독 실패: ${error.message}")
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!started) return
        val channel = channels[event.sensor.type] ?: return
        val previous = previousTimestamps.put(event.sensor.type, event.timestamp)
        val intervalMs = if (previous != null && event.timestamp > previous) {
            (event.timestamp - previous) / 1_000_000.0
        } else {
            null
        }
        // SensorEvent and its values buffer are reused by Android.
        val reading = ImuReading(
            event.sensor.name, event.sensor.type, event.values.toList(),
            event.timestamp, intervalMs, event.accuracy
        )
        Log.i("ImuProbe", String.format(Locale.ROOT,
            "EVENT channel=%s type=%d values=%s timestampNs=%d intervalMs=%s accuracy=%d",
            channel, reading.type, reading.values, reading.timestampNs, reading.intervalMs, reading.accuracy))
        onReading(channel, reading)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        Log.i("ImuProbe", "ACCURACY name=${sensor.name} type=${sensor.type} accuracy=$accuracy")
    }

    private fun report(channel: String, message: String) {
        Log.i("ImuProbe", "STATUS channel=$channel $message")
        onStatus(channel, message)
    }

    fun stop() {
        started = false
        manager?.unregisterListener(this)
        channels.clear()
        previousTimestamps.clear()
    }

    companion object {
        const val ACCELERATION = "가속도"
        const val GYROSCOPE = "자이로"
    }
}
