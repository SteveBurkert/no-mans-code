package depgraph

import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class Camera {

    val position = Vector3f()
    var yaw = 0f
    var pitch = 0f

    private val flying: Boolean get() = flightElapsed < flightDuration

    private val forwardVector = Vector3f()
    private val rightVector = Vector3f()
    private val upVector = Vector3f(0f, 1f, 0f)
    private val focusPoint = Vector3f()
    private val viewMatrix = Matrix4f()

    private val flightFrom = Vector3f()
    private val flightTo = Vector3f()
    private var flightFromYaw = 0f
    private var flightToYaw = 0f
    private var flightFromPitch = 0f
    private var flightToPitch = 0f
    private var flightElapsed = 0f
    private var flightDuration = 0f

    fun forward(): Vector3f =
        forwardVector.set(cos(pitch) * sin(yaw), sin(pitch), -cos(pitch) * cos(yaw))

    fun right(): Vector3f = rightVector.set(cos(yaw), 0f, sin(yaw))

    fun view(): Matrix4f =
        viewMatrix.setLookAt(position, focusPoint.set(position).add(forward()), upVector)

    fun turn(deltaYaw: Float, deltaPitch: Float) {
        cancelFlight()
        yaw += deltaYaw
        pitch = (pitch + deltaPitch).coerceIn(-PITCH_LIMIT, PITCH_LIMIT)
    }

    fun move(direction: Vector3f, distance: Float) {
        cancelFlight()
        position.fma(distance, direction)
    }

    /** Approaches [target] head on, stopping [distance] short of it and turning to face it. */
    fun flyTo(target: Vector3f, distance: Float, seconds: Float) {
        val approach = Vector3f(target).sub(position)
        if (approach.length() < 1e-4f) approach.set(forward()) else approach.normalize()

        flightFrom.set(position)
        flightTo.set(target).fma(-distance, approach)
        flightFromYaw = yaw
        flightFromPitch = pitch
        flightToYaw = atan2(approach.x, -approach.z)
        flightToPitch = asin(approach.y.coerceIn(-1f, 1f))
        flightElapsed = 0f
        flightDuration = seconds
    }

    fun update(seconds: Float) {
        if (!flying) return
        flightElapsed += seconds
        val progress = (flightElapsed / flightDuration).coerceIn(0f, 1f)
        val eased = progress * progress * (3f - 2f * progress)
        position.set(flightFrom).lerp(flightTo, eased)
        yaw = flightFromYaw + shortestTurn(flightFromYaw, flightToYaw) * eased
        pitch = flightFromPitch + (flightToPitch - flightFromPitch) * eased
    }

    fun cancelFlight() {
        flightDuration = 0f
        flightElapsed = 0f
    }

    /** Turns to face [target] without moving, ending any fly-to in progress. */
    fun pointAt(target: Vector3f) {
        cancelFlight()
        val dx = target.x - position.x
        val dy = target.y - position.y
        val dz = target.z - position.z
        val length = sqrt(dx * dx + dy * dy + dz * dz)
        if (length < 1e-4f) return
        yaw = atan2(dx, -dz)
        pitch = asin((dy / length).coerceIn(-1f, 1f))
    }

    private fun shortestTurn(from: Float, to: Float): Float {
        var delta = (to - from) % TWO_PI
        if (delta > PI) delta -= TWO_PI
        if (delta < -PI) delta += TWO_PI
        return delta
    }

    private companion object {
        const val PI = kotlin.math.PI.toFloat()
        const val TWO_PI = (2.0 * kotlin.math.PI).toFloat()
        const val PITCH_LIMIT = (kotlin.math.PI / 2.0 - 0.02).toFloat()
    }
}
