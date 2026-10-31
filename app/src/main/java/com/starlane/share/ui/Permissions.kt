package com.starlane.share.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Only the permissions each feature needs, per API level. Nothing is requested up-front. */
object Perms {
    /** Nearby discovery over Wi-Fi Direct (and NSD on some OEM builds). */
    fun discovery(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else -> arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun bluetooth(): Array<String> = when {
        Build.VERSION.SDK_INT >= 31 -> arrayOf(
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_ADVERTISE,
        )
        else -> emptyArray() // legacy BLUETOOTH/ADMIN are install-time; location covered by discovery()
    }

    /** Reading media for the photo/video/audio tabs. Documents/other files use the system picker (no permission). */
    fun media(): Array<String> = when {
        Build.VERSION.SDK_INT >= 33 -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    /** Writing to Download/Starlane directly on Android 9 and older (newer versions use MediaStore). */
    fun legacyWrite(): Array<String> =
        if (Build.VERSION.SDK_INT <= 28) arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE) else emptyArray()

    fun notifications(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()

    fun granted(c: Context, perms: Array<String>) = perms.all { c.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun missing(c: Context, perms: Array<String>) = perms.filter { c.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}
