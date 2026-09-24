package com.aniblaze.aggregator.lampa

import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import java.util.concurrent.TimeUnit

/**
 * A minimal HEADLESS emulation of the Lampa runtime, enough to run a Lampa
 * "online" plugin (e.g. nb557's online_mod.js) and drive its balancers without a
 * UI. The plugin is ES5, so it runs in Rhino; we shim the `Lampa.*` API it calls:
 * network → OkHttp (synchronous), Storage → a map, Player.play → a capture hook,
 * Component.add → a capture hook, plus a no-op jQuery/Template so its DOM-building
 * code doesn't crash. This is the "add a plugin by URL, use its sources" path.
 *
 * PHASE 1: load a plugin and capture the component it registers. Driving a
 * balancer's search/extract is layered on top of this.
 */
class LampaRuntime(private val okHttp: OkHttpClient) {

    private val sandboxHttp = LampaNetworkPolicy.sandboxed(okHttp)

    private val contextFactory = object : ContextFactory() {
        override fun makeContext(): Context = super.makeContext().apply {
            optimizationLevel = -1 // interpreted mode: no 64KB method limit for big plugins
            languageVersion = Context.VERSION_ES6
            instructionObserverThreshold = 10_000
            // Плагин — удалённый недоверенный код. Ни Packages/java, ни getClass
            // не должны открывать ему JVM, файловую систему или запуск процессов.
            setClassShutter(ClassShutter { false })
        }

        override fun observeInstructionCount(cx: Context, instructionCount: Int) {
            if (Thread.currentThread().isInterrupted) {
                throw RuntimeException("Lampa script execution cancelled")
            }
        }
    }
    private val cx: Context = contextFactory.enterContext()
    private val scope: Scriptable = cx.initSafeStandardObjects(null, false)

    /** Components the loaded plugin registered via Lampa.Component.add. */
    val components = LinkedHashMap<String, Scriptable>()

    /** The most recent object handed to Lampa.Player.play / .playlist (JSON). */
    @Volatile var lastPlay: String? = null
        private set

    /** The most recent cdnvideohub series playlist JSON (all seasons/episodes/dubs). */
    @Volatile var lastPlaylist: String? = null

    private val logs = ArrayList<String>()
    val log: List<String> get() = logs

    /** Реализация моста. Сам Java-объект в JS не публикуется. */
    private inner class Bridge {
        fun request(url: String, post: String?, headersJson: String?, timeoutMs: Int): String? = runCatching {
            log("req ${if (post != null) "POST" else "GET"} ${url.take(90)}")
            val safeUrl = LampaNetworkPolicy.validateHttps(url)
            val client = sandboxHttp.newBuilder()
                .callTimeout(timeoutMs.takeIf { it > 0 }?.coerceIn(1_000, 20_000)?.toLong() ?: 15_000L, TimeUnit.MILLISECONDS)
                .build()
            val b = Request.Builder().url(safeUrl)
            headersJson?.takeIf { it.isNotBlank() }?.let { hj ->
                val headers = JSONObject(hj)
                headers.keys().forEach { key ->
                    if (key.lowercase() !in BLOCKED_HEADERS) b.header(key, headers.optString(key))
                }
            }
            if (post != null) b.post(post.toRequestBody("application/x-www-form-urlencoded".toMediaTypeOrNull()))
            val body = client.newCall(b.build()).execute().use { response ->
                if (!response.isSuccessful) null
                else LampaNetworkPolicy.readLimited(response.body, LampaNetworkPolicy.MAX_RESPONSE_BYTES)
            }
            // Capture the cdnvideohub series playlist (all seasons/episodes/dubs) — the
            // plugin fetches it with the auth the direct endpoint rejects, so we reuse it.
            if (safeUrl.encodedPath.contains("sv/playlist") && body != null && body.contains("\"items\"")) lastPlaylist = body
            body
        }.onFailure { log("request blocked/failed: ${it.message}") }.getOrNull()

        fun onComponent(name: String, comp: Scriptable) { components[name] = comp }
        fun onPlay(json: String) { lastPlay = json }
        fun onPlaylist(json: String) { lastPlay = json }
        fun log(msg: String) { logs.add(msg.take(500)); if (logs.size > 400) logs.removeAt(0) }
        fun atob(s: String): String = runCatching {
            String(java.util.Base64.getMimeDecoder().decode(s), Charsets.ISO_8859_1)
        }.getOrDefault("")
        fun btoa(s: String): String = runCatching {
            java.util.Base64.getEncoder().encodeToString(s.toByteArray(Charsets.ISO_8859_1))
        }.getOrDefault("")
    }

    init {
        installBridge(Bridge())
        cx.evaluateString(scope, SHIM_JS, "lampa-shim", 1, null)
    }

    /**
     * Публикует чистый JS-объект из BaseFunction. В отличие от javaToJS(Bridge), у
     * него нет getClass(), reflection и прочих унаследованных Java-методов.
     */
    private fun installBridge(bridge: Bridge) {
        val target = cx.newObject(scope)
        fun function(name: String, action: (Array<out Any>) -> Any?) {
            val fn = object : BaseFunction() {
                override fun call(cx: Context, scope: Scriptable, thisObj: Scriptable, args: Array<out Any>): Any =
                    action(args)?.let { Context.javaToJS(it, this@LampaRuntime.scope) }
                        ?: Context.getUndefinedValue()
            }
            ScriptableObject.putProperty(target, name, fn)
        }
        fun text(args: Array<out Any>, index: Int): String? = args.getOrNull(index)?.let(Context::toString)
            ?.takeUnless { it == "undefined" || it == "null" }

        function("request") { args ->
            bridge.request(
                text(args, 0).orEmpty(),
                text(args, 1),
                text(args, 2),
                text(args, 3)?.toDoubleOrNull()?.toInt() ?: 0,
            )
        }
        function("onComponent") { args ->
            val component = args.getOrNull(1) as? Scriptable
            if (component != null) bridge.onComponent(text(args, 0).orEmpty(), component)
            null
        }
        function("onPlay") { args -> bridge.onPlay(text(args, 0).orEmpty()); null }
        function("onPlaylist") { args -> bridge.onPlaylist(text(args, 0).orEmpty()); null }
        function("log") { args -> bridge.log(text(args, 0).orEmpty()); null }
        function("atob") { args -> bridge.atob(text(args, 0).orEmpty()) }
        function("btoa") { args -> bridge.btoa(text(args, 0).orEmpty()) }
        ScriptableObject.putProperty(scope, "__bridge", target)
    }

    /** Load a plugin's JS source. Returns null on success, or the error message. */
    fun loadPlugin(js: String): String? = runCatching {
        require(js.toByteArray(Charsets.UTF_8).size <= LampaNetworkPolicy.MAX_PLUGIN_BYTES) {
            "Lampa plugin is too large"
        }
        cx.evaluateString(scope, js, "plugin", 1, null)
        null
    }.getOrElse { it.message ?: it.toString() }

    /** One extracted dub: its display title (usually a studio), the default stream
     *  url, and the quality ladder (label → url). */
    data class LampaStream(val title: String, val url: String, val qualities: List<Pair<String, String>>)

    /**
     * Drive a plugin [component] with the chosen [balancer] to extract EVERY dub for
     * a movie: instantiate it with the metadata, run its search (synchronous
     * network), then fire each rendered result card's enter handler — each reaches
     * Lampa.Player.play with that dub's stream, which we parse. Returns one
     * [LampaStream] per dub the balancer offers (empty if it found nothing).
     */
    fun resolve(component: String, balancer: String, movie: Map<String, Any?>, season: Int = 0): List<LampaStream> {
        val comp = components[component] as? Function ?: return emptyList()
        val out = ArrayList<LampaStream>()
        lastPlaylist = null
        runCatching {
            cx.evaluateString(scope, "__storage['${component}_balanser']=${jsStr(balancer)}; __clearEnter();", "prep", 1, null)
            // Preload the season choice so the plugin's extendChoice() (run inside
            // create()) renders episodes for the requested season, not just S1. The
            // plugin silently substitutes an unknown balancer with its default one,
            // so seed the choice under EVERY known source name — whichever source
            // actually runs finds the requested (0-based) season index.
            if (season > 0) {
                val movieId = (movie["id"] ?: "").toString()
                val choice = "{ ${jsStr(movieId)}: { season: ${season - 1}, voice: 0 } }"
                for (name in CHOICE_KEYS) {
                    cx.evaluateString(scope, "__storage['online_mod_choice_$name']=$choice;", "seasonprep", 1, null)
                }
            }
            val obj = newObject(
                mapOf(
                    "movie" to newObject(movie),
                    "id" to (movie["id"] ?: movie["kinopoisk_id"] ?: ""),
                    "component" to component,
                    "search" to (movie["title"] ?: ""),
                    "clarification" to false,
                    "activity" to newObject(mapOf<String, Any?>()),
                ),
            )
            val instance = comp.construct(cx, scope, arrayOf(Context.javaToJS(obj, scope)))
            // Lampa sets .activity on the instance externally; the component's create()
            // dereferences this.activity.loader immediately, so provide a stub.
            val activity = cx.evaluateString(
                scope,
                "({ loader:function(){}, toggle:function(){}, active:function(){return true;}, canRefresh:function(){return false;}, need_size:'' })",
                "act", 1, null,
            )
            ScriptableObject.putProperty(instance as ScriptableObject, "activity", activity)
            runCatching { call(instance, "create") }.onFailure { logs.add("create: ${it.message}") }
            val count = (cx.evaluateString(scope, "__enterCount();", "c", 1, null) as? Number)?.toInt() ?: 0
            for (i in 0 until count) {
                lastPlay = null
                runCatching { cx.evaluateString(scope, "try{__triggerEnter($i);}catch(e){__bridge.log('trig '+e);}", "t", 1, null) }
                lastPlay?.let { json -> parsePlay(json)?.let { s -> if (out.none { it.url == s.url }) out.add(s) } }
            }
        }.onFailure { logs.add("resolve: ${it.message}") }
        return out
    }

    private fun parsePlay(json: String): LampaStream? = runCatching {
        val el = kotlinx.serialization.json.Json.parseToJsonElement(json)
        val obj = when (el) {
            is kotlinx.serialization.json.JsonArray -> el.firstOrNull() as? kotlinx.serialization.json.JsonObject
            is kotlinx.serialization.json.JsonObject -> el
            else -> null
        } ?: return null
        val url = (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.startsWith("http") } ?: return null
        val title = (obj["title"] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()?.trim().orEmpty()
        val qualities = (obj["quality"] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (k, v) ->
            val u = (v as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.startsWith("http") } ?: return@mapNotNull null
            k.replace(Regex("[\\u200B\\uFEFF\\u00A0]"), "").trim() to u
        }.orEmpty()
        LampaStream(title, url, qualities)
    }.getOrNull()

    private fun kotlinx.serialization.json.JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun jsStr(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

    fun close() { runCatching { Context.exit() } }

    /** Call a JS function on a scope object, returning the raw result. */
    fun call(obj: Scriptable, method: String, vararg args: Any?): Any? {
        val fn = ScriptableObject.getProperty(obj, method) as? Function ?: return null
        return fn.call(cx, scope, obj, args.map { Context.javaToJS(it, scope) }.toTypedArray())
    }

    fun newObject(fields: Map<String, Any?>): Scriptable {
        val o = cx.newObject(scope)
        fields.forEach { (k, v) -> ScriptableObject.putProperty(o, k, Context.javaToJS(v, scope)) }
        return o
    }

    private companion object {
        val BLOCKED_HEADERS = setOf("host", "content-length", "connection", "transfer-encoding", "upgrade")
        // Source names the online_mod plugin may actually run (live ones plus the
        // legacy names a stored preference can still carry). The season choice is
        // seeded under each, because the plugin silently swaps unknown balancers
        // for its default.
        val CHOICE_KEYS = listOf(
            "rezka2", "cdnvideohub", "kodik", "filmix",
            "rezka", "cdnmovies", "vibix", "collaps",
        )
        // The Lampa runtime shim. Pure JS (uses the injected `__bridge` for network
        // and capture). Kept intentionally permissive: unknown UI methods are no-ops
        // so a plugin's rendering code runs without a DOM.
        const val SHIM_JS = """
var window = this; var self = this; var globalThis = this;
var navigator = { userAgent: 'Lampa', platform: 'Win32' };
var location = { href: 'https://cub.rip/', protocol: 'https:', host: 'cub.rip', hostname: 'cub.rip', origin: 'https://cub.rip', pathname: '/', search: '' };
var document = { createElement: function(){ return jqNode(); }, body: jqNode(), documentElement: jqNode(), addEventListener: function(){}, cookie: '' };
var console = { log: function(){ __bridge.log(Array.prototype.join.call(arguments,' ')); }, error: function(){ __bridge.log('ERR '+Array.prototype.join.call(arguments,' ')); }, warn: function(){}, info: function(){} };
function setTimeout(fn){ try{ if(typeof fn==='function') fn(); }catch(e){ __bridge.log('setTimeout '+e); } return 0; }
function clearTimeout(){} function setInterval(){ return 0; } function clearInterval(){}
function atob(s){ return __bridge.atob(''+s); }
function btoa(s){ return __bridge.btoa(''+s); }
function requestAnimationFrame(fn){ try{ fn(); }catch(e){} return 0; }

// Captured 'hover:enter' handlers of rendered result cards — the driver invokes
// the first to trigger stream extraction (getStream → Player.play).
var __enterHandlers = [];
function __clearEnter(){ __enterHandlers = []; }
function __enterCount(){ return __enterHandlers.length; }
function __triggerEnter(i){ if(__enterHandlers[i]){ try{ __enterHandlers[i].call(); return true; }catch(e){ __bridge.log('enter '+e); } } return false; }

// Minimal chainable jQuery-like node so DOM-building code doesn't crash.
function jqNode(){
  var n = { length: 0, __lampa_node: true };
  var chain = ['append','appendTo','prepend','before','after','off','one','find','closest','parent','parents','children','first','last','eq','next','prev','html','text','val','attr','removeAttr','addClass','removeClass','toggleClass','hasClass','css','remove','empty','detach','click','trigger','focus','blur','hide','show','toggle','animate','stop','width','height','scrollTop','data','each','map','filter','not','is','clone','wrap','insertAfter','insertBefore','replaceWith','add'];
  for (var i=0;i<chain.length;i++){ (function(m){ n[m]=function(){ if(m==='html'||m==='text'||m==='val'||m==='attr'||m==='css'||m==='data'||m==='width'||m==='height') return arguments.length? n : ''; return n; }; })(chain[i]); }
  n.on = function(ev, fn){ if(typeof ev==='string' && ev.indexOf('enter')>=0 && typeof fn==='function') __enterHandlers.push(fn); return n; };
  n.get = function(){ return n; }; n.toArray = function(){ return []; };
  n.render = function(){ return n; }; n.size = function(){ return 0; }; n.index = function(){ return 0; };
  n.offset = function(){ return { top:0, left:0 }; }; n.position = function(){ return { top:0, left:0 }; };
  return n;
}
function ${'$'}(sel){ if (sel && sel.__lampa_node) return sel; return jqNode(); }
${'$'}.ajax = function(o){ var d={done:function(f){return d;},fail:function(){return d;},always:function(){return d;},then:function(){return d;}}; return d; };
${'$'}.each = function(a, fn){ if(a&&a.length!==undefined){ for(var i=0;i<a.length;i++) fn(i, a[i]); } else { for(var k in a) fn(k, a[k]); } };
${'$'}.parseHTML = function(){ return [jqNode()]; };
${'$'}.extend = function(){ var t=arguments[0]||{}; for(var i=1;i<arguments.length;i++){ var s=arguments[i]; for(var k in s) t[k]=s[k]; } return t; };
${'$'}.isArray = function(a){ return Object.prototype.toString.call(a)==='[object Array]'; };

function reguest(){
  var to = 15000;
  this.timeout = function(t){ to = t|0; return this; };
  this.clear = function(){ return this; };
  this.cancel = function(){ return this; };
  var self = this;
  function doReq(url, success, error, post, params){
    try {
      var headers = params && params.headers ? JSON.stringify(params.headers) : '';
      var body = (typeof post === 'string') ? post : (post ? JSON.stringify(post) : null);
      var res = __bridge.request(''+url, body, headers, to);
      if (res === null || res === undefined){ if(error) error({status:0}, 'network'); return; }
      var out = res; try { out = JSON.parse(res); } catch(e) {}
      if (success) success(out, res);
    } catch(e){ __bridge.log('reguest '+e+' @'+((e&&e.lineNumber)||'?')); if(error) error({status:0}, ''+e); }
  }
  this['native'] = function(url, success, error, post, params){ doReq(url, success, error, post, params); };
  this.silent = function(url, success, error, post, params){ doReq(url, success, error, post, params); };
  this.get = function(url, success, error){ doReq(url, success, error, null, null); };
  this.post = function(url, post, success, error){ doReq(url, success, error, post, null); };
  this.errorDecode = function(a, c){ return ''+(c || (a && a.status) || 'error'); };
  this.render = function(){ return jqNode(); };
}

var __storage = {};
var Lampa = {
  Reguest: reguest,
  Storage: {
    get: function(k, d){ return (k in __storage) ? __storage[k] : (d===undefined?'':d); },
    set: function(k, v){ __storage[k] = v; },
    field: function(k){ return __storage[k]; },
    cache: function(k, size, d){ if(!(k in __storage)) __storage[k] = (d===undefined?{}:d); return __storage[k]; },
    add: function(){}, sync: function(){},
    listener: { follow: function(){}, send: function(){}, remove: function(){} }
  },
  Utils: {
    hash: function(str){ str=''+str; var h=0,i,c; if(str.length===0) return h; for(i=0;i<str.length;i++){ c=str.charCodeAt(i); h=((h<<5)-h)+c; h=h&h; } return Math.abs(h); },
    addUrlComponent: function(url, comp){ url=''+url; return url + (url.indexOf('?')>=0?'&':'?') + comp; },
    shortText: function(t){ return ''+t; },
    cardImgBackground: function(){ return ''; },
    copyTextToClipboard: function(){}, protocol: function(){ return 'https://'; },
    capitalizeFirstLetter: function(s){ s=''+s; return s.charAt(0).toUpperCase()+s.slice(1); },
    ymd: function(){ return ''; }, secondsToTime: function(){ return ''; },
    parseTime: function(){ return {}; }, isValidUrl: function(){ return true; }
  },
  Player: {
    play: function(o){ try{ __bridge.onPlay(JSON.stringify(o||{})); }catch(e){} },
    playlist: function(l){ try{ __bridge.onPlaylist(JSON.stringify(l||[])); }catch(e){} },
    callback: function(){}, listener: { follow: function(){}, send: function(){} }
  },
  Component: { add: function(name, comp){ __bridge.onComponent(''+name, comp); }, get: function(){}, all: function(){ return {}; } },
  Platform: { is: function(){ return false; }, screen: function(){ return false; }, tv: function(){ return false; } },
  Arrays: {
    clone: function(a){ try{ return JSON.parse(JSON.stringify(a)); }catch(e){ return a; } },
    getKeys: function(o){ var r=[]; for(var k in o) r.push(k); return r; },
    isArray: function(a){ return Object.prototype.toString.call(a)==='[object Array]'; },
    extend: function(a,b,deep){ if(b) for(var k in b){ a[k]=b[k]; } return a; },
    shuffle: function(a){ return a; }, unique: function(a){ return a; }
  },
  Lang: { translate: function(k){ return ''+k; }, add: function(){} },
  Manifest: { plugins: {}, apk_version: '3.0.0', origin: 'lampa', app_digital: 190 },
  Account: { hasPremium: function(){ return false; }, working: function(){ return false; }, logged: function(){ return false; }, listener: { follow: function(){}, send: function(){} } },
  Listener: { follow: function(){}, send: function(){}, remove: function(){} },
  Favorite: { add: function(){}, remove: function(){}, check: function(){ return {}; } },
  Timeline: { view: function(){ return { time:0, duration:0, percent:0 }; }, update: function(){}, handler: function(){}, render: function(){ return jqNode(); } },
  Noty: { show: function(){}, close: function(){} },
  Modal: { open: function(){}, close: function(){}, update: function(){}, title: function(){} },
  Loading: { start: function(){}, stop: function(){}, close: function(){} },
  Template: { get: function(){ return jqNode(); }, add: function(){}, js: function(){ return ''; } },
  Controller: { add: function(){}, toggle: function(){}, enabled: function(){ return { name: '' }; }, own: function(){}, collectionSet: function(){}, collectionFocus: function(){} },
  Activity: { active: function(){ return { activity: { toggle: function(){} } }; }, push: function(){}, replace: function(){}, out: function(){} },
  Scroll: function(){ this.render=function(){ return jqNode(); }; this.append=function(){}; this.minus=function(){}; this.body=function(){ return jqNode(); }; this.update=function(){}; this.reset=function(){}; this.clear=function(){}; this.destroy=function(){}; this.onEnd=function(){}; this.onScroll=function(){}; this.onWheel=function(){}; },
  Select: { show: function(){}, hide: function(){} },
  Settings: { main: function(){ return { render: function(){ return jqNode(); } }; }, update: function(){}, listener: { follow: function(){} } },
  Params: { values: {}, select: function(){}, trigger: function(){}, insert: function(){}, listener: { follow: function(){}, send: function(){} } },
  Filter: function(){ this.render=function(){ return jqNode(); }; this.set=function(){}; this.chosen=function(){}; this.addButtonBack=function(){}; this.onSelect=function(){}; this.onBack=function(){}; },
  Api: { sources: {}, img: function(u){ return ''+u; } },
  TMDB: { key: function(){ return ''; }, api: function(u){ return 'https://api.themoviedb.org/3/'+u; }, image: function(u){ return ''+u; }, close: function(){} },
  VPN: { enabled: function(){ return false; }, checkList: function(){} },
  Helper: { show: function(){}, add: function(){} },
  Background: { immediate: function(){}, change: function(){}, remove: function(){} },
  Explorer: function(object){ this.render=function(){ return jqNode(); }; this.appendFiles=function(){}; this.appendHead=function(){}; this.back=function(){}; this.destroy=function(){}; this.explorer=function(){ return jqNode(); }; this.scroll=function(){ return { render: function(){ return jqNode(); }, append: function(){}, minus: function(){}, body: function(){ return jqNode(); }, update: function(){} }; }; },
  Push: { create: function(){}, update: function(){}, clear: function(){} },
  Reload: { call: function(){} },
  Socket: { send: function(){}, on: function(){} },
  Utils2: {}, SubtitlesTrack: {}, Torserver: {}, Extensions: function(){ return { get: function(){ return []; } }; }
};
window.Lampa = Lampa;
window.jQuery = ${'$'}; window.${'$'} = ${'$'};
"""
    }
}
