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
 var NR='(\\d{1,3}(?:[\\s\\u00a0\\u2009]?\\d{3})*)';
 function pnum(v){ if(typeof v==='number') return v; var s=String(v).trim(); if(/^\d+([.,]\d+)?$/.test(s)) return parseFloat(s.replace(',','.')); return num(s); }
 function walk(o,f){ if(o&&typeof o==='object'){ for(var k in o){ f(k,o[k]); walk(o[k],f); } } }
 try{
  window.__oz=window.__oz||{};
  if(!window.__oz.started && location.pathname.indexOf('/product')===0){
   window.__oz.started=true;
   fetch('/api/composer-api.bx/page/json/v2?url='+location.pathname,{credentials:'include'})
    .then(function(x){return x.text();})
    .then(function(x){window.__oz.text=x;})
    .catch(function(e){window.__oz.err=String(e);});
  }
 }catch(e){}
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
  var ds='', t='', st=0, cd=0, tp=0, tc=0, bc=0, bo=0;
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
  // Поиск по тексту всей страницы: «2 558 ₽ С банками» и «2 693 ₽ С другими банками»
  var bt=(document.body&&document.body.innerText)||'';
  var m1=new RegExp(NR+'\\s*₽\\s*(?:С\\s*банками|с\\s*Ozon\\s*Картой|по\\s*Ozon\\s*Карте)','i').exec(bt);
  if(m1) bc=num(m1[1]);
  var m2=new RegExp(NR+'\\s*₽\\s*С\\s*другими\\s*банками','i').exec(bt);
  if(m2) bo=num(m2[1]);
  var ac=0, ap=0, apiInfo='api=wait';
  if(window.__oz){
   if(window.__oz.err) apiInfo='api err='+window.__oz.err;
   else if(window.__oz.text!=null){
    try{
     var J=JSON.parse(window.__oz.text), ws=J.widgetStates||{}, names=[], wp='';
     for(var key in ws){
      if(/rice|Sale/.test(key)) names.push(key);
      if(/^webPrice-/.test(key)){
       wp=wp||String(ws[key]);
       var po=JSON.parse(ws[key]);
       walk(po,function(kk,vv){
        if(typeof vv==='string'||typeof vv==='number'){
         if(kk==='cardPrice'&&!ac) ac=pnum(vv);
         if(kk==='price'&&!ap) ap=pnum(vv);
        }
       });
      }
     }
     apiInfo='api ok keys='+names.slice(0,8).join(',')+' webPrice='+wp.substring(0,300);
    }catch(e){ apiInfo='api parse err='+e+' len='+window.__oz.text.length+' head='+window.__oz.text.substring(0,120); }
   }
  }
  var price=ap||st||bo||tp||ld, card=ac||cd||bc||tc;
  if(!price&&card) price=card;
  if(card&&price&&card>price){ var x=card; card=price; price=x; }
  r.price=price; r.card=card;
  var raw=['ld='+ld,'found: card='+card+' regular='+price,'body='+bt.length,apiInfo];
  if(ds) raw.push('state='+ds.substring(0,220));
  if(t) raw.push('widget='+t.replace(/\s+/g,' ').substring(0,200));
  var sn=[], ix=-1, cnt=0;
  while(cnt<5){ ix=bt.indexOf('₽',ix+1); if(ix<0) break; sn.push(bt.substring(Math.max(0,ix-12),ix+28).replace(/\s+/g,' ')); cnt++; }
  if(sn.length) raw.push('rub='+sn.join(' // '));
  var bk=bt.search(/банк/i);
  if(bk>=0) raw.push('bank='+bt.substring(Math.max(0,bk-30),bk+60).replace(/\s+/g,' '));
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
                    if (f != null && f.price > 0 && (f.card > 0 || attempts >= 8)) finish(f)
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
