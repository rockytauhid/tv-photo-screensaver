// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Rocky Adam Tauhid

package com.photodream.tv

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build

/**
 * Lightweight connectivity check so LAN/cloud sources can fail fast when the TV is offline
 * instead of waiting for TCP timeouts. Opens no network sockets.
 */
internal object NetworkMonitor {
    @Suppress("DEPRECATION")
    fun isConnected(context: Context): Boolean {
        val manager = context.applicationContext
            .getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val network = manager.activeNetwork ?: return false
                val capabilities = manager.getNetworkCapabilities(network) ?: return false
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
            } else {
                manager.activeNetworkInfo?.isConnected == true
            }
        } catch (_: SecurityException) {
            true
        }
    }
}
