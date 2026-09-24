from flask import Flask, request, render_template_string
import requests
from bs4 import BeautifulSoup

app = Flask(__name__)

@app.route("/", methods=["GET"])
def index():
    q = request.args.get("q", "").strip()
    if not q:
        return render_template_string(TEMPLATE, query="", results=None, error=None)

    results = None
    error = None

    try:
        url = "https://html.duckduckgo.com/html/"
        resp = requests.get(url, params={"q": q}, headers={
            "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                          "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }, timeout=15)
        resp.raise_for_status()

        soup = BeautifulSoup(resp.text, "html.parser")
        items = []
        for r in soup.select(".result"):
            a = r.select_one(".result__title a")
            snippet_el = r.select_one(".result__snippet")
            if a:
                title = a.get_text(strip=True)
                href = a.get("href", "")
                snippet = snippet_el.get_text(strip=True) if snippet_el else ""
                # DuckDuckGo HTML wrapper uses redirect URLs
                if "uddg=" in href:
                    from urllib.parse import unquote, parse_qs, urlparse
                    parsed = urlparse(href)
                    qs = parse_qs(parsed.query)
                    if "uddg" in qs:
                        href = unquote(qs["uddg"][0])
                items.append({"title": title, "href": href, "snippet": snippet})
        results = items if items else []
    except Exception as e:
        error = str(e)

    return render_template_string(TEMPLATE, query=q, results=results, error=error)


TEMPLATE = r"""<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Поиск</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:#111;color:#e0e0e0;display:flex;flex-direction:column;align-items:center;padding-top:120px;min-height:100vh}
h1{font-size:1.1rem;font-weight:400;color:#888;margin-bottom:24px}
form{display:flex;gap:8px;width:100%;max-width:560px;padding:0 16px}
input[type=text]{flex:1;padding:10px 14px;border:1px solid #333;border-radius:6px;background:#1a1a1a;color:#e0e0e0;font-size:15px;outline:none}
input[type=text]:focus{border-color:#555}
button{padding:10px 20px;border:none;border-radius:6px;background:#2a2a2a;color:#ccc;font-size:15px;cursor:pointer}
button:hover{background:#333}
.results{width:100%;max-width:560px;padding:0 16px;margin-top:32px}
.results .item{margin-bottom:24px}
.results .item a{color:#8ab4f8;text-decoration:none;font-size:15px;word-break:break-all}
.results .item a:hover{text-decoration:underline}
.results .item .url{color:#888;font-size:12px;margin-top:2px;word-break:break-all}
.results .item .snippet{color:#aaa;font-size:13px;margin-top:4px;line-height:1.5}
.empty{color:#666;text-align:center;margin-top:48px;font-size:14px}
.error{color:#c55;text-align:center;margin-top:48px;font-size:14px}
</style>
</head>
<body>
<h1>Поиск</h1>
<form method="GET" action="/">
  <input type="text" name="q" placeholder="Что искать…" value="{{ query }}" autofocus>
  <button type="submit">Поиск</button>
</form>
{% if error %}
  <p class="error">{{ error }}</p>
{% elif results is not none %}
  <div class="results">
  {% if results %}
    {% for r in results %}
    <div class="item">
      <a href="{{ r.href }}" target="_blank" rel="noopener">{{ r.title }}</a>
      <div class="url">{{ r.href }}</div>
      <div class="snippet">{{ r.snippet }}</div>
    </div>
    {% endfor %}
  {% else %}
    <p class="empty">Ничего не найдено</p>
  {% endif %}
  </div>
{% else %}
  <p class="empty">&nbsp;</p>
{% endif %}
</body>
</html>"""

if __name__ == "__main__":
    import sys
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8080
    print(f"\n  -> http://localhost:{port}\n")
    app.run(host="127.0.0.1", port=port)
