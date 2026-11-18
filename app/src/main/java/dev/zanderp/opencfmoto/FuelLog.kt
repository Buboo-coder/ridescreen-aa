// SPDX-License-Identifier: AGPL-3.0-or-later
// Part of RideScreen AA (fork of OpenCfMoto). Free software under the GNU AGPL v3 or later.
package dev.zanderp.opencfmoto

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Rider's own fuel log (date, odometer, litres, price per litre). Stored only on the phone, in
 * SharedPreferences. Averages assume the tank is filled up every time, so each fill-up's litres
 * are what was burnt since the previous fill-up's odometer reading.
 */
object FuelLog {
    data class Entry(
        val id: Long,
        val dateMs: Long,
        val odoKm: Double,
        val liters: Double,
        val pricePerL: Double,
    ) {
        val total: Double get() = liters * pricePerL
    }

    data class Stats(val kmPerL: Double, val costPerKm: Double)

    private const val PREFS = "fuel_log"
    private const val KEY = "entries"

    /** All entries, oldest odometer first. */
    fun all(ctx: Context): List<Entry> {
        val raw = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
            ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                Entry(
                    id = o.optLong("id"),
                    dateMs = o.optLong("date"),
                    odoKm = o.optDouble("odo"),
                    liters = o.optDouble("l"),
                    pricePerL = o.optDouble("p"),
                )
            }.filter { it.odoKm.isFinite() && it.liters > 0 && it.pricePerL > 0 }
                .sortedBy { it.odoKm }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(ctx: Context, e: Entry) = save(ctx, all(ctx) + e)

    fun remove(ctx: Context, id: Long) = save(ctx, all(ctx).filter { it.id != id })

    private fun save(ctx: Context, list: List<Entry>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("date", it.dateMs).put("odo", it.odoKm).put("l", it.liters).put("p", it.pricePerL))
        }
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    /** Km per litre of the fill-up at [index] of [sorted] (needs a previous one), or null. */
    fun kmPerLAt(sorted: List<Entry>, index: Int): Double? {
        if (index < 1 || index >= sorted.size) return null
        val d = sorted[index].odoKm - sorted[index - 1].odoKm
        val l = sorted[index].liters
        return if (d > 0 && l > 0) d / l else null
    }

    /** Overall km/L and baht per km over every pair of consecutive fill-ups; null under 2 entries. */
    fun stats(sorted: List<Entry>): Stats? {
        if (sorted.size < 2) return null
        var dist = 0.0
        var fuel = 0.0
        var cost = 0.0
        for (i in 1 until sorted.size) {
            val d = sorted[i].odoKm - sorted[i - 1].odoKm
            if (d <= 0) continue
            dist += d
            fuel += sorted[i].liters
            cost += sorted[i].total
        }
        if (dist <= 0 || fuel <= 0) return null
        return Stats(dist / fuel, cost / dist)
    }
}
