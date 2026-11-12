package com.hb.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.content.pm.ServiceInfo
import java.io.File
import java.io.FileOutputStream

class HBSocks5VpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var configFile: File? = null

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean

    // Called by the native SOCKS5 engine for each outbound proxy socket.
    // Protecting only this socket avoids a VPN routing loop while keeping
    // browser traffic inside the VPN interface.
    @Suppress("unused")
    fun protectSocketFromNative(fd: Int): Boolean = protect(fd)
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean

    companion object {
        const val ACTION_CONNECT = "com.hb.browser.proxy.CONNECT"
        const val ACTION_DISCONNECT = "com.hb.browser.proxy.DISCONNECT"
        private const val CHANNEL_ID = "hb_proxy_vpn"
        private const val NOTIFICATION_ID = 7315
        init { System.loadLibrary("hev-socks5-tunnel") }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat()
        if (tun == null) startTunnel()
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "HB Browser Proxy", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }.setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HB Browser")
            .setContentText("نفق SOCKS5 يعمل على مستوى الهاتف")
            .setContentIntent(open)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startTunnel() {
        val saved = ProxyCredentialStore.load(this)
        if (saved == null || saved.size < 4) {
            stopSelf()
            return
        }
        val host = saved[0]
        val port = saved[1].toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            stopSelf()
            return
        }

        val builder = Builder()
            .setSession("HB Browser SOCKS5")
            .setMtu(1500)
            .addAddress("198.18.0.1", 15)
            .addAddress("fc00::1", 126)
            .addRoute("0.0.0.0", 0)
            .addRoute("::", 0)
            .addDnsServer("198.18.0.2")

        val descriptor = builder.establish()
        if (descriptor == null) {
            stopSelf()
            return
        }

        val config = buildString {
            append("misc:\n  task-stack-size: 86016\n")
            append("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n  icmp: 'reply'\n")
            append("socks5:\n  port: $port\n  address: '${yaml(saved[0])}'\n  udp: 'udp'\n")
            if (saved[2].isNotEmpty() && saved[3].isNotEmpty()) {
                append("  username: '${yaml(saved[2])}'\n  password: '${yaml(saved[3])}'\n")
            }
            append("mapdns:\n  address: 198.18.0.2\n  port: 53\n  network: 240.0.0.0\n  netmask: 240.0.0.0\n  cache-size: 10000\n")
        }

        try {
            val file = File(cacheDir, "hb-socks5-tunnel.yml")
            FileOutputStream(file, false).use { it.write(config.toByteArray(Charsets.UTF_8)) }
            configFile = file
            tun = descriptor
            if (!TProxyStartService(file.absolutePath, descriptor.fd)) {
                descriptor.close()
                tun = null
                file.delete()
                configFile = null
                stopSelf()
            }
        } catch (_: Exception) {
            try { descriptor.close() } catch (_: Exception) {}
            tun = null
            configFile?.delete()
            configFile = null
            stopSelf()
        }
    }

    private fun yaml(value: String): String = value.replace("'", "''").replace("\n", "").replace("\r", "")

    private fun stopTunnel() {
        try { if (TProxyIsRunning()) TProxyStopService() } catch (_: Exception) {}
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        configFile?.delete()
        configFile = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onRevoke() {
        stopTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopTunnel()
        super.onDestroy()
    }
}
