package com.ozonprices

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/** Принимает ссылку из «Поделиться», добавляет товар и закрывается: вы остаётесь в Ozon, копий приложения не создаётся. */
class ShareActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Scheduler.schedule(this, Store.intervalHours, false)
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        val msg = if (intent?.action == Intent.ACTION_SEND) Store.addFromText(this, text) else "Ничего не добавлено"
        Toast.makeText(applicationContext, msg, Toast.LENGTH_LONG).show()
        finish()
    }
}
