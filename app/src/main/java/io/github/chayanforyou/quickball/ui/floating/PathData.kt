package io.github.chayanforyou.quickball.ui.floating

import android.graphics.Path

/**
 * Parses the small subset of SVG path data used by the app's stroke icons (absolute M, L, H, V,
 * C and Z). Lets a view draw and animate the individual strokes of an icon (e.g. the speaker's
 * sound waves) in the same 24×24 coordinate space as the vector drawables.
 */
internal object PathData {

    fun parse(data: String): Path {
        val path = Path()
        val tokens = tokenize(data)
        var i = 0
        var command = 'M'
        var x = 0f
        var y = 0f
        fun next(): Float = tokens[i++].toFloat()

        while (i < tokens.size) {
            val token = tokens[i]
            if (token.length == 1 && token[0].isLetter()) {
                command = token[0]
                i++
                if (command == 'Z' || command == 'z') {
                    path.close()
                    continue
                }
            }
            when (command) {
                'M' -> {
                    x = next(); y = next()
                    path.moveTo(x, y)
                    command = 'L' // further pairs after M are implicit line-tos
                }
                'L' -> {
                    x = next(); y = next()
                    path.lineTo(x, y)
                }
                'H' -> {
                    x = next()
                    path.lineTo(x, y)
                }
                'V' -> {
                    y = next()
                    path.lineTo(x, y)
                }
                'C' -> {
                    val x1 = next(); val y1 = next()
                    val x2 = next(); val y2 = next()
                    x = next(); y = next()
                    path.cubicTo(x1, y1, x2, y2, x, y)
                }
                else -> throw IllegalArgumentException("Unsupported path command $command")
            }
        }
        return path
    }

    private fun tokenize(data: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        for (c in data) {
            when {
                c.isLetter() -> {
                    flush()
                    out.add(c.toString())
                }
                c == ',' || c.isWhitespace() -> flush()
                c == '-' && sb.isNotEmpty() && sb.last() != 'e' && sb.last() != 'E' -> {
                    flush()
                    sb.append(c)
                }
                else -> sb.append(c)
            }
        }
        flush()
        return out
    }
}
