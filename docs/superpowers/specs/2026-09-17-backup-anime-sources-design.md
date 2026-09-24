# Запасные аниме-источники: YummyAnime, Animedia, SameBand

Дата: 2026-09-17. Модуль: `desktop-app`.

## Зачем

Сегодня все потоки приложения идут через три поставщика: Kodik (Anixart, AnimeOn),
AniLibria CDN и AnimeVost MP4. KodikSource (`kodikapi.com`) и BalancerSource
(worker → kodikapi) мертвы. Нужны запасные источники с ДРУГИМИ CDN и с
дополнительным маршрутом к Kodik/Sibnet, чтобы падение одного хоста не
оставляло тайтл без потока. Блокировки РКН не учитываются (решение пользователя).

## Проверено живьём 17.09.2026

| Источник | API | Поток |
|---|---|---|
| YummyAnime `https://api.yani.tv` | открытый JSON, без ключа: `search?q=`, `anime?limit&offset`, `anime/{id}`, `anime/{id}/videos`; в ответе `remote_ids.shikimori_id` | видео серии — iframe на `kodikplayer.com` (Kodik), `video.sibnet.ru` (progressive MP4), `alloha.yani.tv` (обфусцированный JS — не разбираем) |
| Animedia `https://amd.online` | DLE HTML; поиск `GET /index.php?do=search&subaction=search&story=`; на странице тайтла `a.nav_video_links[data-vid][data-vlnk]` | `data-vlnk` = `https://aser.pro/vod/{n}`; страница содержит `file: "https://aser.pro/content/stream/.../hls/index.m3u8"` (master, 720/360, Referer не нужен) |
| SameBand `https://sameband.studio` | DLE HTML; список `/anime/`, страница тайтла → `iframe[src^=/v/play/]` | плеер содержит `file: "/v/list/<name>_list.txt"` — JSON `[{title, file:"[480p]/v/…/index.m3u8,[720p]…,[1080p]…"}]` |

Отброшены: AnimeLib (только Kodik-эмбеды без Bearer), AniDub (плейлист требует JS),
HDrezka (обфускация).

## Дизайн

Три класса в `com.aniblaze.aggregator.source`, каждый реализует `ContentAggregator`
по образцу `AnimeVostSource`:

- `YummyAnimeSource` — префикс id `ya:{anime_id}`, опционально `:t{index}` для
  выбранной озвучки. `search` → `/search?q=`, `trending`/`catalogPage` → `/anime?limit=30&offset=`,
  `getContentSegments`/`extractContent` → `/anime/{id}/videos`: группировка по
  `data.dubbing`, озвучки → `Translation` (индекс по отсортированному имени —
  стабильный между запусками, как в AnimeOn). Поток: перебор видео нужной серии —
  выбранная озвучка первой, дальше по числу просмотров; `kodikplayer.com` →
  `KodikExtractor`, `video.sibnet.ru` → `SibnetExtractor`, остальное пропускается.
- `AnimediaSource` — префикс `am:{newsId}`. Каталог: главная и `/page/N/`
  (`a[href~=/\d+-[a-z0-9-]+\.html]`), поиск DLE. Серии: `a.nav_video_links` →
  номер из `data-vid`, ссылка из `data-vlnk` (только `aser.pro/vod/`; ссылки на
  `/index.php?do=nz` — не вышедшие, `playable=false`). Поток: страница `vod` →
  regex `file:\s*"([^"]+\.m3u8)"` → master + варианты из master (`RESOLUTION=…x720` → `720p`).
- `SameBandSource` — префикс `sb:{newsId}`. Каталог: `/anime/` и `/anime/page/N/`.
  Серии и поток из одного playlist-файла; варианты из `[480p]…,[720p]…` (лучшее
  первым); номер серии — число из `title` после снятия HTML.
- `SibnetExtractor` — вынос `AnixartSource.extractSibnet` в отдельный класс
  (используют Anixart и Yummy). Поведение Anixart не меняется.

Подключение: `Main.kt` — три записи в `aggregators`; `SettingsScreen.ALL_SOURCES` —
три имени. По умолчанию ВЫКЛЮЧЕНЫ (`enabledSources` не трогается) — пользователь
включает тумблером как запасные. `ownsContentId` по префиксу, так что чужие id в
их endpoint не попадают.

Ошибки: сетевые исключения `HttpClient` пробрасываются (как везде), «нет данных» —
пустой список/null. Пустой поток у одной озвучки — переход к следующей.

## Тесты

Live-smoke, по образцу `AnimeOnSourceSmokeTest`, для каждого источника:
1. поиск/каталог находит тайтл, id с префиксом, постер абсолютный;
2. список серий непустой, отсортирован, нумерация с 1;
3. `extractContent(ep 1)` даёт ссылку, и **поток реально отдаётся**: GET первых байт
   `.m3u8` → HTTP 200 и тело начинается с `#EXTM3U` (Kodik/Animedia/SameBand), либо
   Range-запрос `.mp4` → 200/206 (Sibnet).

Юнит-тест без сети: разбор playlist SameBand и разбор master m3u8 Animedia на фикстурах.
