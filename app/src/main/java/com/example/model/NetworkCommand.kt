package com.example.model

import org.json.JSONObject

/**
 * Defines JSON commands supported by Windows control stream.
 */
sealed class NetworkCommand {
    abstract val type: String
    
    fun toJson(token: String): String {
        val root = JSONObject()
        root.put("type", type)
        root.put("token", token)
        populateJson(root)
        return root.toString() + "\n"
    }
    
    protected abstract fun populateJson(json: JSONObject)

    data class MouseMove(val x: Float, val y: Float) : NetworkCommand() {
        override val type: String = "mouse_move"
        override fun populateJson(json: JSONObject) {
            json.put("x", x.toDouble())
            json.put("y", y.toDouble())
        }
    }

    data class MouseClick(val button: String = "left") : NetworkCommand() {
        override val type: String = "mouse_click"
        override fun populateJson(json: JSONObject) {
            json.put("button", button)
        }
    }

    data class MouseScroll(val value: Int) : NetworkCommand() {
        override val type: String = "mouse_scroll"
        override fun populateJson(json: JSONObject) {
            json.put("value", value)
        }
    }

    data class KeyboardInput(val text: String = "", val keyCode: String = "") : NetworkCommand() {
        override val type: String = "keyboard_input"
        override fun populateJson(json: JSONObject) {
            if (text.isNotEmpty()) {
                json.put("text", text)
            }
            if (keyCode.isNotEmpty()) {
                json.put("key_code", keyCode)
            }
        }
    }

    data class RegisterVideoStream(val udpPort: Int) : NetworkCommand() {
        override val type: String = "register_video_stream"
        override fun populateJson(json: JSONObject) {
            json.put("udp_port", udpPort)
        }
    }

    data class UpdateStreamSettings(val quality: Int, val fps: Int, val scale: Float) : NetworkCommand() {
        override val type: String = "update_stream_settings"
        override fun populateJson(json: JSONObject) {
            json.put("quality", quality)
            json.put("fps", fps)
            json.put("scale", scale.toDouble())
        }
    }

    object Shutdown : NetworkCommand() {
        override val type: String = "shutdown"
        override fun populateJson(json: JSONObject) {}
    }

    object Restart : NetworkCommand() {
        override val type: String = "restart"
        override fun populateJson(json: JSONObject) {}
    }

    object LockScreen : NetworkCommand() {
        override val type: String = "lock_screen"
        override fun populateJson(json: JSONObject) {}
    }
}
