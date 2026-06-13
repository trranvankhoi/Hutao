package com.example.network

import android.util.Log
import com.example.model.NetworkCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest

class ControlClient {
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var reader: BufferedReader? = null
    
    private val _responses = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val responses: SharedFlow<String> = _responses

    var isConnected = false
        private set

    /**
     * Attempts connections, evaluates pairings. Suffixes custom SHA256 security.
     */
    suspend fun connect(ip: String, port: Int, passwordPlain: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            close()
            
            val sock = Socket()
            sock.connect(InetSocketAddress(ip, port), 5000)
            socket = sock
            outputStream = sock.getOutputStream()
            reader = BufferedReader(InputStreamReader(sock.getInputStream()))
            isConnected = true

            // Trigger active reading loop thread
            startReadingTask()

            // Authenticate flow
            val hash = sha256(passwordPlain)
            val authCmd = JSONObject().apply {
                put("type", "auth_login")
                put("password_hash", hash)
            }.toString() + "\n"

            sendRaw(authCmd)

            // Block & await auth token success responses
            val firstResponse = readNextLine() ?: return@withContext Result.failure(Exception("Disconnected prior validation"))
            val responseObj = JSONObject(firstResponse)
            
            if (responseObj.optString("status") == "success") {
                val token = responseObj.optString("token")
                Result.success(token)
            } else {
                val msg = responseObj.optString("message", "Authentication rejected")
                close()
                Result.failure(Exception(msg))
            }
        } catch (e: Exception) {
            close()
            Result.failure(e)
        }
    }

    private fun startReadingTask() {
        // Simple thread to pump incoming answers to downstream Flow
        Thread {
            while (isConnected) {
                try {
                    val line = reader?.readLine()
                    if (line == null) {
                        isConnected = false
                        break
                    }
                    _responses.tryEmit(line)
                } catch (e: Exception) {
                    isConnected = false
                    break
                }
            }
        }.start()
    }

    private fun readNextLine(): String? {
        try {
            return reader?.readLine()
        } catch (e: Exception) {
            return null
        }
    }

    fun sendRaw(data: String) {
        try {
            outputStream?.write(data.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (e: Exception) {
            isConnected = false
            Log.e("ControlClient", "Write error occurred: ${e.message}")
        }
    }

    fun sendCommand(token: String, command: NetworkCommand) {
        val serialized = command.toJson(token)
        sendRaw(serialized)
    }

    fun close() {
        isConnected = false
        try {
            outputStream?.close()
        } catch (e: Exception) {}
        try {
            reader?.close()
        } catch (e: Exception) {}
        try {
            socket?.close()
        } catch (e: Exception) {}
        socket = null
        outputStream = null
        reader = null
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
