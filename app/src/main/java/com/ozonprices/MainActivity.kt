package com.ozonprices

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Green = Color(0xFF2E7D32)
private val Red = Color(0xFFC62828)

class MainActivity : ComponentActivity() {
    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Scheduler.schedule(this, Store.intervalHours, false)
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { MaterialTheme { App() } }
    }
}

@Composable
fun App() {
    var screen by remember { mutableStateOf("list") }
    var selected by remember { mutableStateOf<String?>(null) }
    val sel = selected
    when {
        screen == "login" -> {
            BackHandler { screen = "settings" }
            LoginScreen { screen = "settings" }
        }
        screen == "settings" -> {
            BackHandler { screen = "list" }
            SettingsScreen(onBack = { screen = "list" }, onLogin = { screen = "login" })
        }
        sel != null -> {
            BackHandler { selected = null }
            DetailScreen(sel) { selected = null }
        }
        else -> ListScreen(onOpen = { selected = it }, onSettings = { screen = "settings" })
    }
}

private fun prevPrice(p: Product, useCard: Boolean): Double? {
    val cur = p.eff(useCard)
    return p.history.lastOrNull { it.eff(useCard) != cur }?.eff(useCard)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val ctx = LocalContext.current
    val list by Store.products.collectAsState()
    val refreshing by Store.refreshing.collectAsState()
    val useCard by Store.useCard.collectAsState()
    var showAdd by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ozon Цены") },
                actions = {
                    IconButton(onClick = {
                        Scheduler.now(ctx)
                        Toast.makeText(ctx, "Проверяю цены…", Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Обновить") }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Default.Add, contentDescription = "Добавить")
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (list.isEmpty()) {
                Text(
                    "Пока пусто.\n\nВ приложении или на сайте Ozon откройте товар → «Поделиться» → «Ozon Цены». " +
                        "Либо нажмите «+» и вставьте ссылку.",
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(list, key = { it.id }) { p -> ProductCard(p, useCard) { onOpen(p.id) } }
                }
            }
        }
    }

    if (showAdd) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Добавить товар") },
            text = {
                OutlinedTextField(
                    value = text, onValueChange = { text = it },
                    label = { Text("Ссылка на товар Ozon") }, minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Toast.makeText(ctx, Store.addFromText(ctx, text), Toast.LENGTH_LONG).show()
                    showAdd = false
                }) { Text("Добавить") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } }
        )
    }
}

private fun priceNote(p: Product, useCard: Boolean): String = when {
    p.price <= 0 -> ""
    p.card > 0 && useCard -> "с банками-партнёрами · с другими банками ${money(p.price)}"
    p.card > 0 -> "с банками-партнёрами: ${money(p.card)}"
    useCard -> "цена с банками-партнёрами не найдена, показана обычная"
    else -> ""
}

@Composable
fun ProductCard(p: Product, useCard: Boolean, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable { onClick() }) {
        Column(Modifier.padding(14.dp)) {
            Text(
                p.title.ifBlank { p.url }, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (p.price > 0) {
                    val eff = p.eff(useCard)
                    Text(money(eff), fontSize = 22.sp, style = MaterialTheme.typography.titleLarge)
                    val prev = prevPrice(p, useCard)
                    if (prev != null) {
                        val d = eff - prev
                        Spacer(Modifier.width(10.dp))
                        Text(
                            (if (d < 0) "↓ " else "↑ ") + money(kotlin.math.abs(d)),
                            color = if (d < 0) Green else Red
                        )
                    }
                } else {
                    Text(if (p.error.isNotBlank()) "Цена не получена" else "Проверяется…")
                }
            }
            val note = priceNote(p, useCard)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
            if (p.target > 0) {
                Text("Цель: ${money(p.target)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
fun PriceChart(points: List<Pair<Long, Double>>, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val min = points.minOf { it.second }
        val max = points.maxOf { it.second }
        val t0 = points.first().first
        val dt = (points.last().first - t0).coerceAtLeast(1L)
        val path = Path()
        points.forEachIndexed { i, pt ->
            val x = (pt.first - t0).toFloat() / dt * size.width
            val frac = if (max - min < 1e-9) 0.5f else ((pt.second - min) / (max - min)).toFloat()
            val y = size.height * 0.9f - frac * size.height * 0.8f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            drawCircle(color, 4.dp.toPx(), Offset(x, y))
        }
        drawPath(path, color, style = Stroke(3.dp.toPx()))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val list by Store.products.collectAsState()
    val useCard by Store.useCard.collectAsState()
    val p = list.find { it.id == id }
    if (p == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    var target by remember(p.id) {
        mutableStateOf(if (p.target > 0) p.target.toLong().toString() else "")
    }
    val df = remember { SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()) }
    val values = p.history.map { it.t to it.eff(useCard) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Товар") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { Store.remove(p.id) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(p.title.ifBlank { p.url }, style = MaterialTheme.typography.titleMedium)
            Text(
                if (p.price > 0) money(p.eff(useCard)) else "—",
                style = MaterialTheme.typography.headlineLarge
            )
            val note = priceNote(p, useCard)
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodyMedium)
            if (p.checked > 0) {
                Text("Проверено: ${df.format(Date(p.checked))}", style = MaterialTheme.typography.bodySmall)
            }
            if (p.error.isNotBlank()) Text(p.error, color = Red)

            if (values.size >= 2) {
                PriceChart(values, Modifier.fillMaxWidth().height(180.dp))
                Text(
                    "Минимум: ${money(values.minOf { it.second })}   Максимум: ${money(values.maxOf { it.second })}",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text("График появится после нескольких проверок.", style = MaterialTheme.typography.bodySmall)
            }

            OutlinedTextField(
                value = target,
                onValueChange = { target = it.filter { c -> c.isDigit() } },
                label = { Text("Целевая цена, ₽ (уведомить, когда достигнет)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Button(onClick = {
                Store.update(p.id) { it.copy(target = target.toDoubleOrNull() ?: 0.0) }
                Toast.makeText(ctx, "Сохранено", Toast.LENGTH_SHORT).show()
            }) { Text("Сохранить цель") }

            OutlinedButton(onClick = {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
            }) { Text("Открыть на Ozon") }

            if (p.raw.isNotBlank()) {
                Text("Что увидело приложение на странице:", style = MaterialTheme.typography.labelMedium)
                SelectionContainer { Text(p.raw, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val useCard by Store.useCard.collectAsState()
    var hours by remember { mutableIntStateOf(Store.intervalHours) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Настройки") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Цена с банками-партнёрами (Ozon Банк и др.)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Включено: цена «С банками» (Ozon Банк и партнёры, она ниже). Выключено: цена «С другими банками».",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = useCard, onCheckedChange = { Store.setUseCard(it) })
            }

            Text("Как часто проверять цены", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 3, 6, 12, 24).forEach { h ->
                    FilterChip(
                        selected = hours == h,
                        onClick = {
                            hours = h
                            Store.intervalHours = h
                            Scheduler.schedule(ctx, h, true)
                        },
                        label = { Text("$h ч") }
                    )
                }
            }
            Text("Аккаунт Ozon (необязательно)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Без входа приложение видит публичные цены, как гость на сайте. Если в вашем аккаунте цены " +
                    "отличаются (персональные скидки, купоны), войдите: вход происходит на сайте Ozon, " +
                    "пароль и код из SMS приложению не передаются, сохраняется только сессия браузера.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onLogin) { Text("Войти в Ozon") }
            OutlinedButton(onClick = {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                Toast.makeText(ctx, "Данные входа удалены", Toast.LENGTH_SHORT).show()
            }) { Text("Выйти и очистить данные Ozon") }

            OutlinedButton(onClick = {
                Notifier.notify(ctx, 1, "Тестовое уведомление", "Уведомления работают.")
            }) { Text("Проверить уведомления") }
            Text(
                "Если уведомления не приходят при закрытом приложении, отключите ограничение батареи для " +
                    "«Ozon Цены» в настройках Android (Приложения → Ozon Цены → Батарея → Без ограничений). " +
                    "Android может сдвигать фоновые проверки, точное время не гарантируется.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}


@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(onDone: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Войдите в аккаунт Ozon, затем нажмите «Готово»",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = {
                CookieManager.getInstance().flush()
                onDone()
            }) { Text("Готово") }
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = Fetcher.UA
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webViewClient = WebViewClient()
                    loadUrl("https://www.ozon.ru/")
                }
            },
            onRelease = { it.destroy() }
        )
    }
}
