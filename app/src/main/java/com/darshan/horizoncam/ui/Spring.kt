package com.darshan.horizoncam.ui

import android.animation.ValueAnimator
import android.content.Context
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import com.darshan.horizoncam.R
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/** Spring parameters in Apple's designer terms: [response] in seconds and [damping] ratio. */
class SpringSpec(val response: Float, val damping: Float)

/** The springs used across the app (see docs/DESIGN.md › Motion). */
object Springs {
    /** Default for anything that isn't thrown: critically damped, no overshoot. */
    val DEFAULT = SpringSpec(0.35f, 1f)
    val PRESS_DOWN = SpringSpec(0.18f, 1f)
    val PRESS_UP = SpringSpec(0.3f, 1f)
    /** After a flick or a thrown release: a little overshoot because the gesture carried momentum. */
    val FLICK = SpringSpec(0.35f, 0.85f)
    /** Controls turning with the phone (Apple's rotation preset). */
    val ROTATION = SpringSpec(0.4f, 0.8f)
    val QUICK = SpringSpec(0.3f, 1f)
}

/** System motion preferences. Refresh when the activity resumes. */
object Motion {
    /** False when the person turned animations off (Accessibility › Remove animations). */
    @Volatile var enabled = true
        private set
    @Volatile var durationScale = 1f
        private set

    fun refresh(context: Context) {
        enabled = try { ValueAnimator.areAnimatorsEnabled() } catch (e: Throwable) { true }
        val scale = try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        } catch (e: Throwable) { 1f }
        durationScale = if (scale > 0f) scale.coerceAtMost(10f) else 1f
        if (scale <= 0f) enabled = false
    }

    /**
     * Momentum projection: where a flick would come to rest, using Apple's deceleration model.
     * [velocity] is in units per second.
     */
    fun project(velocity: Float, decelerationRate: Float = 0.998f): Float =
        velocity / 1000f * decelerationRate / (1f - decelerationRate)

    /** Progressive resistance past a boundary (rubber-banding). */
    fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        val sign = if (overshoot < 0) -1f else 1f
        val o = abs(overshoot)
        return sign * (o * dimension * constant) / (dimension + constant * o)
    }
}

/**
 * A damped spring solved analytically on each display frame. It always continues from its
 * current value and velocity, so every animation can be interrupted and re-targeted without a
 * jump or a velocity "brick wall".
 */
class Spring(
    private val read: (() -> Float)?,
    private val write: (Float) -> Unit,
    private val threshold: Float
) : Choreographer.FrameCallback {

    var value = 0f
        private set
    var velocity = 0f
        private set
    var target = 0f
        private set
    val isRunning: Boolean get() = running

    private var response = 0.35f
    private var damping = 1f
    private var running = false
    private var lastFrameNs = 0L
    private var endAction: (() -> Unit)? = null

    init {
        value = read?.invoke() ?: 0f
        target = value
    }

    /** Jumps to [v] with no animation (also used to follow a finger 1:1). */
    fun snapTo(v: Float) {
        stop()
        value = v
        target = v
        velocity = 0f
        write(v)
    }

    fun animateTo(target: Float, spec: SpringSpec = Springs.DEFAULT, velocity: Float? = null, onEnd: (() -> Unit)? = null) {
        if (!running) {
            // Start from what is on screen now (it may have been changed directly).
            read?.invoke()?.let { value = it }
            this.velocity = 0f
        }
        if (velocity != null) this.velocity = velocity
        this.target = target
        response = spec.response * Motion.durationScale
        damping = spec.damping
        endAction = onEnd
        if (!Motion.enabled || response <= 0f) {
            finish()
            return
        }
        if (!running) {
            running = true
            lastFrameNs = 0L
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun stop() {
        if (running) Choreographer.getInstance().removeFrameCallback(this)
        running = false
        endAction = null
    }

    private fun finish() {
        val end = endAction
        stop()
        value = target
        velocity = 0f
        write(target)
        end?.invoke()
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val dt = if (lastFrameNs == 0L) 1f / 60f else ((frameTimeNanos - lastFrameNs) / 1e9f).coerceIn(0f, 1f / 30f)
        lastFrameNs = frameTimeNanos
        step(dt)
        if (abs(value - target) < threshold && abs(velocity) < threshold * 12f) {
            finish()
            return
        }
        write(value)
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun step(dt: Float) {
        val w0 = (2.0 * PI / response).toFloat()
        val x0 = value - target
        val v0 = velocity
        val x: Float
        val v: Float
        if (damping >= 1f) {
            val e = exp(-w0 * dt)
            val b = v0 + w0 * x0
            x = (x0 + b * dt) * e
            v = (b - w0 * (x0 + b * dt)) * e
        } else {
            val z = damping
            val wd = w0 * sqrt(1f - z * z)
            val e = exp(-z * w0 * dt)
            val b = (v0 + z * w0 * x0) / wd
            val c = cos(wd * dt)
            val s = sin(wd * dt)
            x = e * (x0 * c + b * s)
            v = e * ((-z * w0 * x0 + wd * b) * c + (-z * w0 * b - wd * x0) * s)
        }
        value = target + x
        velocity = v
    }
}

/** View properties that can be driven by a spring. */
enum class SpringProp { X, Y, SCALE, ALPHA, ROTATION }

private fun tagFor(p: SpringProp) = when (p) {
    SpringProp.X -> R.id.spring_x
    SpringProp.Y -> R.id.spring_y
    SpringProp.SCALE -> R.id.spring_scale
    SpringProp.ALPHA -> R.id.spring_alpha
    SpringProp.ROTATION -> R.id.spring_rotation
}

/** The spring that drives [p] on this view (created on first use). */
fun View.spring(p: SpringProp): Spring {
    val key = tagFor(p)
    (getTag(key) as? Spring)?.let { return it }
    val d = resources.displayMetrics.density
    val s = when (p) {
        SpringProp.X -> Spring({ translationX }, { translationX = it }, 0.3f * d)
        SpringProp.Y -> Spring({ translationY }, { translationY = it }, 0.3f * d)
        SpringProp.SCALE -> Spring({ scaleX }, { scaleX = it; scaleY = it }, 0.0015f)
        SpringProp.ALPHA -> Spring({ alpha }, { alpha = it }, 0.004f)
        SpringProp.ROTATION -> Spring({ rotation }, { rotation = it }, 0.05f)
    }
    setTag(key, s)
    return s
}

fun View.springTo(p: SpringProp, target: Float, spec: SpringSpec = Springs.DEFAULT, velocity: Float? = null, onEnd: (() -> Unit)? = null) =
    spring(p).animateTo(target, spec, velocity, onEnd)

/**
 * Press feedback on touch-down (not on release): the control springs to [pressedScale] while the
 * finger is down and springs back when it lifts or slides away. Clicks still work as usual.
 */
fun View.pressFeedback(pressedScale: Float = 0.92f) {
    setOnTouchListener { v, e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (v.isEnabled) v.springTo(SpringProp.SCALE, pressedScale, Springs.PRESS_DOWN)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.springTo(SpringProp.SCALE, 1f, Springs.PRESS_UP)
        }
        false
    }
}

/** Fades a control in or out with a spring, keeping its place in the layout. */
fun View.fadeTo(show: Boolean, gone: Boolean = false) {
    if (show) {
        if (visibility != View.VISIBLE) {
            alpha = 0f
            visibility = View.VISIBLE
        }
        isEnabled = true
        springTo(SpringProp.ALPHA, 1f, Springs.QUICK)
    } else {
        isEnabled = false
        if (visibility == View.VISIBLE) {
            springTo(SpringProp.ALPHA, 0f, Springs.QUICK) { visibility = if (gone) View.GONE else View.INVISIBLE }
        }
    }
}
