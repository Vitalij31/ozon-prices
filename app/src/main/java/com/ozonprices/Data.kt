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

data class PricePoint(val t: Long, val p: Double)

data class Product(
    val id: String,
    val url: String,
    val title: String = "",
    val price: Double = 0.0,
    val target: Double = 0.0,
    val history: List<PricePoint> = emptyList(),
    val checked: Long = 0L,
    val error: String = ""
)

fun money(v: Double): String = String.format(Locale("ru", "RU"), "%,.0f ₽", v)

object Store {
    private lateinit var prefs: SharedPreferences
    val products = MutableStateFlow<List<Product>>(emptyList())
    val refreshing = MutableStateFlow(false)

    @Synchronized
    fun init(ctx: Context) {
        if (::prefs.isInitialized) return
        prefs = ctx.applicationContext.getSharedPreferences("store", Context.MODE_PRIVATE)
        products.value = load()
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
                target = o.optDouble("target", 0.0),
                history = (0 until h.length()).map { j ->
                    val a = h.getJSONArray(j)
                    PricePoint(a.getLong(0), a.getDouble(1))
                },
                checked = o.optLong("checked"),
                error = o.optString("error")
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
            p.history.forEach { h.put(JSONArray().put(it.t).put(it.p)) }
            arr.put(
                JSONObject().put("id", p.id).put("url", p.url).put("title", p.title)
                    .put("price", p.price).put("target", p.target)
                    .put("checked", p.checked).put("error", p.error).put("h", h)
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

    /** Принимает текст со ссылкой (например, из "Поделиться" в Ozon) и добавляет товар. */
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
        Scheduler.now(ctx)
        return "Товар добавлен, проверяю цену…"
    }
}
