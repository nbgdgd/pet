/**
 * AniBlaze — reverse proxy for the Anixart API and the video balancers.
 *
 * RKN blocked api.anixart.tv (IP 186.2.175.11) in Russia on 2025-06-05, together
 * with every other anixart.* domain (they all point at that one IP). The balancer
 * hosts (kodikapi.com, kodik.info, …) are unreachable from the same networks —
 * verified: a direct request returns no response at all. Cloudflare's edge is NOT
 * in Russia, so it can still reach all of them and stream the answer back.
 *
 * Routing:
 *   /<path>            → https://api.anixart.tv/<path>      (unchanged, legacy)
 *   /p/<host>/<path>   → https://<host>/<path>              (allow-listed hosts)
 *
 * Only hosts in ALLOWED are proxied, so this never becomes an open relay.
 *
 * Deploy: see proxy/README.md.
 */

const ORIGIN = "https://api.anixart.tv";

/** Hosts this proxy is willing to forward to (exact match or subdomain). */
const ALLOWED = [
  "api.anixart.tv",
  // Balancers — the same set the Lampa plugin / wparty use.
  "kodikapi.com",
  "kodik.info",
  "kodik.biz",
  "kodikplayer.com",
  "aloha.tv",
  "alloha.tv",
  "api.alloha.tv",
  "apivb.info",
  "vibix.org",
  "videocdn.tv",
  "svetacdn.in",
  "lumex.host",
  "turbo.to",
  // Metadata used to resolve a title id for the balancers.
  "shikimori.one",
  "shikimori.me",
];

function isAllowed(host) {
  const h = host.toLowerCase();
  return ALLOWED.some((a) => h === a || h.endsWith("." + a));
}

function corsHeaders() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,HEAD,OPTIONS",
    "access-control-allow-headers": "*",
    "access-control-max-age": "86400",
  };
}

export default {
  async fetch(request) {
    const inUrl = new URL(request.url);

    // CORS preflight (harmless; lets the same Worker also serve a browser if ever needed).
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: corsHeaders() });
    }

    let target;
    if (inUrl.pathname.startsWith("/p/")) {
      // /p/<host>/<rest…> — generic, allow-listed pass-through for the balancers.
      const rest = inUrl.pathname.slice(3); // strip "/p/"
      const slash = rest.indexOf("/");
      const host = slash === -1 ? rest : rest.slice(0, slash);
      const path = slash === -1 ? "/" : rest.slice(slash);
      if (!host || !isAllowed(host)) {
        return new Response("host not allowed", { status: 403, headers: corsHeaders() });
      }
      target = "https://" + host + path + inUrl.search;
    } else {
      // Legacy behaviour: everything else is the Anixart API.
      target = ORIGIN + inUrl.pathname + inUrl.search;
    }

    // Forward method, headers and body verbatim. Strip hop-by-hop / CF headers so
    // the origin sees a clean request for its own host.
    const headers = new Headers(request.headers);
    headers.delete("host");
    headers.delete("cf-connecting-ip");
    headers.delete("cf-ipcountry");
    headers.delete("x-forwarded-for");
    headers.delete("x-forwarded-proto");
    headers.delete("x-real-ip");

    const hasBody = request.method !== "GET" && request.method !== "HEAD";
    const init = {
      method: request.method,
      headers,
      body: hasBody ? await request.arrayBuffer() : undefined,
      redirect: "follow",
    };

    let originResp;
    try {
      originResp = await fetch(target, init);
    } catch (e) {
      return new Response("upstream fetch failed: " + e, { status: 502 });
    }

    const respHeaders = new Headers(originResp.headers);
    respHeaders.set("access-control-allow-origin", "*");
    // Let the app's own OkHttp cache layer decide; don't let CF cache the API.
    respHeaders.delete("set-cookie");

    return new Response(originResp.body, {
      status: originResp.status,
      statusText: originResp.statusText,
      headers: respHeaders,
    });
  },
};
