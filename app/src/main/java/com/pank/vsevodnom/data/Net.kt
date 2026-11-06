package com.pank.vsevodnom.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Есть ли интернет прямо сейчас. Экран показывает красную плашку, загрузки ждут с крутилкой. */
object Net {
    var online by mutableStateOf(true); private set
    private var started = false

    fun init(ctx: Context) {
        if (started) return; started = true
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        fun check() {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork)
            online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        check()
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(n: Network) = check()
                override fun onLost(n: Network) { online = false }
                override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) {
                    online = c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                }
            })
        }
    }
}
