package com.ozonprices

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.UUID

/** p — обычная цена, c — цена с Ozon Картой (0, если не найдена). */
data class PricePoint(val t: Long, val p: Double, val c: Double = 0.0)

data class Product(
    val id: String,
    val url: String,
    val title: String = "",
    val price: Double = 0.0,
    val card: Double = 0.0,
    val target: Double = 0.0,
    val history: List<PricePoint> = emptyList(),
    val checked: Long = 0L,
    val error: String = "",
    val raw: String = ""
)

fun money(v: Double): String = String.format(Locale("ru", "RU"), "%,.0f ₽", v)

fun effPrice(price: Double, card: Double, useCard: Boolean): Double =
    if (useCard && card > 0) card else price

fun Product.eff(useCard: Boolean): Double = effPrice(price, card, useCard)
fun PricePoint.eff(useCard: Boolean): Double = effPrice(p, c, useCard)

object Store {
    private lateinit var prefs: SharedPreferences
    val products = MutableStateFlow<List<Product>>(emptyList())
    val refreshing = MutableStateFlow(false)
    val useCard = MutableStateFlow(true)

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        useCard.value = prefs.getBoolean("card", true)
        products.value = load()
    }

    fun setUseCard(v: Boolean) {
        useCard.value = v
        prefs.edit().putBoolean("card", v).apply()
    }

    var intervalHours: Int
        get() = prefs.getInt("hours", 3)
        set(v) { prefs.edit().putInt("hours", v).apply() }

    private fun load(): List<Product> = try {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val h = o.getJSONArray("h")
            Product(
                id = o.getString("id"),
                url = o.getString("url"),
                title = o.optString("title"),
                price = o.optDouble("price", 0.0),
                card = o.optDouble("card", 0.0),
                target = o.optDouble("target", 0.0),
                history = (0 until h.length()).map { j ->
                    val a = h.getJSONArray(j)
                    PricePoint(a.getLong(0), a.getDouble(1), a.optDouble(2, 0.0))
                },
                checked = o.optLong("checked"),
                error = o.optString("error"),
                raw = o.optString("raw")
            )
        }
    } catch (e: Exception) {
        emptyList()
    }

    @Synchronized
    private fun save() {
        val arr = JSONArray()
        products.value.forEach { p ->
            val h = JSONArray()
            p.history.forEach { h.put(JSONArray().put(it.t).put(it.p).put(it.c)) }
            arr.put(
                JSONObject().put("id", p.id).put("url", p.url).put("title", p.title)
                    .put("price", p.price).put("card", p.card).put("target", p.target)
                    .put("checked", p.checked).put("error", p.error).put("raw", p.raw).put("h", h)
            )
        }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    fun update(id: String, f: (Product) -> Product) {
        products.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    fun remove(id: String) {
        products.update { l -> l.filter { it.id != id } }
        save()
    }

    /** Принимает текст со ссылкой (например, из «Поделиться» в Ozon) и добавляет товар. */
    fun addFromText(ctx: Context, text: String): String {
        val raw = Regex("https?://[^\\s]+").find(text)?.value?.trimEnd('.', ',', ')', ';')
            ?: return "Ссылка не найдена"
        val uri = Uri.parse(raw)
        val host = uri.host ?: return "Некорректная ссылка"
        if (!host.contains("ozon")) return "Это не ссылка Ozon"
        val path = uri.path ?: ""
        val url = if (path.startsWith("/product")) "https://$host$path" else raw
        if (products.value.any { it.url == url }) return "Товар уже в списке"
        val p = Product(id = UUID.randomUUID().toString(), url = url)
        products.update { it + p }
        save()
        Scheduler.now(ctx, p.id)
        return "Товар добавлен, проверяю цену…"
    }
}
