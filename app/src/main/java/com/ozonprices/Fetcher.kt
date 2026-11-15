package com.ozonprices

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONTokener
import kotlin.coroutines.resume

data class Fetched(val title: String, val price: Double)

/** Загружает страницу товара в скрытом WebView и достаёт название и цену. Вызывать на главном потоке. */
object Fetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

    private val JS = """
(function(){
 var r={title:'',price:0};
 try{
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
      if(of){ if(Array.isArray(of)) of=of[0]; r.price=parseFloat(of.price||of.lowPrice||0)||0; }
     }
    }
   }catch(e){}
  }
  if(!r.title){
   var m=document.querySelector('meta[property="og:title"]');
   r.title=m?m.content:document.title;
  }
  if(!r.price){
   var w=document.querySelector('[data-widget="webPrice"]');
   if(w){
    var mm=w.innerText.match(/(\d[\d\s\u00a0\u2009]*)\s*₽/);
    if(mm) r.price=parseInt(mm[1].replace(/\D/g,''))||0;
   }
  }
 }catch(e){}
 return JSON.stringify(r);
})()
"""

    private fun parse(raw: String?): Fetched? {
        if (raw == null || raw == "null") return null
        return try {
            val s = JSONTokener(raw).nextValue() as? String ?: return null
            val o = JSONObject(s)
            Fetched(o.optString("title"), o.optDouble("price", 0.0))
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun fetch(ctx: Context, url: String): Fetched? = withTimeoutOrNull(50_000L) {
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
                runCatching { wv.stopLoading(); wv.destroy() }
                if (cont.isActive) cont.resume(r)
            }

            fun poll() {
                if (done) return
                wv.evaluateJavascript(JS) { raw ->
                    val f = parse(raw)
                    if (f != null) last = f
                    attempts++
                    if (f != null && f.price > 0) finish(f)
                    else if (attempts >= 16) finish(last)
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
            }
            wv.loadUrl(url)
        }
    }
}
