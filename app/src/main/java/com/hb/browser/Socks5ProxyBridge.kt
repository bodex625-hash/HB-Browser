package com.hb.browser

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local SOCKS5 bridge: Chromium connects without authentication to loopback;
 * this bridge authenticates to the upstream SOCKS5 server when credentials exist.
 */
internal class Socks5ProxyBridge(
    private val upstreamHost: String,
    private val upstreamPort: Int,
    private val username: String,
    private val password: String
) {
    private val workers = Executors.newCachedThreadPool()
    private val sockets = CopyOnWriteArraySet<Socket>()
    @Volatile private var server: ServerSocket? = null
    val localPort: Int get() = server?.localPort ?: -1

    @Synchronized fun start(): Int {
        if (server != null) return localPort
        val listener = ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"))
        server = listener
        workers.execute {
            while (!listener.isClosed) {
                try {
                    val client = listener.accept()
                    sockets.add(client)
                    workers.execute { handle(client) }
                } catch (_: Exception) {
                    if (!listener.isClosed) stop()
                }
            }
        }
        return listener.localPort
    }

    private fun readFully(input: DataInputStream, count: Int): ByteArray {
        val bytes = ByteArray(count)
        input.readFully(bytes)
        return bytes
    }

    private fun handle(client: Socket) {
        var upstream: Socket? = null
        try {
            client.soTimeout = 20000
            val ci = DataInputStream(BufferedInputStream(client.getInputStream()))
            val co = DataOutputStream(BufferedOutputStream(client.getOutputStream()))
            if (ci.readUnsignedByte() != 5) return
            val methodCount = ci.readUnsignedByte()
            readFully(ci, methodCount)
            co.write(byteArrayOf(5, 0))
            co.flush()

            val requestHead = readFully(ci, 4)
            if (requestHead[0].toInt() != 5 || requestHead[1].toInt() != 1) {
                co.write(byteArrayOf(5, 7, 0, 1, 0, 0, 0, 0, 0, 0))
                co.flush()
                return
            }
            val address = when (requestHead[3].toInt() and 0xff) {
                1 -> readFully(ci, 4)
                3 -> readDomainAddress(ci)
                4 -> readFully(ci, 16)
                else -> return
            }
            val portBytes = readFully(ci, 2)

            upstream = Socket(upstreamHost, upstreamPort).also {
                it.tcpNoDelay = true
                it.soTimeout = 20000
                sockets.add(it)
            }
            val ui = DataInputStream(BufferedInputStream(upstream.getInputStream()))
            val uo = DataOutputStream(BufferedOutputStream(upstream.getOutputStream()))
            val hasCredentials = username.isNotEmpty() || password.isNotEmpty()
            uo.write(if (hasCredentials) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
            uo.flush()
            val methodReply = readFully(ui, 2)
            if (methodReply[0].toInt() != 5 || methodReply[1].toInt() == 0xff) {
                co.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                co.flush()
                return
            }
            if (methodReply[1].toInt() == 2) {
                val u = username.toByteArray(Charsets.UTF_8)
                val p = password.toByteArray(Charsets.UTF_8)
                require(u.size <= 255 && p.size <= 255)
                uo.writeByte(1); uo.writeByte(u.size); uo.write(u)
                uo.writeByte(p.size); uo.write(p); uo.flush()
                val authReply = readFully(ui, 2)
                if (authReply[1].toInt() != 0) {
                    co.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                    co.flush()
                    return
                }
            } else if (methodReply[1].toInt() != 0) {
                co.write(byteArrayOf(5, 5, 0, 1, 0, 0, 0, 0, 0, 0))
                co.flush()
                return
            }

            uo.write(requestHead)
            uo.write(address)
            uo.write(portBytes)
            uo.flush()
            val replyHead = readFully(ui, 4)
            val replyAddress = when (replyHead[3].toInt() and 0xff) {
                1 -> readFully(ui, 4)
                3 -> readDomainAddress(ui)
                4 -> readFully(ui, 16)
                else -> ByteArray(0)
            }
            val replyPort = readFully(ui, 2)
            co.write(replyHead); co.write(replyAddress); co.write(replyPort); co.flush()
            if (replyHead[1].toInt() != 0) return

            client.soTimeout = 0
            upstream.soTimeout = 0
            val remote = upstream ?: return
            val finished = java.util.concurrent.CountDownLatch(2)
            workers.execute { pump(client, remote); finished.countDown() }
            workers.execute { pump(remote, client); finished.countDown() }
            finished.await()
        } catch (_: Exception) {
            // The browser receives a normal failed connection when the proxy rejects it.
        } finally {
            try { client.close() } catch (_: Exception) {}
            sockets.remove(client)
            try { upstream?.close() } catch (_: Exception) {}
            if (upstream != null) sockets.remove(upstream)
        }
    }

    private fun DataInputStream.availableDomainLength(): Int {
        val length = readUnsignedByte()
        return length
    }

    private fun pump(from: Socket, to: Socket) {
        try {
            val buffer = ByteArray(16 * 1024)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                output.write(buffer, 0, count)
                output.flush()
            }
        } catch (_: Exception) {
        } finally {
            try { from.shutdownInput() } catch (_: Exception) {}
            try { to.shutdownOutput() } catch (_: Exception) {}
        }
    }

    fun testConnection(): Boolean {
        val port = localPort
        if (port <= 0) return false
        return try {
            Socket("127.0.0.1", port).use { socket ->
                socket.soTimeout = 12000
                val out = DataOutputStream(socket.getOutputStream())
                val input = DataInputStream(socket.getInputStream())
                out.write(byteArrayOf(5, 1, 0)); out.flush()
                if (input.readUnsignedByte() != 5 || input.readUnsignedByte() != 0) return false
                out.write(byteArrayOf(5, 1, 0, 3, 11) + "example.com".toByteArray() + byteArrayOf(0, 80))
                out.flush()
                val version = input.readUnsignedByte()
                val result = input.readUnsignedByte()
                version == 5 && result == 0
            }
        } catch (_: Exception) { false }
    }

    @Synchronized fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
        sockets.forEach { try { it.close() } catch (_: Exception) {} }
        sockets.clear()
    }
}

internal object ProxyCredentialStore {
    private const val PREFS = "hb_proxy_secure"
    private const val VALUE = "encrypted"
    private const val KEY_ALIAS = "hb_browser_proxy_key"

    private fun key(): SecretKey {
        val store = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    fun save(context: Context, host: String, port: Int, user: String, pass: String) {
        val raw = listOf(host, port.toString(), user, pass).joinToString("\n")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.iv + cipher.doFinal(raw.toByteArray(Charsets.UTF_8))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(VALUE, Base64.encodeToString(encrypted, Base64.NO_WRAP)).apply()
    }

    fun load(context: Context): List<String>? {
        val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(VALUE, null) ?: return null
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8).split("\n").takeIf { it.size == 4 }
        } catch (_: Exception) { null }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
