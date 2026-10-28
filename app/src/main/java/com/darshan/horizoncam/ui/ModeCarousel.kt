package com.darshan.horizoncam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Mode labels (PHOTO / VIDEO / HYPERLAPSE) on a strip that keeps the selected mode centred over the
 * shutter. Drag it and it follows the finger 1:1; let go and it projects the flick's momentum and
 * snaps to the nearest mode with a spring that carries the release velocity. Tapping a label still
 * works, and TalkBack sees ordinary buttons with a selected state.
 */
class ModeCarousel @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {

    /** Called when the person picks mode [index] by dragging. The owner calls [select] to accept it. */
    var onPick: ((index: Int) -> Unit)? = null
    var hapticsEnabled: () -> Boolean = { true }

    var selectedIndex = 0
        private set

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val offset = Spring({ translationX }, { translationX = it; trackCentre(); fadeEdges() }, 0.3f * density)
    private var velocity: VelocityTracker? = null
    private var downX = 0f
    private var startTx = 0f
    private var dragging = false
    private var releaseVelocity = 0f
    private var centredIndex = -1

    init {
        orientation = HORIZONTAL
        clipChildren = false
    }

    /** Lets the strip be wider than the screen instead of squeezing the labels. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightMeasureSpec)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        if (!dragging && !offset.isRunning) offset.snapTo(offsetFor(selectedIndex))
        fadeEdges()
    }

    /** Labels fade out as they reach the screen edge instead of being cut off. */
    private fun fadeEdges() {
        val p = parent as? View ?: return
        val w = p.width.toFloat()
        if (w <= 0f) return
        val ramp = 56f * density
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val l = left + translationX + c.left
            val r = l + c.width
            val inset = minOf(l, w - r)
            c.alpha = if (inset >= 0f) 1f else (1f + inset / ramp).coerceIn(0.15f, 1f)
        }
    }

    /** translationX that puts child [i] in the middle of the parent. */
    private fun offsetFor(i: Int): Float {
        val p = parent as? View ?: return 0f
        val c = getChildAt(i.coerceIn(0, childCount - 1)) ?: return 0f
        return p.width / 2f - (left + c.left + c.width / 2f)
    }

    /** Selects mode [index]; animates there unless [animate] is false. */
    fun select(index: Int, animate: Boolean = true) {
        selectedIndex = index.coerceIn(0, childCount - 1)
        for (i in 0 until childCount) getChildAt(i).isSelected = i == selectedIndex
        if (width == 0 || !animate) {
            offset.snapTo(offsetFor(selectedIndex))
        } else {
            val v = releaseVelocity
            releaseVelocity = 0f
            offset.animateTo(offsetFor(selectedIndex), if (abs(v) > 300f) Springs.FLICK else Springs.DEFAULT, if (v != 0f) v else null)
        }
    }

    /** A detent tick each time a new label passes the centre while dragging. */
    private fun trackCentre() {
        if (!dragging) return
        val i = nearestTo(translationX)
        if (i != centredIndex) {
            if (centredIndex >= 0 && hapticsEnabled()) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            centredIndex = i
        }
    }

    private fun nearestTo(tx: Float): Int {
        var best = selectedIndex
        var bestD = Float.MAX_VALUE
        for (i in 0 until childCount) {
            val d = abs(offsetFor(i) - tx)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> if (!dragging && abs(ev.rawX - downX) > slop) {
                beginDrag(ev)
                return true
            }
        }
        return dragging
    }

    private fun beginDrag(ev: MotionEvent) {
        dragging = true
        parent?.requestDisallowInterceptTouchEvent(true)
        offset.stop()
        startTx = translationX
        downX = ev.rawX
        centredIndex = selectedIndex
        velocity?.recycle()
        velocity = VelocityTracker.obtain()
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        velocity?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && abs(ev.rawX - downX) > slop) beginDrag(ev)
                if (dragging) {
                    val min = offsetFor(childCount - 1)
                    val max = offsetFor(0)
                    var tx = startTx + (ev.rawX - downX)
                    // Resist past the first and last mode instead of stopping dead.
                    val span = (parent as? View)?.width?.toFloat() ?: width.toFloat()
                    if (tx > max) tx = max + Motion.rubberBand(tx - max, span)
                    if (tx < min) tx = min + Motion.rubberBand(tx - min, span)
                    offset.snapTo(tx)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    val vt = velocity
                    vt?.computeCurrentVelocity(1000)
                    val v = vt?.xVelocity ?: 0f
                    dragging = false
                    val target = nearestTo(translationX + Motion.project(v))
                    val before = selectedIndex
                    releaseVelocity = v
                    if (target != before) onPick?.invoke(target)
                    // Same mode, or the owner refused it (e.g. while recording): settle back.
                    if (selectedIndex == before) select(before)
                }
                velocity?.recycle()
                velocity = null
                return true
            }
        }
        return super.onTouchEvent(ev)
    }
}
