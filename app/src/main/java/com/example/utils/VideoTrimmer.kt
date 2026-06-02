package com.example.utils

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

object VideoTrimmer {
    private const val TAG = "VideoTrimmer"

    fun trimVideo(
        inputPath: String,
        outputPath: String,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit = {}
    ): Boolean {
        val startUs = startMs * 1000
        val endUs = endMs * 1000

        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(inputPath)

            val trackCount = extractor.trackCount
            
            // Delete existing output if any
            val outFile = File(outputPath)
            if (outFile.exists()) {
                outFile.delete()
            }

            muxer = MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            var bufferSize = 1024 * 1024 // 1MB default

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val outTrackIndex = muxer.addTrack(format)
                    indexMap[i] = outTrackIndex

                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        val inputSize = format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                        if (inputSize > bufferSize) {
                            bufferSize = inputSize
                        }
                    }
                }
            }

            muxer.start()

            // Seek to start position matching keyframes
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocate(bufferSize)
            val bufferInfo = MediaCodec.BufferInfo()
            
            val totalSpanUs = endUs - startUs
            var currentUs = startUs

            var ptsOffsetUs = -1L
            val lastPtsMap = HashMap<Int, Long>()

            while (true) {
                val trackIndex = extractor.sampleTrackIndex
                if (trackIndex < 0) {
                    break
                }

                val sampleTime = extractor.sampleTime
                if (sampleTime > endUs) {
                    break
                }

                bufferInfo.size = extractor.readSampleData(buffer, 0)
                if (bufferInfo.size < 0) {
                    break
                }

                if (ptsOffsetUs == -1L) {
                    ptsOffsetUs = sampleTime
                }

                val adjustedPts = sampleTime - ptsOffsetUs
                val lastPts = lastPtsMap[trackIndex] ?: -1L

                // Only write if timestamps are strictly increasing per track to protect against MediaMuxer crashes
                if (adjustedPts > lastPts) {
                    bufferInfo.presentationTimeUs = adjustedPts
                    bufferInfo.offset = 0
                    bufferInfo.flags = extractor.sampleFlags

                    val outTrackIndex = indexMap[trackIndex]
                    if (outTrackIndex != null) {
                        muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
                    }
                    lastPtsMap[trackIndex] = adjustedPts
                } else if (adjustedPts == lastPts) {
                    val correctedPts = adjustedPts + 1000 // Increment slightly (1ms) to keep sequence monotonic
                    bufferInfo.presentationTimeUs = correctedPts
                    bufferInfo.offset = 0
                    bufferInfo.flags = extractor.sampleFlags

                    val outTrackIndex = indexMap[trackIndex]
                    if (outTrackIndex != null) {
                        muxer.writeSampleData(outTrackIndex, buffer, bufferInfo)
                    }
                    lastPtsMap[trackIndex] = correctedPts
                } else {
                    Log.w(TAG, "Skipping frame with out of order PTS: track=$trackIndex, pts=$adjustedPts, last=$lastPts")
                }

                currentUs = sampleTime
                if (totalSpanUs > 0) {
                    val progress = ((currentUs - startUs).toFloat() / totalSpanUs).coerceIn(0f, 1f)
                    onProgress(progress)
                }

                extractor.advance()
            }

            muxer.stop()
            onProgress(1.0f)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error trimming video Lossless", e)
            return false
        } finally {
            try {
                extractor?.release()
            } catch (ignored: Exception) {}
            try {
                muxer?.release()
            } catch (ignored: Exception) {}
        }
    }
}
