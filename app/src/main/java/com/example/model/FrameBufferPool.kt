package com.example.model

/**
 * Object Pool structure to prevent allocating redundant buffers during real-time image rendering.
 */
class FrameBufferPool(private val bufferSize: Int = 1024 * 1024, private val maxPoolSize: Int = 5) {
    private val pool = ArrayDeque<ByteArray>()

    fun acquire(): ByteArray {
        synchronized(pool) {
            return if (pool.isNotEmpty()) {
                pool.removeLast()
            } else {
                ByteArray(bufferSize)
            }
        }
    }

    fun release(buffer: ByteArray) {
        synchronized(pool) {
            if (pool.size < maxPoolSize && buffer.size == bufferSize) {
                pool.addLast(buffer)
            }
        }
    }
}
