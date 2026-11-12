package com.hb.browser

import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.core.content.ContextCompat

internal object HBProxyVpnController {
    fun start(context: Context) {
        val intent = Intent(context, HBSocks5VpnService::class.java).setAction(HBSocks5VpnService.ACTION_CONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    fun stop(context: Context) {
        val intent = Intent(context, HBSocks5VpnService::class.java).setAction(HBSocks5VpnService.ACTION_DISCONNECT)
        ContextCompat.startForegroundService(context, intent)
    }

    fun permissionIntent(context: Context): Intent? = VpnService.prepare(context)
}
