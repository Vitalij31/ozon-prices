package com.ozonprices

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

object Notifier {
    private const val CH = "prices"

    fun notify(ctx: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Изменения цен", NotificationManager.IMPORTANCE_HIGH))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = Notification.Builder(ctx, CH)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(id, n)
    }
}

object Checker {
    private val mutex = Mutex()

    /** Проверяет все товары или только один (onlyId). */
    suspend fun runAll(ctx: Context, onlyId: String? = null) = mutex.withLock {
        Store.init(ctx)
        Store.refreshing.value = true
        try {
            val ids = if (onlyId != null) listOf(onlyId) else Store.products.value.map { it.id }
            for (id in ids) checkOne(ctx, id)
        } finally {
            Store.refreshing.value = false
        }
    }

    private suspend fun checkOne(ctx: Context, id: String) {
        val p = Store.products.value.find { it.id == id } ?: return
        val useCard = Store.useCard.value
        val r = withContext(Dispatchers.Main) { Fetcher.fetch(ctx, p.url) }
        val now = System.currentTimeMillis()
        val title = if (!r?.title.isNullOrBlank()) r!!.title else p.title

        if (r == null || r.price <= 0) {
            Store.update(id) {
                it.copy(
                    title = title, checked = now,
                    raw = r?.raw ?: it.raw,
                    error = "Не удалось получить цену (Ozon мог показать проверку или изменить страницу)"
                )
            }
            return
        }

        val prev = p.eff(useCard)
        val cur = effPrice(r.price, r.card, useCard)

        Store.update(id) {
            val h = it.history
            val point = PricePoint(now, r.price, r.card)
            val newHistory = when {
                h.size >= 2 &&
                    h[h.size - 1].p == r.price && h[h.size - 1].c == r.card &&
                    h[h.size - 2].p == r.price && h[h.size - 2].c == r.card ->
                    h.dropLast(1) + point
                else -> (h + point).takeLast(500)
            }
            it.copy(
                title = title, price = r.price, card = r.card, checked = now,
                error = "", raw = r.raw, history = newHistory
            )
        }

        val dropped = prev > 0 && cur < prev
        val reached = p.target > 0 && cur <= p.target && (prev == 0.0 || prev > p.target || dropped)
        if (dropped || reached) {
            val kind = if (useCard && r.card > 0) " (с Ozon Картой)" else ""
            val text = buildString {
                if (dropped) append("Цена снизилась: ${money(prev)} → ${money(cur)}$kind.")
                if (reached) append(" Достигнута целевая цена ${money(p.target)}.")
            }.trim()
            Notifier.notify(ctx, id.hashCode(), title.ifBlank { "Товар Ozon" }.take(80), text)
        }
    }
}

class PriceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        Checker.runAll(applicationContext, inputData.getString("id"))
        return Result.success()
    }
}

object Scheduler {
    fun schedule(ctx: Context, hours: Int, replace: Boolean) {
        val req = PeriodicWorkRequestBuilder<PriceWorker>(hours.toLong(), TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
            "periodic",
            if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
            req
        )
    }

    /** Запустить проверку сейчас: одного товара (id) или всех. */
    fun now(ctx: Context, id: String? = null) {
        val b = OneTimeWorkRequestBuilder<PriceWorker>()
        if (id != null) b.setInputData(workDataOf("id" to id))
        WorkManager.getInstance(ctx).enqueueUniqueWork("now", ExistingWorkPolicy.APPEND_OR_REPLACE, b.build())
    }
}
