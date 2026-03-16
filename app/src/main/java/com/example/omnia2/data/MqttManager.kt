package com.example.omnia2.data

import android.util.Log
import com.hivemq.client.mqtt.mqtt5.Mqtt5Client
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import java.util.concurrent.TimeUnit

class MqttManager {
    private var client: Mqtt5Client? = null
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val messages = _messages.asSharedFlow()
    
    // Stable ID to allow persistent sessions (getting missed messages)
    private val clientId = "omnia2_watch_joseph"

    fun connect(
        brokerHost: String = "ipv4.zacharie.org",
        port: Int = 1883
    ) {
        try {
            client = Mqtt5Client.builder()
                .identifier(clientId)
                .serverHost(brokerHost)
                .serverPort(port)
                .simpleAuth()
                    .username("joseph")
                    .password("2f21ZxB5JC6XfujK".toByteArray())
                    .applySimpleAuth()
                .build()

            // We use a persistent session (Session Expiry > 0)
            // so the broker keeps messages for us while we are disconnected.
            client?.toAsync()?.connectWith()
                ?.cleanStart(false)
                ?.sessionExpiryInterval(3600) // Keep session for 1 hour
                ?.send()
                ?.whenComplete { _, throwable ->
                    if (throwable != null) {
                        Log.e("MQTT", "Failed to connect", throwable)
                    } else {
                        Log.i("MQTT", "Connected and session resumed")
                        subscribe("omnia2/messages")
                    }
                }
        } catch (e: Exception) {
            Log.e("MQTT", "Connection error", e)
        }
    }

    private fun subscribe(topic: String) {
        client?.toAsync()?.subscribeWith()
            ?.topicFilter(topic)
            ?.callback { publish: Mqtt5Publish ->
                val content = String(publish.payloadAsBytes)
                Log.i("MQTT", "Received: $content")
                _messages.tryEmit(content)
            }
            ?.send()
    }

    fun disconnect() {
        client?.toAsync()?.disconnectWith()
            ?.sessionExpiryInterval(3600) // Ensure session is kept on broker
            ?.send()
    }
}
