package com.dydown.app.parse

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.dydown.app.core.Http
import com.dydown.app.core.ParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import kotlin.coroutines.resume

/**
 * 页面上下文代理。
 *
 * 设计核心：本 App 不做签名逆向。隐藏 WebView 打开抖音真实页面（.douyin.com 子域共享登录态），
 * 由真实浏览器替我们过 JSVM 反爬、带登录态、并由页面自带的签名器发合法请求。
 *
 * 关键事实（真机 dump 确认）：www.douyin.com 详情页的 _ROUTER_DATA 只有 app 配置骨架，
 * 作品数据是页面水合后由前端 fetch/XHR 异步取的。所以取数不靠抠 SSR，而是：
 *   在页面脚本运行前注入钩子，挂钩 fetch / XMLHttpRequest，
 *   把含 aweme_id / play_addr / flv_pull_url 等特征字段的接口响应记录到 window.__DY_CAPTURE__，
 *   轮询到真实数据后连同 _ROUTER_DATA 一起交给 RouterDataParser 递归解析。
 * 该方案对视频 / 图集 / 直播 / 主页批量统一生效，抖音改签名算法时页面自己算，我们无感。
 */
@SuppressLint("SetJavaScriptEnabled")
class PageBridge(private val context: Context) {

    private val mutex = Mutex()
    private var wv: WebView? = null
    private var loadedUrl: String? = null
    /** 本次加载过程中 WebView 跳转过的所有 URL（含短链→视频页→推荐页），用于还原真实作品地址 */
    private val visited = mutableListOf<String>()

    private suspend fun ensureWebView(): WebView = withContext(Dispatchers.Main) {
        wv ?: run {
            val view = WebView(context.applicationContext)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            view.settings.databaseEnabled = true
            view.settings.cacheMode = WebSettings.LOAD_DEFAULT
            view.settings.userAgentString = Http.UA
            view.settings.mediaPlaybackRequiresUserGesture = false
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            wv = view
            view
        }
    }

    /**
     * 加载用户主页并滚动到底触发无限加载，一次性收集所有作品。
     * 不依赖 Web API 翻页（常被风控），而是让页面自己加载、我们只搬数据。
     */
    suspend fun loadAndScrapeUserPage(pageUrl: String): Scraped = mutex.withLock {
        withContext(Dispatchers.Main) {
            val view = ensureWebView()
            visited.clear()
            loadPage(view, pageUrl)
            var json = pollJson(view, 15_000)

            // 滚动触发无限加载：多管齐下（window + 所有可滚容器 + dispatch 事件）
            val scrollJs = """
                (function(){
                    window.scrollTo(0, document.body.scrollHeight);
                    document.documentElement.scrollTop = document.documentElement.scrollHeight;
                    var all = document.querySelectorAll('*');
                    for(var i=0;i<all.length;i++){
                        var el = all[i];
                        try{
                            if(el.scrollHeight > el.clientHeight + 50 && el.clientHeight > 80){
                                el.scrollTop = el.scrollHeight;
                            }
                        }catch(e){}
                    }
                    try{ window.dispatchEvent(new Event('scroll')); }catch(e){}
                    try{ document.dispatchEvent(new Event('scroll')); }catch(e){}
                    return 'ok';
                })()
            """.trimIndent()

            var stableRounds = 0
            for (i in 0 until 30) {
                val beforeLen = json.length
                eval(view, scrollJs)
                delay(2000)  // 给接口返回留足时间
                json = eval(view, EXTRACT_JS)
                if (json.length < beforeLen * 1.02) {
                    stableRounds++
                    if (stableRounds >= 3) break  // 连续 3 轮无新数据 → 已到底
                } else {
                    stableRounds = 0
                }
            }

            val finalUrl = eval(view, "location.href") ?: pageUrl
            dump(context, finalUrl, json)
            if (json.isEmpty()) {
                throw ParseException("页面未返回数据：可能被风控或需要重新登录。")
            }
            Scraped(finalUrl, json, null)
        }
    }

    /**
     * 加载一个抖音页面，从页面自行发起的接口响应里抓作品数据。
     *
     * 短链（v.douyin.com/xxx）处理要点：抖音"分享短链"会先进视频页、播完自动跳到推荐首页（?recommend=1）。
     * 所以这里跟踪整条跳转链，从中间那跳 /video/{id}、/note/{id} 还原真实作品地址；
     * 若在落点没抓到目标数据，就用还原出的作品地址重新直接加载一次再抓。
     */
    suspend fun loadAndScrape(pageUrl: String): Scraped = mutex.withLock {
        withContext(Dispatchers.Main) {
            val view = ensureWebView()
            visited.clear()
            loadPage(view, pageUrl)
            var json = pollJson(view, 15_000)
            var finalUrl = eval(view, "location.href") ?: pageUrl

            // 从跳转链还原真实作品地址（短链→作品页的中间那跳）
            val canon = canonicalMediaUrl(pageUrl, visited, finalUrl)
            val id = canon?.let { LinkResolver.idFromUrl(it) }
            // 当前页没抠到目标数据、且拿到了作品地址 → 重新直接加载作品页再抓一次
            if (canon != null && id != null && !json.contains(id)) {
                loadedUrl = null
                loadPage(view, canon)
                json = pollJson(view, 15_000)
                finalUrl = eval(view, "location.href") ?: canon
            }

            dump(context, finalUrl, json)
            if (json.isEmpty()) {
                throw ParseException("页面未返回数据：可能被风控、需要重新登录，或该内容已删除/设为私密。去「设置」点一次重新登录再试。")
            }
            val mediaId = canon?.let { LinkResolver.idFromUrl(it) }
                ?: LinkResolver.idFromUrl(finalUrl)
                ?: LinkResolver.idFromUrl(pageUrl)
            Scraped(finalUrl, json, mediaId)
        }
    }

    /** 从 pageUrl / 跳转链 / 最终地址里还原作品的规范地址（/video/{id} 或 /note/{id}） */
    private fun canonicalMediaUrl(pageUrl: String, visited: List<String>, finalUrl: String): String? {
        val candidates = visited + finalUrl + pageUrl
        for (u in candidates) {
            val id = LinkResolver.idFromUrl(u) ?: continue
            when (LinkResolver.classifyUrl(u)) {
                LinkKind.VIDEO -> return "https://www.douyin.com/video/$id"
                LinkKind.GALLERY -> return "https://www.douyin.com/note/$id"
                else -> {}
            }
        }
        return null
    }

    private suspend fun loadPage(view: WebView, url: String) {
        if (loadedUrl == url) return
        var finished = false
        withTimeoutOrNull(30_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                    override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                        u?.let { visited.add(it) }
                        // 页面脚本运行前注入网络钩子（幂等，避免二次包装）
                        v?.evaluateJavascript(INJECT_JS, null)
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        url?.let { visited.add(it) }
                        // 兜底再注入一次（防止 onPageStarted 时机太早被页面重置）
                        v?.evaluateJavascript(INJECT_JS, null)
                        if (!finished) { finished = true; cont.resume(Unit) }
                    }
                    override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                        if (r?.isForMainFrame == true && !finished) { finished = true; cont.resume(Unit) }
                    }
                }
                view.loadUrl(url)
            }
        }
        if (finished) loadedUrl = url
        // 给 SPA hydration / 接口往返一点时间，再开始轮询
        delay(1000)
    }

    private suspend fun pollJson(view: WebView, timeoutMs: Long): String {
        var waited = 0L
        while (waited < timeoutMs) {
            // 等到捕获的接口响应 / SSR 里出现真实媒体字段才返回
            if (eval(view, READY_JS) == "Y") return eval(view, EXTRACT_JS)
            delay(500)
            waited += 500
        }
        // 超时也返回当前内容，便于诊断
        return eval(view, EXTRACT_JS)
    }

    // —— 页面内 fetch 兜底（主页翻页用） ——

    private suspend fun loadOrigin(view: WebView, target: String) {
        if (loadedUrl == target) return
        withTimeoutOrNull(20_000) {
            suspendCancellableCoroutine<Unit> { cont ->
                var done = false
                view.webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(v: WebView?, r: WebResourceRequest?) = false
                    override fun onPageStarted(v: WebView?, u: String?, favicon: android.graphics.Bitmap?) {
                        v?.evaluateJavascript(INJECT_JS, null)
                    }
                    override fun onPageFinished(v: WebView?, url: String?) {
                        v?.evaluateJavascript(INJECT_JS, null)
                        if (!done) { done = true; cont.resume(Unit) }
                    }
                    override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: WebResourceError?) {
                        if (r?.isForMainFrame == true && !done) { done = true; cont.resume(Unit) }
                    }
                }
                view.loadUrl(target)
            }
        }
        if (true) loadedUrl = target
        delay(600)
    }

    /** 在页面上下文里请求一个接口并返回响应文本（翻页兜底用） */
    suspend fun fetch(apiUrl: String, pageOrigin: String = "https://www.douyin.com/"): String =
        mutex.withLock {
            withContext(Dispatchers.Main) {
                val view = ensureWebView()
                loadOrigin(view, pageOrigin)
                eval(view, script(apiUrl))
                awaitResult(view, 25_000)
            }
        }

    private suspend fun awaitResult(view: WebView, timeoutMs: Long): String {
        var waited = 0L
        while (waited < timeoutMs) {
            val state = eval(view, "window.__dyState||''")
            if (state == "done") {
                val data = eval(view, "window.__dyData||''")
                eval(view, "window.__dyState='';window.__dyData=''")
                if (data.isEmpty()) throw ParseException("页面内请求返回空（风控或未登录）")
                if (data.startsWith("__ERR__")) throw ParseException("页面内请求失败：${data.removePrefix("__ERR__")}")
                if (data.startsWith("__BLOCKED__")) throw ParseException("接口被风控拦截，去「设置」重新登录后重试")
                return data
            }
            delay(200)
            waited += 200
        }
        throw ParseException("页面上下文请求超时")
    }

    private fun script(apiUrl: String): String {
        val u = apiUrl.replace("\\", "\\\\").replace("\"", "\\\"")
        return """
            (function(){
              window.__dyState='busy'; window.__dyData='';
              var u="$u";
              try{
                var s=null;
                if(window.byted_acrawler&&window.byted_acrawler.frontierSign){
                  s=window.byted_acrawler.frontierSign({"url":u});
                }
                if(s){
                  for(var k in s){
                    if(/bogus/i.test(k)&&s[k]){
                      u+=(u.indexOf("?")<0?"?":"&")+"a_bogus="+encodeURIComponent(s[k]);
                      break;
                    }
                  }
                }
              }catch(e){}
              fetch(u,{credentials:'include',headers:{'accept':'application/json, text/plain, */*'}})
                .then(function(r){
                  if(r.status===403||r.status===444){window.__dyData='__BLOCKED__';window.__dyState='done';return null;}
                  return r.text();
                })
                .then(function(t){
                  if(t===null) return;
                  window.__dyData=t||'__EMPTY__';
                  window.__dyState='done';
                })
                .catch(function(e){window.__dyData='__ERR__'+e;window.__dyState='done';});
              return 'ok';
            })()
        """.trimIndent()
    }

    // —— 通用 JS 求值 ——

    private suspend fun eval(view: WebView, js: String): String =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                view.evaluateJavascript(js) { raw ->
                    if (cont.isActive) cont.resume(unquote(raw))
                }
            }
        }

    /** evaluateJavascript 返回的是 JSON 编码的字符串，需要解一层 */
    private fun unquote(raw: String?): String {
        if (raw == null || raw == "null" || raw == "undefined") return ""
        return runCatching {
            when (val v = JSONTokener(raw).nextValue()) {
                is String -> v
                null -> ""
                else -> v.toString()
            }
        }.getOrDefault(raw)
    }

    /** 诊断：把抓到的原始页面数据存到 filesDir/dydown_debug/last_scrape.json，便于用 adb pull 分析 */
    private fun dump(context: Context, url: String, json: String) {
        runCatching {
            val dir = java.io.File(context.filesDir, "dydown_debug")
            dir.mkdirs()
            java.io.File(dir, "last_scrape.json").writeText("URL: $url\n\n$json")
        }
    }

    suspend fun close() = withContext(Dispatchers.Main) {
        wv?.let {
            it.stopLoading()
            it.destroy()
        }
        wv = null
        loadedUrl = null
    }

    companion object {
        /**
         * 页面脚本运行前注入：挂钩 fetch / XMLHttpRequest，
         * 把含真实媒体特征字段的接口响应记录到 window.__DY_CAPTURE__。
         * 页面自己的签名器（a_bogus/msToken）会照常工作，我们只搬响应。
         */
        private const val INJECT_JS =
            "(function(){try{" +
                "if(window.__DY_HOOKED)return 'dup';window.__DY_HOOKED=true;" +
                "window.__DY_CAPTURE__=[];window.__DY_URLS__=[];" +
                "function rec(t,u){try{" +
                "if(typeof t!=='string'||t.length<20||t.length>3000000)return;" +
                "if(t.indexOf('aweme_id')>=0||t.indexOf('play_addr')>=0||t.indexOf('flv_pull_url')>=0||" +
                "t.indexOf('hls_pull_url')>=0||t.indexOf('live_core_sdk_data')>=0||" +
                "t.indexOf('stream_url')>=0||t.indexOf('image_post_info')>=0){" +
                "window.__DY_CAPTURE__.push(t);window.__DY_URLS__.push(String(u||''));" +
                "if(window.__DY_CAPTURE__.length>40){" +
                "window.__DY_CAPTURE__.shift();window.__DY_URLS__.shift();}" +
                "}" +
                "}catch(e){}}" +
                "try{var of=window.fetch;" +
                "window.fetch=function(){var u='';" +
                "try{var a=arguments[0];u=(typeof a==='string')?a:((a&&a.url)||'');}catch(e){}" +
                "var p=of.apply(this,arguments);" +
                "try{p.then(function(r){try{var c=r.clone();" +
                "c.text().then(function(t){rec(t,u);});}catch(e){}});}catch(e){}" +
                "return p;};}catch(e){}" +
                "try{var op=XMLHttpRequest.prototype.open;var os=XMLHttpRequest.prototype.send;" +
                "XMLHttpRequest.prototype.open=function(m,u){this.__dyU=u;return op.apply(this,arguments);};" +
                "XMLHttpRequest.prototype.send=function(){var self=this;" +
                "try{self.addEventListener('load',function(){try{" +
                "if(!self.responseType||self.responseType==='text')rec(self.responseText,self.__dyU);" +
                "else if(self.responseType==='json'&&self.response)" +
                "rec(JSON.stringify(self.response),self.__dyU);" +
                "}catch(e){}});}catch(e){}" +
                "return os.apply(this,arguments);};}catch(e){}" +
                "return 'ok';}catch(e){return 'err'}})()"

        /** 页面数据里是否已出现真实媒体字段（捕获的接口响应或 SSR 里） */
        private const val READY_JS =
            "(function(){try{var s='';" +
                "if(window.__DY_CAPTURE__)s+=window.__DY_CAPTURE__.join('');" +
                "if(window._ROUTER_DATA)s+=JSON.stringify(window._ROUTER_DATA);" +
                "if(s.indexOf('aweme_id')>=0||s.indexOf('play_addr')>=0||s.indexOf('flv_pull_url')>=0||" +
                "s.indexOf('hls_pull_url')>=0||s.indexOf('live_core_sdk_data')>=0||" +
                "s.indexOf('image_post_info')>=0)return 'Y';" +
                "return '';}catch(e){return ''}})()"

        /**
         * 取数：捕获的接口响应（正文主体）+ _ROUTER_DATA / RENDER_DATA（兜底），
         * 统一包成 {"__captures__":[...],"__router__":{...}} 交给递归解析器。
         *
         * 每条响应额外挂一个 __dy_url__ 记录请求地址：主页批量要用它区分
         * /aweme/post/（本人作品）与 /favorite/、推荐流、关注动态等"别人的作品"响应。
         * 只按字段特征抓包会把这些旁支响应一起并进列表。
         */
        private const val EXTRACT_JS =
            "(function(){try{var out={};var arr=[];" +
                "if(window.__DY_CAPTURE__){for(var i=0;i<window.__DY_CAPTURE__.length;i++){" +
                "try{var o=JSON.parse(window.__DY_CAPTURE__[i]);" +
                "var u=(window.__DY_URLS__&&window.__DY_URLS__[i])||'';" +
                "if(o&&typeof o==='object'&&!Array.isArray(o)){try{o.__dy_url__=u;}catch(e2){}}" +
                "arr.push(o);}catch(e){}}}" +
                "if(arr.length)out.__captures__=arr;" +
                "if(window._ROUTER_DATA)out.__router__=window._ROUTER_DATA;" +
                "var e=document.getElementById('RENDER_DATA');" +
                "if(e&&e.textContent){try{out.__render__=JSON.parse(decodeURIComponent(e.textContent));}catch(e2){}}" +
                "return JSON.stringify(out);}catch(e){return ''}})()"
    }
}

data class Scraped(val finalUrl: String, val json: String, val id: String? = null)
