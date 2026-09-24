/**
 * AniBlaze — reverse proxy for the Anixart API, for Deno Deploy.
 *
 * Same job as proxy/worker.js but on Deno's edge (*.deno.dev), which is a
 * different domain/IP from Cloudflare's workers.dev — useful where an RF provider
 * blocks workers.dev specifically. Always-on (no sleep), free.
 *
 * Only the API host needs this; posters (s.anixmirai.com) and video
 * (kodikplayer.com) are on un-blocked hosts and are hit directly by the app.
 *
 * Deploy (≈2 min, free):
 *   1. https://dash.deno.com  → sign in with GitHub.
 *   2. "New Playground".
 *   3. Replace the sample with this whole file → it deploys automatically.
 *   4. Copy the URL it shows, e.g. https://aniblaze-xxxx.deno.dev
 *   5. Send that URL back to wire into the app's PROXY_HOSTS.
 *
 * Verify:  open https://<your>.deno.dev/release/random — should show JSON.
 */

const ORIGIN = "https://api.anixart.tv";

Deno.serve(async (req: Request) => {
  const url = new URL(req.url);

  if (req.method === "OPTIONS") {
    return new Response(null, {
      status: 204,
      headers: {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET,POST,HEAD,OPTIONS",
        "access-control-allow-headers": "*",
      },
    });
  }

  const target = ORIGIN + url.pathname + url.search;

  const headers = new Headers(req.headers);
  headers.delete("host");

  const hasBody = req.method !== "GET" && req.method !== "HEAD";
  let resp: Response;
  try {
    resp = await fetch(target, {
      method: req.method,
      headers,
      body: hasBody ? await req.arrayBuffer() : undefined,
      redirect: "follow",
    });
  } catch (e) {
    return new Response("upstream fetch failed: " + e, { status: 502 });
  }

  const respHeaders = new Headers(resp.headers);
  respHeaders.set("access-control-allow-origin", "*");
  respHeaders.delete("set-cookie");

  return new Response(resp.body, {
    status: resp.status,
    statusText: resp.statusText,
    headers: respHeaders,
  });
});
