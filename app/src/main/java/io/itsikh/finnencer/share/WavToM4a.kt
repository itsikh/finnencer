package io.itsikh.finnencer.share

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Transcodes the PCM WAV files produced by [io.itsikh.finnencer.data.ai.GeminiTts]
 * into AAC-LC in an MP4 container (`.m4a`). Raw 24 kHz/16-bit PCM compresses
 * to roughly 1 MB per minute at 96 kbps — small enough to send over
 * messengers, large enough to keep voice quality intact.
 *
 * Single-method API; suspending so callers can offload to IO.
 */
object WavToM4a {

    /**
     * @return the produced [output] file on success; throws if the WAV
     *         header is malformed or MediaCodec rejects the input format.
     */
    suspend fun transcode(input: File, output: File, bitrate: Int = 96_000): File = withContext(Dispatchers.IO) {
        val raf = RandomAccessFile(input, "r")
        try {
            val header = parseWavHeader(raf)
            require(header.bitsPerSample == 16) { "only 16-bit PCM supported (got ${header.bitsPerSample})" }
            require(header.format == 1) { "only PCM (format=1) supported (got ${header.format})" }

            val mime = MediaFormat.MIMETYPE_AUDIO_AAC
            val format = MediaFormat.createAudioFormat(mime, header.sampleRate, header.channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 32_768)
            }
            val codec = MediaCodec.createEncoderByType(mime)
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()

            val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerStarted = false
            var trackIndex = -1

            raf.seek(header.dataOffset.toLong())
            val totalPcmBytes = header.dataSize.toLong()
            var readPcmBytes = 0L
            var pcmExhausted = false
            val info = MediaCodec.BufferInfo()

            val bytesPerFrame = 2 * header.channels
            val readChunkBytes = 4096 - (4096 % bytesPerFrame)
            val readBuf = ByteArray(readChunkBytes)

            try {
                while (true) {
                    if (!pcmExhausted) {
                        val inIndex = codec.dequeueInputBuffer(10_000)
                        if (inIndex >= 0) {
                            val inBuf = codec.getInputBuffer(inIndex)!!.apply { clear() }
                            val want = minOf(readChunkBytes.toLong(), totalPcmBytes - readPcmBytes).toInt()
                            val n = if (want > 0) raf.read(readBuf, 0, want) else -1
                            if (n <= 0) {
                                codec.queueInputBuffer(
                                    inIndex, 0, 0,
                                    presentationUs(readPcmBytes, header),
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                                )
                                pcmExhausted = true
                            } else {
                                inBuf.put(readBuf, 0, n)
                                codec.queueInputBuffer(
                                    inIndex, 0, n,
                                    presentationUs(readPcmBytes, header),
                                    0,
                                )
                                readPcmBytes += n
                            }
                        }
                    }

                    val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            require(!muxerStarted) { "output format changed twice" }
                            trackIndex = muxer.addTrack(codec.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> { /* keep looping */ }
                        outIndex >= 0 -> {
                            val outBuf = codec.getOutputBuffer(outIndex)!!
                            if (info.size > 0 && muxerStarted && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(trackIndex, outBuf, info)
                            }
                            codec.releaseOutputBuffer(outIndex, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                break
                            }
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                runCatching { codec.release() }
                if (muxerStarted) {
                    runCatching { muxer.stop() }
                }
                runCatching { muxer.release() }
            }
            output
        } finally {
            runCatching { raf.close() }
        }
    }

    private fun presentationUs(bytesRead: Long, h: WavHeader): Long {
        val bytesPerFrame = 2 * h.channels
        val frames = bytesRead / bytesPerFrame
        return 1_000_000L * frames / h.sampleRate
    }

    private data class WavHeader(
        val format: Int,
        val channels: Int,
        val sampleRate: Int,
        val bitsPerSample: Int,
        val dataOffset: Int,
        val dataSize: Int,
    )

    /**
     * Parse RIFF/WAVE chunks until we find `fmt ` and `data`. Tolerant of
     * extra LIST / INFO / fact chunks some encoders emit between the
     * format and data blocks.
     */
    private fun parseWavHeader(raf: RandomAccessFile): WavHeader {
        val riff = ByteArray(12).also { raf.readFully(it) }
        require(String(riff, 0, 4) == "RIFF" && String(riff, 8, 4) == "WAVE") {
            "not a RIFF/WAVE file"
        }
        var fmtSeen = false
        var dataOffset = -1
        var dataSize = -1
        var format = 0
        var channels = 0
        var sampleRate = 0
        var bitsPerSample = 0

        while (raf.filePointer < raf.length()) {
            val chunkHeader = ByteArray(8).also { raf.readFully(it) }
            val tag = String(chunkHeader, 0, 4)
            val size = leInt(chunkHeader, 4)
            when (tag) {
                "fmt " -> {
                    val fmtBuf = ByteArray(size).also { raf.readFully(it) }
                    format = leShort(fmtBuf, 0).toInt()
                    channels = leShort(fmtBuf, 2).toInt()
                    sampleRate = leInt(fmtBuf, 4)
                    bitsPerSample = leShort(fmtBuf, 14).toInt()
                    fmtSeen = true
                }
                "data" -> {
                    dataOffset = raf.filePointer.toInt()
                    dataSize = size
                    break
                }
                else -> raf.seek(raf.filePointer + size)
            }
        }
        require(fmtSeen && dataOffset > 0 && dataSize > 0) {
            "WAV missing fmt or data chunk (fmtSeen=$fmtSeen, dataOffset=$dataOffset, dataSize=$dataSize)"
        }
        return WavHeader(format, channels, sampleRate, bitsPerSample, dataOffset, dataSize)
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        ByteBuffer.wrap(b, o, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun leShort(b: ByteArray, o: Int): Short =
        ByteBuffer.wrap(b, o, 2).order(ByteOrder.LITTLE_ENDIAN).short
}
