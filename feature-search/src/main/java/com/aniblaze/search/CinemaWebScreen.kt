package com.aniblaze.search

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Color as AndroidColor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aniblaze.ui.components.LoadingState
import com.aniblaze.ui.theme.TextPrimary
import java.io.ByteArrayInputStream

/**
 * Plays kinogo cinema (films / series) by loading the cinemar.cc player in a
 * WebView, so the balancer's own JS handles the rotating stream cipher plus the
 * episode / dub / quality selectors. Popups and ad redirects are blocked.
 *
 * Layout: a slim back header, then the player pinned to a 16:9 box at the top so
 * it sits nicely in portrait (rather than floating in a tall black frame). The
 * player's own fullscreen button expands to a true full-screen overlay.
 */
@Composable
fun CinemaWebScreen(
    onBack: () -> Unit,
    viewModel: CinemaWebViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp)) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Назад", tint = TextPrimary, modifier = Modifier.size(26.dp))
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
            when {
                state.error -> Text(
                    "Не удалось открыть плеер. Попробуй другой тайтл.",
                    color = TextPrimary,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                state.embedUrl == null -> LoadingState()
                else -> AndroidView(
                    factory = { ctx -> buildPlayer(ctx, state.embedUrl!!) },
                    modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
                )
            }
        }
    }
}

private fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}

@SuppressLint("SetJavaScriptEnabled")
private fun buildPlayer(context: Context, url: String): View {
    val activity = context.findActivity()
    val container = FrameLayout(context).apply { setBackgroundColor(AndroidColor.BLACK) }
    val web = WebView(context)
    container.addView(
        web,
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )
    web.setBackgroundColor(AndroidColor.BLACK)
    web.settings.apply {
        javaScriptEnabled = true
        domStorageEnabled = true
        mediaPlaybackRequiresUserGesture = false
        loadWithOverviewMode = true
        useWideViewPort = true
        // Kill the player's popup / redirect ads at the source.
        javaScriptCanOpenWindowsAutomatically = false
        setSupportMultipleWindows(false)
        userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }

    web.webChromeClient = object : WebChromeClient() {
        private var customView: View? = null

        // The player's fullscreen button → overlay on the whole window (not the 16:9 box).
        override fun onShowCustomView(view: View, callback: CustomViewCallback) {
            val root = activity?.window?.decorView as? FrameLayout ?: return
            customView = view
            view.setBackgroundColor(AndroidColor.BLACK)
            root.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
            activity.window?.let { WindowInsetsControllerCompat(it, it.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        }

        override fun onHideCustomView() {
            val root = activity?.window?.decorView as? FrameLayout
            customView?.let { root?.removeView(it) }
            customView = null
            activity?.window?.let { WindowInsetsControllerCompat(it, it.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }

    web.webViewClient = object : WebViewClient() {
        // Block navigations away from the player (ad redirects); keep the player host.
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host.orEmpty()
            return !(host.contains("cinemar") || host.contains("cinemap"))
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val host = request.url.host.orEmpty()
            return if (AD_HOSTS.any { host.contains(it) }) {
                WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            } else null
        }

        override fun onPageFinished(view: WebView, url: String) {
            super.onPageFinished(view, url)
            view.evaluateJavascript(TAP_HOOK_JS, null)
        }
    }

    web.loadUrl(url, mapOf("Referer" to "https://kinogo.ec/"))
    return container
}

/**
 * The cinemar player is built on Playerjs (playerjs.com), which — per its public
 * docs — exposes `document.getElementById(<id>).api("toolbar", "on"|"off")` to
 * force-show / auto-hide its control bar, and binds `options.id = "player"` to
 * the `<div id="player">` container we already see in the embed HTML.
 *
 * We attach a single capture-phase `click` listener on `document` (capture fires
 * top-down, before ANY of the player's own bubble-phase listeners anywhere in
 * the tree, regardless of exactly which node they're bound to) and only act when
 * the tap did NOT land on a real control (button/link/input/role=button) —
 * walking up from the click target to tell "pressed the pause button" apart from
 * "tapped empty video". On a genuine control we do nothing and let the click
 * proceed exactly as the site intends (pause button, seek, quality, etc. keep
 * working); on empty video we stop it from ever reaching Playerjs's own
 * click-to-pause handler and instead toggle the toolbar via the documented API.
 */
private const val TAP_HOOK_JS = """
(function() {
  if (window.__aniblazeHooked) return;
  window.__aniblazeHooked = true;
  var toolbarOn = false;
  function isControl(el) {
    var node = el;
    while (node && node.nodeType === 1) {
      var tag = node.tagName;
      if (tag === 'BUTTON' || tag === 'A' || tag === 'INPUT' || tag === 'SELECT' || tag === 'TEXTAREA') return true;
      var role = node.getAttribute && node.getAttribute('role');
      if (role === 'button' || role === 'slider') return true;
      node = node.parentElement;
    }
    return false;
  }
  document.addEventListener('click', function(e) {
    if (isControl(e.target)) return;
    e.stopPropagation();
    e.preventDefault();
    toolbarOn = !toolbarOn;
    try {
      var p = document.getElementById('player');
      if (p && typeof p.api === 'function') p.api('toolbar', toolbarOn ? 'on' : 'off');
    } catch (err) {}
  }, true);
})();
"""

private val AD_HOSTS = listOf(
    "doubleclick", "googlesyndication", "google-analytics", "googletagmanager", "adservice",
    "mc.yandex", "an.yandex", "yandexadexchange", "popads", "popcash", "propeller", "propellerads",
    "adsterra", "exoclick", "juicyads", "onclickads", "onclckds", "hilltopads", "trafficjunky",
    "clickadu", "adnxs", "mgid", "adskeeper", "pushwoosh", "criteo", "taboola", "outbrain",
)
