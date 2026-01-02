package com.binauralbuilder.session_builder_mobile.realtime_backend.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class SimpleFft(val n: Int) {
    private val m: Int = (Math.log(n.toDouble()) / Math.log(2.0)).toInt() // exponent
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)
    private val bitReverse = IntArray(n)

    init {
        // Precompute tables
        for (i in 0 until n / 2) {
            val theta = -2.0 * PI * i / n
            cosTable[i] = cos(theta).toFloat()
            sinTable[i] = sin(theta).toFloat()
        }

        // Bit reverse table
        var j = 0
        for (i in 0 until n - 1) {
            bitReverse[i] = j
            var k = n / 2
            while (k <= j) {
                j -= k
                k /= 2
            }
            j += k
        }
        bitReverse[n - 1] = n - 1
    }

    fun realForward(real: FloatArray, imag: FloatArray) {
        // Bit-reverse permutation
        for (i in 0 until n) {
            val j = bitReverse[i]
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        // Butterfly stats
        var len = 2
        while (len <= n) {
            val halfLen = len / 2
            val step = n / len
            for (i in 0 until halfLen) {
                // Use tables (stride based on step)
                // theta = -2*PI*i/len = -2*PI*(i*step)/n -> table index is i*step
                val idx = i * step
                val c = cosTable[idx]
                val s = sinTable[idx]
                
                for (j in i until n step len) {
                    val k = j + halfLen
                    val tr = real[k] * c - imag[k] * s
                    val ti = real[k] * s + imag[k] * c
                    
                    real[k] = real[j] - tr
                    imag[k] = imag[j] - ti
                    real[j] += tr
                    imag[j] += ti
                }
            }
            len *= 2
        }
    }

    fun realInverse(real: FloatArray, imag: FloatArray) {
        // Inverse FFT: conjugate input, forward FFT, conjugate output, scale
        // Conjugate input: imag = -imag
        for (i in 0 until n) imag[i] = -imag[i]
        
        realForward(real, imag)
        
        // Conjugate output and scale
        val scale = 1.0f / n
        for (i in 0 until n) {
            imag[i] = -imag[i] * scale
            real[i] *= scale
        }
    }
}
