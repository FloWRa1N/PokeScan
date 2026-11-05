package de.pokescan

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class Karte(
    val id: String,
    val name: String,
    val setName: String,
    val number: String,
    val total: String,
    val image: String,
    val price: Double?,
    val count: Int = 1
) {
    val cardmarketUrl: String
        get() = "https://www.cardmarket.com/de/Pokemon/Products/Singles?searchString=" +
            URLEncoder.encode(name, "UTF-8")

    fun toJson(): JSONObject {
        val o = JSONObject()
        o.put("id", id)
        o.put("name", name)
        o.put("setName", setName)
        o.put("number", number)
        o.put("total", total)
        o.put("image", image)
        o.put("count", count)
        if (price != null) o.put("price", price)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject) = Karte(
            o.getString("id"), o.optString("name"), o.optString("setName"),
            o.optString("number"), o.optString("total"), o.optString("image"),
            if (o.has("price")) o.getDouble("price") else null,
            o.optInt("count", 1)
        )
    }
}

class Store(ctx: Context) {
    private val sp = ctx.getSharedPreferences("sammlung", Context.MODE_PRIVATE)

    fun load(): List<Karte> {
        return try {
            val a = JSONArray(sp.getString("karten", "[]"))
            (0 until a.length()).map { Karte.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(list: List<Karte>) {
        val a = JSONArray()
        list.forEach { a.put(it.toJson()) }
        sp.edit().putString("karten", a.toString()).apply()
    }
}

object Tcgdex {
    private const val BASE = "https://api.tcgdex.net/v2/de"

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 20000
        return try {
            c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    private fun num(s: String) = s.trim().trimStart('0')

    suspend fun detail(id: String): Karte? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(get("$BASE/cards/" + URLEncoder.encode(id, "UTF-8")))
            val set = o.optJSONObject("set")
            val cc = set?.optJSONObject("cardCount")
            val cm = o.optJSONObject("pricing")?.optJSONObject("cardmarket")
            var price: Double? = null
            if (cm != null) {
                for (k in listOf("trend", "avg", "low")) {
                    if (cm.has(k) && !cm.isNull(k)) {
                        price = cm.getDouble(k)
                        break
                    }
                }
            }
            val img = o.optString("image")
            Karte(
                o.getString("id"),
                o.optString("name"),
                set?.optString("name") ?: "",
                o.optString("localId"),
                cc?.optInt("official")?.toString() ?: "",
                if (img.isNotEmpty()) "$img/low.png" else "",
                price
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun find(name: String, number: String, total: String): List<Karte> =
        withContext(Dispatchers.IO) {
            val arr = JSONArray(get("$BASE/cards?name=" + URLEncoder.encode(name.trim(), "UTF-8")))
            val ids = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                if (number.isBlank() || num(o.optString("localId")) == num(number)) {
                    ids.add(o.getString("id"))
                }
            }
            val cards = ids.take(15).mapNotNull { detail(it) }
            val t = num(total)
            val exact = if (t.isNotEmpty()) cards.filter { num(it.total) == t } else cards
            (if (exact.isNotEmpty()) exact else cards).take(8)
        }
}
