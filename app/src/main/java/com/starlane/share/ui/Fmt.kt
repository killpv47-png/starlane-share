package com.starlane.share.ui

import android.content.Context
import com.starlane.share.Locales
import com.starlane.share.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

/**
 * Locale-aware formatting. Persian uses Persian digits and its own unit names; numbers are
 * always formatted through [NumberFormat] for the active locale.
 */
object Fmt {
    private fun nf(c: Context, digits: Int): NumberFormat = NumberFormat.getNumberInstance(Locales.current(c)).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }

    fun bytes(c: Context, b: Long): String {
        val v = b.coerceAtLeast(0).toDouble()
        val (num, unit) = when {
            v >= 1e9 -> v / 1e9 to R.string.unit_gb
            v >= 1e6 -> v / 1e6 to R.string.unit_mb
            v >= 1e3 -> v / 1e3 to R.string.unit_kb
            else -> v to R.string.unit_b
        }
        val digits = if (num >= 100 || unit == R.string.unit_b) 0 else 1
        return c.getString(unit, nf(c, digits).format(num))
    }

    fun speed(c: Context, bytesPerSec: Double): String =
        c.getString(R.string.unit_per_second, bytes(c, bytesPerSec.toLong()))

    fun number(c: Context, n: Number): String = nf(c, 0).format(n)

    fun percent(c: Context, p: Float): String = NumberFormat.getPercentInstance(Locales.current(c)).format(p.coerceIn(0f, 1f).toDouble())

    fun duration(c: Context, sec: Long): String {
        if (sec < 0) return c.getString(R.string.eta_unknown)
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        return when {
            h > 0 -> c.getString(R.string.duration_hm, number(c, h), number(c, m))
            m > 0 -> c.getString(R.string.duration_ms, number(c, m), number(c, s))
            else -> c.getString(R.string.duration_s, number(c, s))
        }
    }

    fun dateTime(c: Context, ms: Long): String =
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locales.current(c)).format(Date(ms))
}
