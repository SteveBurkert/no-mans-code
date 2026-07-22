package depgraph

import org.lwjgl.BufferUtils
import org.lwjgl.opengl.GL33C.*
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import kotlin.math.max

/**
 * Bakes printable ASCII into a single-channel texture with Java2D, plus one solid cell that lets
 * the same shader fill plain rectangles.
 */
class FontAtlas(bakedHeight: Int = 44) {

    val texture: Int
    val cellWidth: Int
    val cellHeight: Int
    val whiteU: Float
    val whiteV: Float

    private val advances = FloatArray(FIRST + COUNT)
    private val uvX = FloatArray(FIRST + COUNT)
    private val uvY = FloatArray(FIRST + COUNT)
    private val uvWidth: Float
    private val uvHeight: Float

    init {
        val font = Font(Font.MONOSPACED, Font.PLAIN, bakedHeight)
        val probe = BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB)
        val probeGraphics = probe.createGraphics()
        probeGraphics.font = font
        val metrics = probeGraphics.fontMetrics

        var widest = 1
        for (code in FIRST until FIRST + COUNT) widest = max(widest, metrics.charWidth(code.toChar()))
        cellWidth = widest + PADDING * 2
        cellHeight = metrics.height + PADDING * 2
        val ascent = metrics.ascent
        probeGraphics.dispose()

        var size = 256
        var columns = 0
        while (true) {
            columns = size / cellWidth
            if (columns > 0) {
                val rows = (COUNT + 1 + columns - 1) / columns
                if (rows * cellHeight <= size) break
            }
            size *= 2
        }

        val image = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics.font = font
        graphics.color = Color.WHITE

        for (index in 0 until COUNT) {
            val character = (FIRST + index).toChar()
            val x = (index % columns) * cellWidth
            val y = (index / columns) * cellHeight
            graphics.drawString(character.toString(), x + PADDING, y + PADDING + ascent)
            advances[FIRST + index] = metrics.charWidth(character).toFloat()
            uvX[FIRST + index] = x / size.toFloat()
            uvY[FIRST + index] = y / size.toFloat()
        }
        uvWidth = cellWidth / size.toFloat()
        uvHeight = cellHeight / size.toFloat()

        val whiteX = (COUNT % columns) * cellWidth
        val whiteY = (COUNT / columns) * cellHeight
        graphics.fillRect(whiteX, whiteY, cellWidth, cellHeight)
        whiteU = (whiteX + cellWidth / 2f) / size
        whiteV = (whiteY + cellHeight / 2f) / size
        graphics.dispose()

        val pixels = image.getRGB(0, 0, size, size, null, 0, size)
        val coverage = BufferUtils.createByteBuffer(size * size)
        for (pixel in pixels) coverage.put((pixel ushr 24).toByte())
        coverage.flip()

        texture = glGenTextures()
        glBindTexture(GL_TEXTURE_2D, texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1)
        glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, size, size, 0, GL_RED, GL_UNSIGNED_BYTE, coverage)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE)
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE)
    }

    fun width(text: String, scale: Float): Float {
        var total = 0f
        for (character in text) total += advance(character)
        return total * scale
    }

    fun advance(character: Char): Float = advances[known(character)]

    fun glyphU(character: Char): Float = uvX[known(character)]

    fun glyphV(character: Char): Float = uvY[known(character)]

    private fun known(character: Char): Int =
        if (character.code in FIRST until FIRST + COUNT) character.code else FALLBACK

    fun glyphWidth(): Float = uvWidth

    fun glyphHeight(): Float = uvHeight

    private companion object {
        const val FIRST = 32
        const val COUNT = 95
        const val PADDING = 2
        const val FALLBACK = '?'.code
    }
}
