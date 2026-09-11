package com.metrolist.music.playback.audio

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

@UnstableApi
class LoudnessAudioProcessor(
    private val attackCoeff: Float = 0.6f,
    private val releaseCoeff: Float = 0.05f,
) : AudioProcessor {

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = C.ENCODING_INVALID
    private var outputBuffer: ByteBuffer = EMPTY_BUFFER
    private var inputEnded = false
    
    @Volatile
    var currentLevel: Float = 0f
        private set

    override fun configure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        sampleRate = inputAudioFormat.sampleRate
        channelCount = inputAudioFormat.channelCount
        encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun isActive(): Boolean = true

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) {
            outputBuffer = EMPTY_BUFFER
            return
        }

        if (channelCount > 0) {
            measureLoudness(inputBuffer)
        }

        val out = replaceOutputBuffer(inputBuffer.remaining())
        out.put(inputBuffer)
        out.flip()
    }

    private fun measureLoudness(inputBuffer: ByteBuffer) {
        inputBuffer.order(ByteOrder.LITTLE_ENDIAN)
        val frameCount = inputBuffer.remaining() / 2 / channelCount
        if (frameCount == 0) return
        val basePosition = inputBuffer.position()

        var sumSquares = 0.0
        repeat(frameCount) { frameIndex ->
            repeat(channelCount) { channelIndex ->
                val sampleIndex = basePosition + (frameIndex * channelCount + channelIndex) * 2
                val sample = inputBuffer.getShort(sampleIndex).toInt()
                sumSquares += sample.toDouble() * sample
            }
        }

    val rms = sqrt(sumSquares / (frameCount * channelCount)) / 32767.0
    val normalizedLevel = rms.toFloat().coerceIn(0f, 1f)

    // Fast attack, slow release so short loud passages still register visibly.
    currentLevel = if (normalizedLevel > currentLevel) {
        currentLevel + (normalizedLevel - currentLevel) * attackCoeff
    } else {
        currentLevel + (normalizedLevel - currentLevel) * releaseCoeff
    }
}

    override fun queueEndOfStream() {
        inputEnded = true
    }

    override fun getOutput(): ByteBuffer {
        val output = outputBuffer
        outputBuffer = EMPTY_BUFFER
        return output
    }

    override fun isEnded(): Boolean = inputEnded && outputBuffer === EMPTY_BUFFER

    @Deprecated("Deprecated in AudioProcessor")
    override fun flush() {
        outputBuffer = EMPTY_BUFFER
        inputEnded = false
        currentLevel = 0f
    }

    @Deprecated("Deprecated in AudioProcessor")
    override fun reset() {
        flush()
        sampleRate = 0
        channelCount = 0
        encoding = C.ENCODING_INVALID
    }

    private fun replaceOutputBuffer(size: Int): ByteBuffer {
        if (outputBuffer.capacity() < size) {
            outputBuffer = ByteBuffer.allocateDirect(size).order(ByteOrder.nativeOrder())
        } else {
            outputBuffer.clear()
        }
        return outputBuffer
    }

    companion object {
        private val EMPTY_BUFFER: ByteBuffer = ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder())
    }
}
