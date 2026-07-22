package depgraph

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33C.*
import java.nio.FloatBuffer

/**
 * Every flat thing on screen: node labels, the readout panels and the search box. All of it is
 * textured quads in pixel space, so one batch and one draw call covers the whole interface.
 */
class Overlay(private val atlas: FontAtlas) {

    private val program = Gl.program(VERTEX, FRAGMENT)
    private val vao = glGenVertexArrays()
    private val buffer = glGenBuffers()
    private var data = FloatArray(1 shl 16)
    private var used = 0
    private var staging: FloatBuffer = BufferUtils.createFloatBuffer(1 shl 16)

    init {
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        Gl.attribute(0, 2, STRIDE, 0)
        Gl.attribute(1, 2, STRIDE, 2)
        Gl.attribute(2, 4, STRIDE, 4)
        glBindVertexArray(0)
    }

    fun begin() {
        used = 0
    }

    fun rect(x: Float, y: Float, width: Float, height: Float, colour: Colour) {
        quad(x, y, width, height, atlas.whiteU, atlas.whiteV, 0f, 0f, colour)
    }

    fun text(x: Float, y: Float, height: Float, colour: Colour, value: String): Float {
        val scale = height / atlas.cellHeight
        var pen = x
        for (character in value) {
            quad(
                pen, y,
                atlas.cellWidth * scale, atlas.cellHeight * scale,
                atlas.glyphU(character), atlas.glyphV(character),
                atlas.glyphWidth(), atlas.glyphHeight(),
                colour
            )
            pen += atlas.advance(character) * scale
        }
        return pen - x
    }

    fun textWidth(value: String, height: Float): Float = atlas.width(value, height / atlas.cellHeight)

    fun draw(viewportWidth: Int, viewportHeight: Int) {
        if (used == 0) return
        if (staging.capacity() < used) staging = BufferUtils.createFloatBuffer(used * 2)
        staging.clear()
        staging.put(data, 0, used)
        staging.flip()

        glUseProgram(program)
        glUniform2f(glGetUniformLocation(program, "viewport"), viewportWidth.toFloat(), viewportHeight.toFloat())
        glUniform1i(glGetUniformLocation(program, "atlas"), 0)
        glActiveTexture(GL_TEXTURE0)
        glBindTexture(GL_TEXTURE_2D, atlas.texture)
        glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)
        glBindVertexArray(vao)
        glBindBuffer(GL_ARRAY_BUFFER, buffer)
        glBufferData(GL_ARRAY_BUFFER, staging, GL_DYNAMIC_DRAW)
        glDrawArrays(GL_TRIANGLES, 0, used / STRIDE)
        glBindVertexArray(0)
    }

    private fun quad(
        x: Float, y: Float, width: Float, height: Float,
        u: Float, v: Float, uWidth: Float, vHeight: Float,
        colour: Colour
    ) {
        ensure(6 * STRIDE)
        vertex(x, y, u, v, colour)
        vertex(x + width, y, u + uWidth, v, colour)
        vertex(x + width, y + height, u + uWidth, v + vHeight, colour)
        vertex(x, y, u, v, colour)
        vertex(x + width, y + height, u + uWidth, v + vHeight, colour)
        vertex(x, y + height, u, v + vHeight, colour)
    }

    private fun vertex(x: Float, y: Float, u: Float, v: Float, colour: Colour) {
        data[used++] = x
        data[used++] = y
        data[used++] = u
        data[used++] = v
        data[used++] = colour.red * colour.alpha
        data[used++] = colour.green * colour.alpha
        data[used++] = colour.blue * colour.alpha
        data[used++] = colour.alpha
    }

    private fun ensure(floats: Int) {
        if (used + floats <= data.size) return
        data = data.copyOf(maxOf(data.size * 2, used + floats))
    }

    private companion object {
        const val STRIDE = 8

        const val VERTEX = """
#version 330 core
layout(location = 0) in vec2 position;
layout(location = 1) in vec2 uv;
layout(location = 2) in vec4 colour;
uniform vec2 viewport;
out vec2 vUv;
out vec4 vColour;
void main() {
    gl_Position = vec4(
        position.x / viewport.x * 2.0 - 1.0,
        1.0 - position.y / viewport.y * 2.0,
        0.0, 1.0);
    vUv = uv;
    vColour = colour;
}
"""

        const val FRAGMENT = """
#version 330 core
in vec2 vUv;
in vec4 vColour;
uniform sampler2D atlas;
out vec4 fragment;
void main() {
    fragment = vColour * texture(atlas, vUv).r;
}
"""
    }
}

class Colour(val red: Float, val green: Float, val blue: Float, val alpha: Float = 1f)
