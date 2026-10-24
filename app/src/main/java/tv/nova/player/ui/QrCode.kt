package tv.nova.player.ui

import android.graphics.Bitmap

/** Dependency-free QR Model 2, version 4-L, byte mode, mask 0 (78-byte capacity). */
object QrCode {
    private const val VERSION_SIZE = 33
    private const val DATA_CODEWORDS = 80
    private const val EC_CODEWORDS = 20

    fun render(text: String, scale: Int = 6): Bitmap {
        require(scale > 0)
        val matrix = encode(text)
        val quiet = 4
        val dimension = (VERSION_SIZE + quiet * 2) * scale
        val pixels = IntArray(dimension * dimension) { 0xFFFFFFFF.toInt() }
        for (y in 0 until VERSION_SIZE) {
            for (x in 0 until VERSION_SIZE) {
                if (!matrix[y][x]) continue
                for (dy in 0 until scale) {
                    val start = ((y + quiet) * scale + dy) * dimension + (x + quiet) * scale
                    for (dx in 0 until scale) pixels[start + dx] = 0xFF000000.toInt()
                }
            }
        }
        return Bitmap.createBitmap(dimension, dimension, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, dimension, 0, 0, dimension, dimension)
        }
    }

    internal fun encode(text: String): Array<BooleanArray> {
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 78) { "QR payload is too long" }
        val modules = Array(VERSION_SIZE) { BooleanArray(VERSION_SIZE) }
        val functions = Array(VERSION_SIZE) { BooleanArray(VERSION_SIZE) }
        fun setFunction(x: Int, y: Int, dark: Boolean) {
            if (x in 0 until VERSION_SIZE && y in 0 until VERSION_SIZE) {
                modules[y][x] = dark
                functions[y][x] = true
            }
        }
        fun finder(cx: Int, cy: Int) {
            for (dy in -4..4) for (dx in -4..4) {
                val distance = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                setFunction(cx + dx, cy + dy, distance != 2 && distance != 4)
            }
        }
        for (i in 0 until VERSION_SIZE) {
            setFunction(6, i, i % 2 == 0)
            setFunction(i, 6, i % 2 == 0)
        }
        finder(3, 3); finder(VERSION_SIZE - 4, 3); finder(3, VERSION_SIZE - 4)
        for (dy in -2..2) for (dx in -2..2) {
            setFunction(26 + dx, 26 + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
        }
        // Reserve both format-information areas before placing data.
        fun formatBits(): Int {
            val data = (1 shl 3) // Error correction L (01), mask 0.
            var remainder = data
            repeat(10) { remainder = (remainder shl 1) xor ((remainder ushr 9) * 0x537) }
            return ((data shl 10) or remainder) xor 0x5412
        }
        fun drawFormat() {
            val bits = formatBits()
            fun bit(index: Int) = ((bits ushr index) and 1) != 0
            for (i in 0..5) setFunction(8, i, bit(i))
            setFunction(8, 7, bit(6)); setFunction(8, 8, bit(7)); setFunction(7, 8, bit(8))
            for (i in 9 until 15) setFunction(14 - i, 8, bit(i))
            for (i in 0 until 8) setFunction(VERSION_SIZE - 1 - i, 8, bit(i))
            for (i in 8 until 15) setFunction(8, VERSION_SIZE - 15 + i, bit(i))
            setFunction(8, VERSION_SIZE - 8, true)
        }
        drawFormat()

        val bits = ArrayList<Boolean>(DATA_CODEWORDS * 8)
        fun append(value: Int, count: Int) {
            for (shift in count - 1 downTo 0) bits += ((value ushr shift) and 1) != 0
        }
        append(4, 4) // Byte mode.
        append(bytes.size, 8)
        bytes.forEach { append(it.toInt() and 0xFF, 8) }
        repeat(minOf(4, DATA_CODEWORDS * 8 - bits.size)) { bits += false }
        while (bits.size % 8 != 0) bits += false
        var pad = 0xEC
        while (bits.size < DATA_CODEWORDS * 8) {
            append(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        val data = ByteArray(DATA_CODEWORDS) { index ->
            var value = 0
            repeat(8) { bit -> value = (value shl 1) or if (bits[index * 8 + bit]) 1 else 0 }
            value.toByte()
        }
        val all = data + reedSolomon(data, EC_CODEWORDS)
        var index = 0
        var right = VERSION_SIZE - 1
        while (right >= 1) {
            if (right == 6) right = 5
            for (vertical in 0 until VERSION_SIZE) {
                val y = if (((right + 1) and 2) == 0) VERSION_SIZE - 1 - vertical else vertical
                for (column in 0..1) {
                    val x = right - column
                    if (!functions[y][x]) {
                        modules[y][x] = index < all.size * 8 &&
                            (((all[index ushr 3].toInt() and 0xFF) ushr (7 - (index and 7))) and 1) != 0
                        index++
                    }
                }
            }
            right -= 2
        }
        // Mask 0: invert every module where (x + y) is even.
        for (y in 0 until VERSION_SIZE) for (x in 0 until VERSION_SIZE) {
            if (!functions[y][x] && (x + y) % 2 == 0) modules[y][x] = !modules[y][x]
        }
        drawFormat()
        return modules
    }

    private fun reedSolomon(data: ByteArray, degree: Int): ByteArray {
        val divisor = IntArray(degree)
        divisor[degree - 1] = 1
        var root = 1
        repeat(degree) {
            for (index in divisor.indices) {
                divisor[index] = multiply(divisor[index], root)
                if (index + 1 < divisor.size) divisor[index] = divisor[index] xor divisor[index + 1]
            }
            root = multiply(root, 2)
        }
        val remainder = IntArray(degree)
        data.forEach { byte ->
            val factor = (byte.toInt() and 0xFF) xor remainder[0]
            for (index in 0 until degree - 1) remainder[index] = remainder[index + 1]
            remainder[degree - 1] = 0
            for (index in remainder.indices) remainder[index] = remainder[index] xor multiply(divisor[index], factor)
        }
        return ByteArray(degree) { remainder[it].toByte() }
    }

    private fun multiply(x: Int, y: Int): Int {
        var a = x
        var b = y
        var result = 0
        repeat(8) {
            result = result xor (a and 1) * b
            a = a ushr 1
            b = (b shl 1) xor ((b ushr 7) * 0x11D)
        }
        return result and 0xFF
    }
}
