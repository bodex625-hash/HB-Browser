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
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class HBSocks5VpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var configFile: File? = null
    private var nativeLoaded = false
    @Volatile private var tunnelVerified = false
    @Volatile private var probeThread: Thread? = null

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean

    // Protect only the upstream SOCKS socket from being routed back into this VPN.
    @Suppress("unused")
    fun protectSocketFromNative(fd: Int): Boolean = protect(fd)

    private external fun TProxyStopService(): Boolean

    companion object {
        const val ACTION_CONNECT = "com.hb.browser.proxy.CONNECT"
        const val ACTION_DISCONNECT = "com.hb.browser.proxy.DISCONNECT"
        const val ACTION_STATE = "com.hb.browser.proxy.STATE"
        const val EXTRA_STATE = "state"
        const val EXTRA_MESSAGE = "message"
        private const val CHANNEL_ID = "hb_proxy_vpn"
        private const val NOTIFICATION_ID = 7315
        private const val TAG = "HBSocks5VpnService"
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            stopSelf()
            return START_NOT_STICKY
        }

        // Enter foreground before loading native code or creating the TUN interface.
        // This keeps Android's foreground-service requirement satisfied and leaves
        // a visible diagnostic notification if a later setup stage fails.
        try {
            startForegroundCompat("جارٍ تجهيز اتصال VPN...")
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to enter foreground", t)
            stopSelf()
            return START_NOT_STICKY
        }

        if (tun == null) {
            tunnelVerified = false
            broadcastState("CONNECTING", "جارٍ تجهيز نفق SOCKS5...")
            startTunnel()
        } else if (tunnelVerified) {
            broadcastState("CONNECTED", "اتصال SOCKS5 يعمل وتم التحقق من مرور الإنترنت عبر VPN")
        } else {
            broadcastState("CONNECTING", "نفق VPN قيد الاختبار...")
        }
        return START_STICKY
    }

    private fun buildNotification(message: String): Notification {
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
        return (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HB Browser")
            .setContentText(message)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private fun startForegroundCompat(message: String) {
        val notification = buildNotification(message)
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun broadcastState(state: String, message: String) {
        val event = Intent(ACTION_STATE).setPackage(packageName)
            .putExtra(EXTRA_STATE, state)
            .putExtra(EXTRA_MESSAGE, message)
        sendBroadcast(event)
    }

    private fun reportFailure(message: String, error: Throwable? = null) {
        if (error != null) Log.e(TAG, message, error) else Log.e(TAG, message)
        broadcastState("FAILED", message)
        try {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, buildNotification(message))
        } catch (t: Throwable) {
            Log.e(TAG, "Unable to update diagnostic notification", t)
        }
    }

    private fun startTunnel() {
        val saved = try {
            ProxyCredentialStore.load(this)
        } catch (t: Throwable) {
            reportFailure("تعذر قراءة بيانات البروكسي", t)
            return
        }

        if (saved == null || saved.size < 4) {
            reportFailure("بيانات SOCKS5 غير محفوظة؛ افتح إعداد البروكسي وأعد حفظها")
            return
        }

        val host = saved[0]
        val port = saved[1].toIntOrNull()
        if (host.isBlank() || port == null || port !in 1..65535) {
            reportFailure("عنوان أو منفذ SOCKS5 غير صالح")
            return
        }

        try {
            System.loadLibrary("hev-socks5-tunnel")
            nativeLoaded = true
        } catch (t: Throwable) {
            reportFailure("تعذر تحميل محرك نفق SOCKS5", t)
            return
        }

        val descriptor = try {
            Builder()
                .setSession("HB Browser SOCKS5")
                .setMtu(1500)
                .addAddress("198.18.0.1", 15)
                .addAddress("fc00::1", 126)
                .addRoute("0.0.0.0", 0)
                .addRoute("::", 0)
                .addDnsServer("198.18.0.2")
                .establish()
        } catch (t: Throwable) {
            reportFailure("فشل إنشاء واجهة VPN في أندرويد", t)
            return
        }

        if (descriptor == null) {
            reportFailure("أندرويد رفض إنشاء واجهة VPN")
            return
        }

        val config = buildString {
            append("misc:\n  task-stack-size: 86016\n")
            append("tunnel:\n  mtu: 1500\n  ipv4: 198.18.0.1\n  ipv6: 'fc00::1'\n  icmp: 'reply'\n")
            append("socks5:\n  port: $port\n  address: '${yaml(host)}'\n  udp: 'udp'\n")
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
                try { descriptor.close() } catch (_: Throwable) {}
                tun = null
                file.delete()
                configFile = null
                reportFailure("محرك SOCKS5 لم يبدأ؛ راجع سجل المحرك")
                return
            }
            try {
                val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification("تم إنشاء النفق؛ جارٍ اختبار الإنترنت عبر البروكسي"))
            } catch (t: Throwable) {
                Log.e(TAG, "Unable to update running notification", t)
            }
            broadcastState("CONNECTING", "تم إنشاء النفق؛ جارٍ اختبار اتصال SOCKS5 فعلياً...")
            startConnectivityProbe()
        } catch (t: Throwable) {
            try { descriptor.close() } catch (_: Throwable) {}
            tun = null
            configFile?.delete()
            configFile = null
            reportFailure("حدث خطأ أثناء إعداد نفق SOCKS5", t)
        }
    }

    // A successful native worker start is not proof that the remote SOCKS5 server
    // authenticated or can carry traffic. Probe an HTTPS request through this VPN.
    private fun startConnectivityProbe() {
        probeThread?.interrupt()
        probeThread = Thread({
            val endpoints = listOf(
                "https://api.ipify.org",
                "https://checkip.amazonaws.com",
                "https://ipv4.icanhazip.com"
            )
            var success = false
            for (endpoint in endpoints) {
                if (Thread.currentThread().isInterrupted) return@Thread
                var connection: HttpURLConnection? = null
                try {
                    connection = URL(endpoint).openConnection() as HttpURLConnection
                    connection.connectTimeout = 7000
                    connection.readTimeout = 7000
                    connection.instanceFollowRedirects = true
                    connection.requestMethod = "GET"
                    val code = connection.responseCode
                    if (code in 200..299) {
                        connection.inputStream.use { input -> input.readBytes() }
                        success = true
                        break
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Proxy connectivity probe failed for $endpoint", t)
                } finally {
                    connection?.disconnect()
                }
            }
            if (Thread.currentThread().isInterrupted) return@Thread
            if (success) {
                tunnelVerified = true
                val message = "اتصال SOCKS5 يعمل وتم التحقق من مرور الإنترنت عبر VPN"
                try {
                    val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(message))
                } catch (t: Throwable) {
                    Log.e(TAG, "Unable to update connected notification", t)
                }
                broadcastState("CONNECTED", message)
            } else {
                val message = "تعذر الوصول للإنترنت عبر SOCKS5؛ تحقق من الخادم والمنفذ وبيانات الدخول"
                stopTunnel()
                reportFailure(message)
            }
        }, "HB-SOCKS5-Connectivity-Probe").apply {
            isDaemon = true
            start()
        }
    }

    private fun yaml(value: String): String =
        value.replace("'", "''").replace("\n", "").replace("\r", "")

    private fun stopTunnel() {
        tunnelVerified = false
        probeThread?.let { if (it !== Thread.currentThread()) it.interrupt() }
        probeThread = null
        if (nativeLoaded) {
            try { TProxyStopService() } catch (t: Throwable) { Log.e(TAG, "Native tunnel stop failed", t) }
            nativeLoaded = false
        }
        try { tun?.close() } catch (t: Throwable) { Log.e(TAG, "TUN close failed", t) }
        tun = null
        try { configFile?.delete() } catch (_: Throwable) {}
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
