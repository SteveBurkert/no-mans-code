package depgraph

import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33C.*
import java.nio.FloatBuffer
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Distant stars, rotating with the camera but never getting closer. */
class StarField(private val count: Int) {

    private val program = Gl.program(VERTEX, FRAGMENT)
    private val vao = glGenVertexArrays()
    private val rotationOnly = Matrix4f()
    private val scratch = FloatArray(16)

    init {
        val random = Random(4242)
        val data = FloatArray(count * 4)
        for (index in 0 until count) {
            val height = random.nextFloat() * 2f - 1f
            val ring = sqrt((1f - height * height).coerceAtLeast(0f))
            val angle = random.nextFloat() * 2f * Math.PI.toFloat()
            data[index * 4] = cos(angle) * ring * DISTANCE
            data[index * 4 + 1] = height * DISTANCE
            data[index * 4 + 2] = sin(angle) * ring * DISTANCE
            data[index * 4 + 3] = 0.25f + random.nextFloat() * random.nextFloat()
        }
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, data, GL_STATIC_DRAW)
        Gl.attribute(0, 3, 4, 0)
        Gl.attribute(1, 1, 4, 3)
        glBindVertexArray(0)
    }

    fun draw(view: Matrix4f, projection: Matrix4f) {
        rotationOnly.set(view)
        rotationOnly.m30(0f).m31(0f).m32(0f)
        glUseProgram(program)
        glUniformMatrix4fv(glGetUniformLocation(program, "rotation"), false, rotationOnly.get(scratch))
        glUniformMatrix4fv(glGetUniformLocation(program, "projection"), false, projection.get(scratch))
        glBindVertexArray(vao)
        glDrawArrays(GL_POINTS, 0, count)
        glBindVertexArray(0)
    }

    private companion object {
        const val DISTANCE = 200f

        const val VERTEX = """
#version 330 core
layout(location = 0) in vec3 direction;
layout(location = 1) in float brightness;
uniform mat4 rotation;
uniform mat4 projection;
out float vBrightness;
void main() {
    gl_Position = projection * rotation * vec4(direction, 1.0);
    gl_PointSize = 1.0 + brightness * 2.2;
    vBrightness = brightness;
}
"""

        const val FRAGMENT = """
#version 330 core
in float vBrightness;
out vec4 fragment;
void main() {
    float d = length(gl_PointCoord - vec2(0.5)) * 2.0;
    float glow = smoothstep(1.0, 0.0, d);
    fragment = vec4(vec3(0.62, 0.72, 1.0) * glow * vBrightness, 1.0);
}
"""
    }
}

/** One additive glow billboard per file. */
class NodeLayer {

    private val program = Gl.program(VERTEX, FRAGMENT)
    private val vao = glGenVertexArrays()
    private val instances = glGenBuffers()
    private val scratch = FloatArray(16)
    private var staging: FloatBuffer = BufferUtils.createFloatBuffer(1024)
    private var count = 0

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, glGenBuffers())
        glBufferData(GL_ARRAY_BUFFER, floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f), GL_STATIC_DRAW)
        Gl.attribute(0, 2, 2, 0)
        glBindBuffer(GL_ARRAY_BUFFER, instances)
        Gl.attribute(1, 3, STRIDE, 0, perInstance = true)
        Gl.attribute(2, 1, STRIDE, 3, perInstance = true)
        Gl.attribute(3, 3, STRIDE, 4, perInstance = true)
        Gl.attribute(4, 1, STRIDE, 7, perInstance = true)
        glBindVertexArray(0)
    }

    fun upload(data: FloatArray, nodes: Int) {
        count = nodes
        staging = stage(staging, data, nodes * STRIDE)
        glBindBuffer(GL_ARRAY_BUFFER, instances)
        glBufferData(GL_ARRAY_BUFFER, staging, GL_DYNAMIC_DRAW)
    }

    fun draw(view: Matrix4f, projection: Matrix4f, viewportHeight: Int) {
        if (count == 0) return
        glUseProgram(program)
        glUniformMatrix4fv(glGetUniformLocation(program, "view"), false, view.get(scratch))
        glUniformMatrix4fv(glGetUniformLocation(program, "projection"), false, projection.get(scratch))
        glUniform1f(glGetUniformLocation(program, "viewportHeight"), viewportHeight.toFloat())
        glBindVertexArray(vao)
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count)
        glBindVertexArray(0)
    }

    private companion object {
        const val STRIDE = 8

        /**
         * A whole project spans thousands of world units, so far away files would land on a
         * fraction of a pixel. Every file is held to a minimum size on screen instead.
         */
        const val VERTEX = """
#version 330 core
layout(location = 0) in vec2 corner;
layout(location = 1) in vec3 centre;
layout(location = 2) in float size;
layout(location = 3) in vec3 colour;
layout(location = 4) in float intensity;
uniform mat4 view;
uniform mat4 projection;
uniform float viewportHeight;
const float MIN_PIXELS = 2.2;
out vec2 vCorner;
out vec3 vColour;
out float vIntensity;
void main() {
    vec4 viewPosition = view * vec4(centre, 1.0);
    float depth = max(-viewPosition.z, 0.001);
    float unitsPerPixel = 2.0 * depth / (projection[1][1] * viewportHeight);
    float radius = max(size, MIN_PIXELS * unitsPerPixel);

    vec3 right = vec3(view[0][0], view[1][0], view[2][0]);
    vec3 up    = vec3(view[0][1], view[1][1], view[2][1]);
    vec3 world = centre + (corner.x * right + corner.y * up) * radius;
    gl_Position = projection * view * vec4(world, 1.0);
    vCorner = corner;
    vColour = colour;
    vIntensity = intensity;
}
"""

        const val FRAGMENT = """
#version 330 core
in vec2 vCorner;
in vec3 vColour;
in float vIntensity;
out vec4 fragment;
void main() {
    float d = length(vCorner);
    if (d > 1.0) discard;
    float halo = pow(1.0 - d, 3.0);
    float core = pow(max(0.0, 1.0 - d * 2.4), 2.0);
    vec3 rgb = vColour * halo * 1.3 + mix(vColour, vec3(1.0), 0.72) * core * 1.7;
    fragment = vec4(rgb * vIntensity, 1.0);
}
"""
    }
}

/**
 * Dependency lines, brighter at the file that declares the import. Lines dim with distance so
 * only the wiring around the camera shows and the deep space stays black; highlighted lines
 * opt out and stay visible across the whole scene.
 */
class EdgeLayer {

    private val program = Gl.program(VERTEX, FRAGMENT)
    private val vao = glGenVertexArrays()
    private val vertices = glGenBuffers()
    private val scratch = FloatArray(16)
    private var staging: FloatBuffer = BufferUtils.createFloatBuffer(1024)
    private var count = 0

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        Gl.attribute(0, 3, STRIDE, 0)
        Gl.attribute(1, 3, STRIDE, 3)
        Gl.attribute(2, 1, STRIDE, 6)
        glBindVertexArray(0)
    }

    fun upload(data: FloatArray, lines: Int) {
        count = lines * 2
        staging = stage(staging, data, count * STRIDE)
        glBindBuffer(GL_ARRAY_BUFFER, vertices)
        glBufferData(GL_ARRAY_BUFFER, staging, GL_DYNAMIC_DRAW)
    }

    fun draw(view: Matrix4f, projection: Matrix4f, camera: Vector3f, fadeDistance: Float) {
        if (count == 0) return
        glUseProgram(program)
        glUniformMatrix4fv(glGetUniformLocation(program, "view"), false, view.get(scratch))
        glUniformMatrix4fv(glGetUniformLocation(program, "projection"), false, projection.get(scratch))
        glUniform3f(glGetUniformLocation(program, "cameraPosition"), camera.x, camera.y, camera.z)
        glUniform1f(glGetUniformLocation(program, "fadeDistance"), fadeDistance)
        glBindVertexArray(vao)
        glDrawArrays(GL_LINES, 0, count)
        glBindVertexArray(0)
    }

    private companion object {
        const val STRIDE = 7

        const val VERTEX = """
#version 330 core
layout(location = 0) in vec3 position;
layout(location = 1) in vec3 colour;
layout(location = 2) in float fadeStrength;
uniform mat4 view;
uniform mat4 projection;
uniform vec3 cameraPosition;
uniform float fadeDistance;
out vec3 vColour;
void main() {
    gl_Position = projection * view * vec4(position, 1.0);
    float fade = exp(-distance(position, cameraPosition) / fadeDistance);
    vColour = colour * mix(1.0, fade, fadeStrength);
}
"""

        const val FRAGMENT = """
#version 330 core
in vec3 vColour;
out vec4 fragment;
void main() {
    fragment = vec4(vColour, 1.0);
}
"""
    }
}

private fun stage(current: FloatBuffer, data: FloatArray, used: Int): FloatBuffer {
    val buffer = if (current.capacity() >= used) current else BufferUtils.createFloatBuffer(used * 2)
    buffer.clear()
    buffer.put(data, 0, used)
    buffer.flip()
    return buffer
}
