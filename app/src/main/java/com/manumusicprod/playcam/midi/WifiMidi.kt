package com.manumusicprod.playcam.midi

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.manumusicprod.playcam.Prefs
import com.manumusicprod.playcam.core.AppleMidiSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread

/**
 * Connexion Wi-Fi à l'ordinateur (RTP-MIDI / AppleMIDI, le standard de macOS et de rtpMIDI sous Windows).
 * - cherche les ordinateurs qui ont une session MIDI réseau active (Bonjour « _apple-midi._udp ») ;
 * - permet de s'y connecter d'un toucher, ou d'être connecté depuis l'ordinateur ;
 * - se reconnecte automatiquement au dernier ordinateur utilisé.
 */
class WifiMidi(
    private val context: Context,
    private val prefs: Prefs,
    private val midiSink: (ByteArray, Int, Long) -> Unit,
) {
    data class Computer(val name: String, val host: String, val port: Int)
    data class Linked(val ssrc: Long, val name: String, val host: String, val latencyMs: Double, val packets: Long)

    private val main = Handler(Looper.getMainLooper())
    private val nsd: NsdManager? = context.getSystemService(NsdManager::class.java)
    private val wifi: WifiManager? = context.applicationContext.getSystemService(WifiManager::class.java)
    private var lock: WifiManager.MulticastLock? = null

    val phoneName: String = "Manu Play Cam (${Build.MODEL})"
    private var registeredName: String = phoneName

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running
    private val _found = MutableStateFlow<List<Computer>>(emptyList())
    val found: StateFlow<List<Computer>> = _found
    private val _linked = MutableStateFlow<List<Linked>>(emptyList())
    val linked: StateFlow<List<Linked>> = _linked
    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching
    private val _info = MutableStateFlow<String?>(null)
    val info: StateFlow<String?> = _info
    private val _connecting = MutableStateFlow<String?>(null)
    val connecting: StateFlow<String?> = _connecting

    private var session: AppleMidiSession? = null
    private var discovering = false
    private val resolveQueue = ConcurrentLinkedQueue<NsdServiceInfo>()
    @Volatile private var resolving = false

    private val sessionListener = object : AppleMidiSession.Listener {
        override fun onMidi(msg: ByteArray, length: Int, arrivalNanos: Long) { midiSink(msg, length, arrivalNanos) }
        override fun onPeersChanged() { main.post { refreshLinked() } }
        override fun onInfo(message: String) { main.post { _connecting.value = null; _info.value = message } }
    }

    private val refresher = object : Runnable {
        override fun run() { refreshLinked(); if (_running.value) main.postDelayed(this, 2000) }
    }

    fun start() {
        if (_running.value) return
        val s = AppleMidiSession(phoneName, sessionListener)
        try { s.start(5004) } catch (e: Exception) { _info.value = "Le MIDI Wi-Fi n'a pas pu démarrer : ${e.message}"; return }
        session = s
        _running.value = true
        register(s.controlPort())
        startDiscovery()
        main.post(refresher)
    }

    fun stop() {
        stopDiscovery()
        runCatching { nsd?.unregisterService(registration) }
        session?.stop(); session = null
        _running.value = false
        _linked.value = emptyList(); _found.value = emptyList()
        main.removeCallbacks(refresher)
    }

    private fun refreshLinked() {
        val s = session ?: run { _linked.value = emptyList(); return }
        _linked.value = s.peers().filter { it.connected }.map { Linked(it.ssrc, it.name, it.address.hostAddress ?: "", it.latencyMs, it.packets) }
        val c = _connecting.value
        if (c != null && _linked.value.any { it.host == c || it.name == c }) _connecting.value = null
    }

    // ---------------------------------------------------------------- annonce du téléphone
    private val registration = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) { registeredName = info.serviceName }
        override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        override fun onServiceUnregistered(info: NsdServiceInfo) {}
        override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
    }

    private fun register(controlPort: Int) {
        val info = NsdServiceInfo()
        info.serviceName = phoneName
        info.serviceType = TYPE
        info.port = controlPort
        runCatching { nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, registration) }
    }

    // ---------------------------------------------------------------- recherche des ordinateurs
    private val discovery = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) { main.post { _searching.value = true } }
        override fun onDiscoveryStopped(serviceType: String) { main.post { _searching.value = false } }
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { main.post { _searching.value = false; discovering = false } }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) {
            if (info.serviceName == registeredName || info.serviceName == phoneName) return
            resolveQueue.add(info); resolveNext()
        }
        override fun onServiceLost(info: NsdServiceInfo) {
            main.post { _found.value = _found.value.filterNot { it.name == info.serviceName } }
        }
    }

    fun startDiscovery() {
        val n = nsd ?: return
        if (discovering) return
        lock = wifi?.createMulticastLock("manu-playcam")?.apply { setReferenceCounted(false); runCatching { acquire() } }
        discovering = true
        runCatching { n.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }.onFailure { discovering = false }
    }

    fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        runCatching { nsd?.stopServiceDiscovery(discovery) }
        runCatching { lock?.release() }
        _searching.value = false
    }

    fun searchAgain() {
        stopDiscovery()
        _found.value = emptyList()
        main.postDelayed({ startDiscovery() }, 400)
    }

    @Suppress("DEPRECATION")
    private fun resolveNext() {
        val n = nsd ?: return
        if (resolving) return
        val next = resolveQueue.poll() ?: return
        resolving = true
        n.resolveService(next, object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving = false
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) main.postDelayed({ resolveQueue.add(info); resolveNext() }, 300)
                else resolveNext()
            }
            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving = false
                val host = info.host
                if (host != null && !isOwnAddress(host)) {
                    val c = Computer(info.serviceName, host.hostAddress ?: "", info.port)
                    main.post {
                        _found.value = (_found.value.filterNot { it.name == c.name } + c).sortedBy { it.name }
                        autoReconnect(c)
                    }
                }
                resolveNext()
            }
        })
    }

    private fun isOwnAddress(a: InetAddress): Boolean = a.isLoopbackAddress || localAddresses().contains(a.hostAddress)

    private fun autoReconnect(c: Computer) {
        if (c.name == prefs.lastComputer && _linked.value.none { it.host == c.host } && _connecting.value == null) connect(c)
    }

    // ---------------------------------------------------------------- connexion
    fun connect(c: Computer) {
        val s = session ?: return
        prefs.lastComputer = c.name
        _connecting.value = c.host
        thread(name = "wifi-midi-connect") {
            try { s.invite(InetAddress.getByName(c.host), c.port) }
            catch (e: Exception) { main.post { _connecting.value = null; _info.value = "Adresse introuvable : ${c.host}" } }
        }
        main.postDelayed({ if (_connecting.value == c.host) _connecting.value = null }, 12_000)
    }

    fun connectManual(host: String, port: Int) = connect(Computer(host, host.trim(), port))

    fun disconnect(ssrc: Long) {
        prefs.lastComputer = ""
        session?.disconnect(ssrc)
        refreshLinked()
    }

    fun consumeInfo() { _info.value = null }
    fun isLinked(): Boolean = _linked.value.isNotEmpty()

    companion object {
        const val TYPE = "_apple-midi._udp"

        fun localAddresses(): List<String> = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .mapNotNull { it.hostAddress }
        } catch (e: Exception) { emptyList() }
    }
}
