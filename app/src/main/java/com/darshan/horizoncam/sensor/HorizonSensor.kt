package com.darshan.horizoncam.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Tracks the direction of "world up" in device coordinates using the gyro-fused
 * game rotation vector (falls back to gravity / accelerometer). Samples are kept
 * in a timestamped ring buffer so camera frames can be matched to the exact
 * device orientation at exposure time.
 *
 * All timestamps exposed by this class are in the elapsedRealtimeNanos() time base.
 */
class HorizonSensor(context: Context) : SensorEventListener {

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor?
    val sensorName: String
    val sensorType: Int

    private val size = 1024
    private val ts = LongArray(size)
    private val ux = FloatArray(size)
    private val uy = FloatArray(size)
    private val uz = FloatArray(size)
    private var head = -1
    private var count = 0
    private val lock = Any()

    private val rot = FloatArray(9)
    private val lp = FloatArray(3)
    private var lpInit = false

    /** Offset added to event timestamps to convert them to elapsedRealtimeNanos. */
    private var clockOffset = 0L
    private var clockChecked = false

    @Volatile var latestUx = 0f; private set
    @Volatile var latestUy = 1f; private set
    @Volatile var latestUz = 0f; private set
    @Volatile var hasData = false; private set

    private var thread: HandlerThread? = null
    private var running = false

    init {
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val grav = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensor = game ?: grav ?: acc
        sensorType = sensor?.type ?: -1
        sensorName = when (sensorType) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> "Gyro fusion (game rotation vector)"
            Sensor.TYPE_GRAVITY -> "Gravity sensor"
            Sensor.TYPE_ACCELEROMETER -> "Accelerometer (no gyro)"
            else -> "None"
        } + (sensor?.let { " · ${it.name}" } ?: "")
    }

    val available: Boolean get() = sensor != null

    fun start() {
        if (running || sensor == null) return
        running = true
        val t = HandlerThread("HorizonSensor").also { it.start() }
        thread = t
        clockChecked = false
        lpInit = false
        // 200 Hz is the maximum allowed without the HIGH_SAMPLING_RATE_SENSORS permission.
        sm.registerListener(this, sensor, 5000, Handler(t.looper))
    }

    fun stop() {
        if (!running) return
        running = false
        sm.unregisterListener(this)
        thread?.quitSafely()
        thread = null
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        var x: Float
        var y: Float
        var z: Float
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                try {
                    SensorManager.getRotationMatrixFromVector(rot, e.values)
                } catch (ex: IllegalArgumentException) {
                    // Some devices report 5 values; use the first 4.
                    SensorManager.getRotationMatrixFromVector(rot, e.values.copyOf(4))
                }
                // World Z (up) expressed in device coordinates is the third row of R.
                x = rot[6]; y = rot[7]; z = rot[8]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!lpInit) {
                    lp[0] = e.values[0]; lp[1] = e.values[1]; lp[2] = e.values[2]; lpInit = true
                } else {
                    val a = 0.12f
                    lp[0] += (e.values[0] - lp[0]) * a
                    lp[1] += (e.values[1] - lp[1]) * a
                    lp[2] += (e.values[2] - lp[2]) * a
                }
                x = lp[0]; y = lp[1]; z = lp[2]
            }
            else -> { x = e.values[0]; y = e.values[1]; z = e.values[2] }
        }
        val n = sqrt(x * x + y * y + z * z)
        if (n < 1e-6f) return
        x /= n; y /= n; z /= n

        if (!clockChecked) {
            val rt = SystemClock.elapsedRealtimeNanos()
            val mono = System.nanoTime()
            clockOffset = if (abs(rt - e.timestamp) <= abs(mono - e.timestamp)) 0L else rt - mono
            clockChecked = true
        }
        val t = e.timestamp + clockOffset
        synchronized(lock) {
            head = (head + 1) % size
            ts[head] = t; ux[head] = x; uy[head] = y; uz[head] = z
            if (count < size) count++
        }
        latestUx = x; latestUy = y; latestUz = z
        hasData = true
    }

    /**
     * Up vector at time [tRealtimeNs] (linear interpolation between samples).
     * Writes (x, y, z) into [out]. Returns false when no data is available.
     */
    fun upAt(tRealtimeNs: Long, out: FloatArray): Boolean {
        synchronized(lock) {
            if (count == 0) return false
            val newest = head
            if (tRealtimeNs >= ts[newest] || count == 1) {
                out[0] = ux[newest]; out[1] = uy[newest]; out[2] = uz[newest]
                return true
            }
            var i = newest
            var steps = 0
            while (steps < count - 1) {
                val prev = (i - 1 + size) % size
                if (ts[prev] <= tRealtimeNs) {
                    val t0 = ts[prev]
                    val t1 = ts[i]
                    val f = if (t1 > t0) ((tRealtimeNs - t0).toDouble() / (t1 - t0)).toFloat() else 1f
                    out[0] = ux[prev] + (ux[i] - ux[prev]) * f
                    out[1] = uy[prev] + (uy[i] - uy[prev]) * f
                    out[2] = uz[prev] + (uz[i] - uz[prev]) * f
                    return true
                }
                i = prev
                steps++
            }
            // Older than anything in the buffer: use the oldest sample.
            out[0] = ux[i]; out[1] = uy[i]; out[2] = uz[i]
            return true
        }
    }
}
