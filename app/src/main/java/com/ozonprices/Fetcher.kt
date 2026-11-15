package com.ozonprices

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

data class Fetched(val title: String, val price: Double, val card: Double, val raw: String)

/** Загружает страницу товара в скрытом WebView и достаёт название, обычную цену и цену с Ozon Картой. */
object Fetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val BLOCK_EXT = listOf(
        ".jpg", ".jpeg", ".png", ".webp", ".gif", ".svg", ".avif", ".mp4", ".webm", ".woff", ".woff2", ".ttf"
    )
    private val BLOCK_HOST = listOf("mc.yandex", "googletagmanager", "google-analytics", "doubleclick")

    private val JS = """
(function(){
 var r={title:'',price:0,card:0,raw:''};
 function num(s){ if(s==null) return 0; var n=parseInt(String(s).replace(/[^\d]/g,'')); return isNaN(n)?0:n; }
 try{
  var ld=0;
  var s=document.querySelectorAll('script[type="application/ld+json"]');
  for(var i=0;i<s.length;i++){
   try{
    var j=JSON.parse(s[i].textContent);
    var a=Array.isArray(j)?j:[j];
    for(var k=0;k<a.length;k++){
     var o=a[k];
     if(o&&o['@type']=='Product'){
      r.title=o.name||'';
      var of=o.offers;
      if(of){ if(Array.isArray(of)) of=of[0]; ld=parseFloat(of.price||of.lowPrice||0)||0; }
     }
    }
   }catch(e){}
  }
  if(!r.title){
   var m0=document.querySelector('meta[property="og:title"]');
   r.title=m0?m0.content:document.title;
  }
  var w=document.querySelector('[data-widget="webPrice"]');
  var sd=document.querySelector('[id^="state-webPrice"]');
  var ds='', t='', st=0, cd=0, tp=0, tc=0;
  if(sd) ds=sd.getAttribute('data-state')||'';
  if(!ds && w){ var inn=w.querySelector('[data-state]'); if(inn) ds=inn.getAttribute('data-state')||''; }
  if(ds){ try{ var d=JSON.parse(ds); st=num(d.price); cd=num(d.cardPrice); }catch(e){} }
  if(w) t=w.innerText||'';
  if(t){
   var re=/(\d[\d\s\u00a0\u2009]*)\s*₽/g, m, list=[];
   while((m=re.exec(t))){ var v=num(m[1]); if(v>0) list.push({v:v,s:m.index,e:re.lastIndex}); }
   var ci=-1;
   var lm=/Ozon\s*Карт|банками[-\s]*партн|партн[её]р/i.exec(t);
   if(lm && list.length){
    var le=lm.index+lm[0].length, best=1e9;
    for(var q=0;q<list.length;q++){
     var dd=(lm.index>=list[q].e)?(lm.index-list[q].e):((list[q].s>=le)?(list[q].s-le+0.5):1e8);
     if(dd<best){ best=dd; ci=q; }
    }
   }
   if(ci>=0) tc=list[ci].v;
   for(var z=0;z<list.length;z++){ if(z!==ci){ tp=list[z].v; break; } }
  }
  var price=st||tp||ld, card=cd||tc;
  if(!price&&card) price=card;
  if(card&&price&&card>price){ var x=card; card=price; price=x; }
  r.price=price; r.card=card;
  var raw=['ld='+ld];
  if(ds) raw.push('state='+ds.substring(0,220));
  if(t) raw.push('text='+t.replace(/\s+/g,' ').substring(0,200));
  r.raw=raw.join(' | ');
 }catch(e){ r.raw='js error: '+e; }
 return JSON.stringify(r);
})()
"""

    private fun parse(raw: String?): Fetched? {
        if (raw == null || raw == "null") return null
        return try {
            val s = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(s)
            Fetched(o.optString("title"), o.optDouble("price", 0.0), o.optDouble("card", 0.0), o.optString("raw"))
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, url: String): Fetched? = withTimeoutOrNull(45_000L) {
        suspendCancellableCoroutine<Fetched?> { cont ->
            val handler = Handler(Looper.getMainLooper())
            val wv = WebView(ctx.applicationContext)
            var done = false
            var started = false
            var attempts = 0
            var last: Fetched? = null

            fun finish(r: Fetched?) {
                if (done) return
                done = true
                handler.removeCallbacksAndMessages(null)
                runCatching { wv.stopLoading(); wv.onPause(); wv.loadUrl("about:blank"); wv.destroy() }
                if (cont.isActive) cont.resume(r)
            }

            fun poll() {
                if (done) return
                wv.evaluateJavascript(JS) { raw ->
                    val f = parse(raw)
                    if (f != null) last = f
                    attempts++
                    // Ждём немного дольше, чтобы блок с ценой по карте успел прогрузиться
                    if (f != null && f.price > 0 && (f.card > 0 || attempts >= 5)) finish(f)
                    else if (attempts >= 14) finish(last)
                    else handler.postDelayed({ poll() }, 1500)
                }
            }

            cont.invokeOnCancellation {
                handler.post { done = true; runCatching { wv.destroy() } }
            }

            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            wv.settings.userAgentString = UA
            wv.settings.loadsImagesAutomatically = false
            CookieManager.getInstance().setAcceptCookie(true)
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, u: String?) {
                    if (!started) {
                        started = true
                        handler.postDelayed({ poll() }, 2000)
                    }
                }

                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val scheme = request?.url?.scheme
                    return scheme != "http" && scheme != "https"
                }

                // Не грузим картинки, шрифты, видео и трекеры: меньше нагрузка на телефон и трафик
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val req = request ?: return null
                    val path = req.url.path?.lowercase() ?: ""
                    val host = req.url.host?.lowercase() ?: ""
                    if (BLOCK_EXT.any { path.endsWith(it) } || BLOCK_HOST.any { host.contains(it) }) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
            }
            wv.loadUrl(url)
        }
    }
}
