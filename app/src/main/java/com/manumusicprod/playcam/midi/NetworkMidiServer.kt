package com.manumusicprod.playcam.midi

import android.content.Context
import android.os.Build
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Réception du MIDI envoyé par le compagnon ordinateur (Mac / Windows) sur le Wi-Fi local.
 *
 * Protocole UDP (port 21928) :
 *   "MMPD"            découverte : le téléphone répond "MMPA" + nom de l'appareil
 *   "MMPM" + octets   message(s) MIDI bruts
 *   "MMPP" + 8 octets ping : renvoyé tel quel ("MMPQ") pour mesurer l'aller-retour
 */
class NetworkMidiServer(
    private val context: Context,
    private val onMidi: (ByteArray, Int, Int, Long) -> Unit,
) {
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _client = MutableStateFlow<String?>(null)
    val client: StateFlow<String?> = _client
    @Volatile private var lastPacketAt = 0L

    fun hasClient(): Boolean = _client.value != null && System.currentTimeMillis() - lastPacketAt < 15_000

    fun statusLabel(): String = _client.value?.let { "Ordinateur $it" } ?: "Ordinateur (en attente)"

    @Synchronized
    fun start() {
        if (_running.value) return
        val s = try {
            DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(PORT)) }
        } catch (e: SocketException) { return }
        socket = s
        _running.value = true
        thread = Thread({ loop(s) }, "mmp-net-midi").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        _running.value = false
        _client.value = null
        runCatching { socket?.close() }
        socket = null
    }

    private fun loop(s: DatagramSocket) {
        val buf = ByteArray(2048)
        val pkt = DatagramPacket(buf, buf.size)
        while (_running.value && !s.isClosed) {
            try {
                pkt.setLength(buf.size)
                s.receive(pkt)
                val t = System.nanoTime()
                val len = pkt.length
                if (len < 4 || buf[0] != 'M'.code.toByte() || buf[1] != 'M'.code.toByte() || buf[2] != 'P'.code.toByte()) continue
                lastPacketAt = System.currentTimeMillis()
                val from = pkt.address
                when (buf[3].toInt().toChar()) {
                    'D' -> { reply(s, from, pkt.port, "MMPA" + deviceName()); _client.value = from.hostAddress }
                    'M' -> { _client.value = from.hostAddress; if (len > 4) onMidi(buf, 4, len - 4, t) }
                    'P' -> { val out = buf.copyOf(len); out[3] = 'Q'.code.toByte(); s.send(DatagramPacket(out, len, from, pkt.port)) }
                }
            } catch (e: Exception) {
                if (!_running.value) break
            }
        }
    }

    private fun reply(s: DatagramSocket, to: InetAddress, port: Int, text: String) {
        val b = text.toByteArray(Charsets.UTF_8)
        runCatching { s.send(DatagramPacket(b, b.size, to, port)) }
    }

    private fun deviceName(): String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    companion object {
        const val PORT = 21928

        /** Adresses IPv4 locales du téléphone (Wi-Fi, partage de connexion). */
        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .filter { it.isSiteLocalAddress }
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }
    }
}
