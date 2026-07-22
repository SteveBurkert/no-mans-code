package depgraph

import org.lwjgl.opengl.GL33C.GL_COMPILE_STATUS
import org.lwjgl.opengl.GL33C.GL_FLOAT
import org.lwjgl.opengl.GL33C.GL_FRAGMENT_SHADER
import org.lwjgl.opengl.GL33C.GL_LINK_STATUS
import org.lwjgl.opengl.GL33C.GL_TRUE
import org.lwjgl.opengl.GL33C.GL_VERTEX_SHADER
import org.lwjgl.opengl.GL33C.glAttachShader
import org.lwjgl.opengl.GL33C.glCompileShader
import org.lwjgl.opengl.GL33C.glCreateProgram
import org.lwjgl.opengl.GL33C.glCreateShader
import org.lwjgl.opengl.GL33C.glDeleteShader
import org.lwjgl.opengl.GL33C.glEnableVertexAttribArray
import org.lwjgl.opengl.GL33C.glGetProgramInfoLog
import org.lwjgl.opengl.GL33C.glGetProgrami
import org.lwjgl.opengl.GL33C.glGetShaderInfoLog
import org.lwjgl.opengl.GL33C.glGetShaderi
import org.lwjgl.opengl.GL33C.glLinkProgram
import org.lwjgl.opengl.GL33C.glShaderSource
import org.lwjgl.opengl.GL33C.glVertexAttribDivisor
import org.lwjgl.opengl.GL33C.glVertexAttribPointer

object Gl {

    fun program(vertexSource: String, fragmentSource: String): Int {
        val vertex = shader(GL_VERTEX_SHADER, vertexSource)
        val fragment = shader(GL_FRAGMENT_SHADER, fragmentSource)
        val program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        check(glGetProgrami(program, GL_LINK_STATUS) == GL_TRUE) {
            "shader link failed: ${glGetProgramInfoLog(program)}"
        }
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        return program
    }

    /** Declares one float attribute; [stride] and [offset] count floats, not bytes. */
    fun attribute(index: Int, size: Int, stride: Int, offset: Int, perInstance: Boolean = false) {
        glVertexAttribPointer(index, size, GL_FLOAT, false, stride * 4, offset.toLong() * 4L)
        glEnableVertexAttribArray(index)
        if (perInstance) glVertexAttribDivisor(index, 1)
    }

    private fun shader(type: Int, source: String): Int {
        val shader = glCreateShader(type)
        glShaderSource(shader, source)
        glCompileShader(shader)
        check(glGetShaderi(shader, GL_COMPILE_STATUS) == GL_TRUE) {
            "shader compile failed: ${glGetShaderInfoLog(shader)}"
        }
        return shader
    }
}
