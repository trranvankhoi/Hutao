package com.example.network

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.example.model.FrameBufferPool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

class VideoReceiver {
    private var socket: DatagramSocket? = null
    private var running = false
    private var thread: Thread? = null

    // Unique custom frame assemblies helper map
    // frame_id -> FrameAssembly
    private val ongoingFrames = ConcurrentHashMap<Int, FrameAssembly>()
    
    // Shared Flow communicating finished high efficiency Bitmaps
    private val _videoFrames = MutableSharedFlow<Bitmap>(extraBufferCapacity = 16)
    val videoFrames: SharedFlow<Bitmap> = _videoFrames

    // Pool configuration preventing garbage collector overhead
    private val bufferPool = FrameBufferPool()

    // Screen frame dynamic metrics computed from received fragments
    var lastReceivedBbox: android.graphics.RectF? = null
        private set

    fun start(port: Int) {
        if (running) return
        running = true
        
        try {
            socket = DatagramSocket(port).apply {
                // Ensure socket maintains high buffer bounds to verify smooth UDP reception
                receiveBufferSize = 4 * 1024 * 1024
            }
        } catch (e: Exception) {
            Log.e("VideoReceiver", "Failed to start UDP streaming receiver: ${e.message}")
            return
        }

        thread = Thread {
            val receiveBuffer = ByteArray(2048) // Safe MTU bound chunk
            val packet = DatagramPacket(receiveBuffer, receiveBuffer.size)
            
            while (running) {
                try {
                    socket?.receive(packet)
                    val len = packet.length
                    if (len > 21) { // Min headers length check
                        processPacket(receiveBuffer, len)
                    }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }.apply {
            priority = Thread.MAX_PRIORITY // Give stream processing high thread performance priority
            start()
        }
    }

    fun stop() {
        running = false
        socket?.close()
        socket = null
        thread?.join(500)
        thread = null
        ongoingFrames.clear()
    }

    private fun processPacket(data: ByteArray, length: Int) {
        val buffer = ByteBuffer.wrap(data, 0, length)
        
        // Parse custom streaming headers matching Server's structure:
        // "!HHH?ffff"
        // H -> Short (2 bytes): frameId
        // H -> Short (2 bytes): packetId
        // H -> Short (2 bytes): totalPackets
        // ? -> Bool (1 byte): isDelta
        // 4 Floats (16 bytes): x, y, w, h
        val frameId = buffer.short.toInt() and 0xFFFF
        val packetId = buffer.short.toInt() and 0xFFFF
        val totalPackets = buffer.short.toInt() and 0xFFFF
        
        val isDelta = buffer.get().toInt() != 0
        
        // Floats bounding rect coords
        val bx = buffer.float
        val by = buffer.float
        val bw = buffer.float
        val bh = buffer.float

        val payloadSize = length - 21
        val chunkBytes = ByteArray(payloadSize)
        buffer.get(chunkBytes)

        // Store or fetch frame mapping assembler
        val assembly = ongoingFrames.computeIfAbsent(frameId) {
            FrameAssembly(totalPackets, isDelta, bx, by, bw, bh)
        }

        assembly.addPacket(packetId, chunkBytes)

        if (assembly.isComplete()) {
            ongoingFrames.remove(frameId)
            
            // Reconstruct full JPEG buffer
            val jpegBytes = assembly.assemble()
            
            // Clear old/expired hanging frame assemblies to free capacity
            cleanupStaleAssemblies(frameId)

            // Decode JPEG image asynchronously on Dispatchers.Default
            try {
                val opts = BitmapFactory.Options().apply {
                    inMutable = true // Use mutable allocations for inPlace overlays inside pool
                }
                val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                if (bitmap != null) {
                    if (assembly.isDelta) {
                        lastReceivedBbox = android.graphics.RectF(assembly.bx, assembly.by, assembly.bx + assembly.bw, assembly.by + assembly.bh)
                    } else {
                        lastReceivedBbox = null
                    }
                    _videoFrames.tryEmit(bitmap)
                }
            } catch (e: Exception) {
                Log.e("VideoReceiver", "JPEG Decode Error: ${e.message}")
            }
        }
    }

    private fun cleanupStaleAssemblies(currentFrameId: Int) {
        val staleThreshold = 10
        ongoingFrames.keys.forEach { fid ->
            if (currentFrameId - fid > staleThreshold) {
                ongoingFrames.remove(fid)
            }
        }
    }

    /**
     * Inner class managing assembling chunk fragments matching a unique frame id.
     */
    private inner class FrameAssembly(
        val totalPackets: Int,
        val isDelta: Boolean,
        val bx: Float,
        val by: Float,
        val bw: Float,
        val bh: Float
    ) {
        private val packets = ConcurrentHashMap<Int, ByteArray>()
        private var receivedCount = 0

        fun addPacket(packetId: Int, chunk: ByteArray) {
            if (packets.putIfAbsent(packetId, chunk) == null) {
                receivedCount++
            }
        }

        fun isComplete(): Boolean = receivedCount == totalPackets

        fun assemble(): ByteArray {
            var totalSize = 0
            for (i in 0 until totalPackets) {
                totalSize += packets[i]?.size ?: 0
            }

            val finalBytes = ByteArray(totalSize)
            var currentOffset = 0
            for (i in 0 until totalPackets) {
                val chunk = packets[i] ?: continue
                System.arraycopy(chunk, 0, finalBytes, currentOffset, chunk.size)
                currentOffset += chunk.size
            }
            return finalBytes
        }
    }
}
