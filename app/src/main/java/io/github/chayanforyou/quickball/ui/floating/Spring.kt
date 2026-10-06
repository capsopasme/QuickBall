package io.github.chayanforyou.quickball.ui.floating

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A minimal damped spring (unit mass) for the overlay views.
 *
 * Views step their springs from onDraw with the frame time and only request another frame while
 * something is still moving, so an idle overlay costs nothing and there is no animator object or
 * Choreographer callback per value.
 */
class Spring(
    initial: Float = 0f,
    var stiffness: Float = 400f,
    var dampingRatio: Float = 0.8f,
) {
    var value: Float = initial
        private set
    var velocity: Float = 0f
        private set
    var target: Float = initial
        private set

    /** Distance from the target under which the spring counts as settled. */
    var restThreshold: Float = 0.0005f

    val isAtRest: Boolean get() = value == target && velocity == 0f

    fun animateTo(target: Float) {
        this.target = target
    }

    fun animateTo(target: Float, stiffness: Float, dampingRatio: Float) {
        this.target = target
        this.stiffness = stiffness
        this.dampingRatio = dampingRatio
    }

    fun snapTo(value: Float) {
        this.value = value
        this.target = value
        this.velocity = 0f
    }

    /** Adds velocity in units per second, e.g. a bounce when a limit is hit. */
    fun kick(velocity: Float) {
        this.velocity += velocity
    }

    /** Advances the spring by [dtSeconds]; returns true while it is still moving. */
    fun step(dtSeconds: Float): Boolean {
        if (isAtRest) return false
        // Semi-implicit Euler in small sub-steps stays stable for stiff springs; a long frame
        // (e.g. the first one after the window appears) is capped instead of jumping.
        var remaining = dtSeconds.coerceIn(0f, MAX_FRAME_SECONDS)
        val damping = 2f * dampingRatio * sqrt(stiffness)
        while (remaining > 0f) {
            val h = if (remaining < SUB_STEP_SECONDS) remaining else SUB_STEP_SECONDS
            val force = -stiffness * (value - target) - damping * velocity
            velocity += force * h
            value += velocity * h
            remaining -= h
        }
        if (abs(value - target) < restThreshold && abs(velocity) < restThreshold * 20f) {
            value = target
            velocity = 0f
            return false
        }
        return true
    }

    private companion object {
        const val MAX_FRAME_SECONDS = 0.05f
        const val SUB_STEP_SECONDS = 1f / 480f
    }
}

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

/** 0 below [edge0], 1 above [edge1], smooth in between. */
internal fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
