# Hybrid recommendations — desktop

## Previous engine and preserved behaviour

`Recommender` remains the offline candidate engine and the engine for the separate
“Similar titles” section. Its genre, keyword-theme, studio, year, audience-quality,
popularity, freshness and nearest-anchor terms remain. Cross-source deduplication,
seen-title/franchise exclusions and genre-window diversification remain.

Previously a card visit added +0.3, partial-season progress added +0.2/+1, and 80%
was sufficient for the +2 completion signal. A two-week pause near the beginning
could be treated as abandoned. These unsupported behavioural inferences are removed.
Removing a favourite is still weak explicit negative feedback, not a dropped status.

## Ratings and completion

Source weights: 5/5 = +6, 4/5 = +3.5, 3/5 = neutral, 2/5 = -3, 1/5 = -5.
An explicit rating replaces inferred behaviour for that title, so completion and
favourite status cannot turn a 1/5 into a positive example. Existing recency decay
and sample-confidence damping remain. One rating is not treated as a mature taste.

Positive history requires all main episode numbers `1..episodesTotal` in `watched`
AND `airingStatus == 1`. The highest completed episode alone is insufficient.
`episodeCounts` is an availability count, never proof of the final season length.
Ongoing/announced releases are not completed, even when caught up or manually marked.
Separately catalogued OVA/specials have separate IDs and are not prerequisites;
bonus numbers beyond the main season total are not prerequisites either. The current
Segment model has no per-segment main/special classification: ambiguous data remains
neutral rather than being guessed.

An explicit whole-title mark is now retained in additive `completedTitles`; old
episode marks are NOT retroactively reinterpreted as manual whole-title marks.
Persisted cards now retain total/available counts, airing status, content type and
country. Existing saves decode with defaults. Missing legacy metadata is enriched
asynchronously from the existing catalog source into a separate cache; playback
positions, episode marks, ratings and history are not migrated destructively.

There is no explicit dropped status in this app, so abandonedPenalty is zero.
Partial viewing, an old pause and opening a detail page supply no positive genre,
theme, studio, anchor, semantic or completed-history signal. Explicit favourites
remain valid separately from history, as requested.

## Semantic profiles and scoring

Profile facets: themes, setting, tone, characterDynamics, narrativeStyle, storyFocus,
keywords. The fallback extracts a small conservative profile from existing synopsis
keywords. AI outputs use short Russian labels. Similarity is weighted facet Jaccard:
themes 2, setting 1.2, tone 2, character dynamics 1.5, narrative style 1.5,
story focus 2, keywords 0.6. Missing information does not count as a match. Profiles
from different extraction modes are not compared as though vocabularies were identical.

Old base score is retained, plus these maximum scales (before source-strength and
confidence factors):

| Contribution | Scale |
| --- | ---: |
| Rating-neighbour similarity | +60 |
| Semantic context | +55 |
| Favourite affinity | +8 |
| Fully completed history affinity | +6 |
| Explicitly disliked neighbours | -90 |

Up to three strongest neighbours contribute to each term; evidence is averaged,
not summed over a huge history. Rating-neighbour confidence is `n / (n + 3)`.
When both AI profiles exist, rating-neighbour similarity is 35% existing metadata
overlap and 65% semantic context. Dislike uses 40% metadata and 60% context.
Source weights and the old sample-confidence factor further scale contributions.
The inherited base scales are genre 35, theme 20, studio 15, year 8, audience quality
25, popularity 4, freshness 3, nearest anchor 8, with existing negative genre/theme terms.
Seen/franchise penalties remain hard exclusions instead of redundant numeric terms.

The old engine first selects 120 candidates. Only that shortlist is semantically
reranked. Tail candidates remain available to pagination without catalog-wide AI.
The reranker preserves diversification and roughly 20% exploration opportunities.

## OpenRouter and cost controls

Model: `google/gemini-2.5-flash-lite`. Structured profiles were chosen over opaque
embeddings because this UI needs inspectable context labels and concrete explanations.
Similarity then runs locally without another embedding/rerank API dependency.

Official sources checked:

- [Model and price](https://openrouter.ai/google/gemini-2.5-flash-lite): $0.10/M input,
  $0.40/M output at implementation time; structured JSON output is supported.
- [Structured output schema](https://openrouter.ai/docs/guides/features/structured-outputs).
- [Provider price ceiling](https://openrouter.ai/docs/guides/routing/provider-selection).
- [Embeddings alternative](https://openrouter.ai/docs/api/api-reference/embeddings/create-embeddings).

Batch size 8; at most 32 relevant taste anchors and 120 candidates. A completely cold,
fully eligible selection needs at most 19 batch requests; a warm unchanged selection
needs zero. New/changed candidates can require additional batches. Maximum 20 requests
per pass and 24 per UTC day, persisted before dispatch. Provider pricing is capped at
the above rates; output is capped at 4800 tokens per batch. A typical cold pass is
estimated at a few cents (~$0.02–$0.05), not a guaranteed bill.

High-rated examples are prioritised, then negative ratings (necessary to avoid a
positive-only semantic profile), fully completed titles and other eligible anchors,
then candidates. No analysis of thousands of catalog entries. Descriptions are capped
at 3000 characters; titles and other metadata fields are bounded too.

Public catalog metadata only goes into the prompt, not ratings, viewing positions,
local IDs, history timestamps or the user's full taste profile. Metadata is explicitly
treated as untrusted data, not instructions. Credentials are in the Authorization
header only, with no redirects or logging interceptor. Response errors are sanitised.

The supplied key is stored outside the repository as a user-scoped Windows DPAPI
blob: `%APPDATA%/AniBlaze/openrouter-key.dpapi`. It is not in state.json or the build.
`ANIBLAZE_OPENROUTER_KEY` is also supported for portable/non-Windows runs.

## Cache, fallback and UI

Cache: `%APPDATA%/AniBlaze/semantic-profiles-v1.json`.
Profile key: `animeId | semanticVersion | SHA-256(canonical relevant metadata)`.
Popularity, current rating and episode availability do not invalidate story profiles.
Successful empty profiles are cached too. Schema/prompt changes require a version bump.
Atomic writes, bounded 1024 profiles and 256 metadata records, persisted request budget,
per-request reservation and 30-minute API-error cooldown prevent repeated paid loops.
One pipeline owns writes; immutable warm snapshots can be read while it is waiting
for the network. Cancellation releases the network call and keeps the budget reservation.

First paint uses local/cached results. Enrichment is asynchronous and does not run
from recomposition or progress saves. Reranking does not clear the grid; stable IDs
retain poster nodes and scroll anchors. A result is not applied after feedback,
source/pool changes or pagination. Existing no-reshuffle-on-favourite behaviour remains.
No key, service failure, damaged/unwritable cache or absent semantic data falls back
to local recommendations. No mandatory AI dependency was introduced.

Each recommendation carries its actual score decomposition and source evidence.
Positive semantic/rating explanations choose 1–3 contributing sources, not arbitrary
posters or AI-generated reasons. Click opens the source title; hover shows its title
and rating. Partial/visited titles cannot appear as watched evidence. Global-quality
recommendations without personal evidence keep an honest non-personal explanation.

## Changed files

Production: AppSettings.kt, RecommendationInput.kt, RecommendationCompletion.kt,
Recommender.kt, HybridRecommender.kt, SemanticProfile.kt, OpenRouterSemantics.kt,
SemanticRecommendationStore.kt, DesktopRepository.kt, ui/RecommendationsScreen.kt,
ui/RecommendationReason.kt, ui/Common.kt.

Regression coverage: HybridRecommendationTest, SemanticStoreTest,
OpenRouterSemanticsTest, RecommendationReasonRenderTest; adjusted old tests that
explicitly expected the now-forbidden partial-history/inactivity behaviour.
`SemanticOpenRouterLiveTest` is explicitly gated by `ANIBLAZE_SEMANTIC_LIVE=1`;
ordinary regression runs do not use paid API calls.

Backup before edits: `manual-backups/semantic-recommendations-20260902-01`.
Playback, recovery/watchdog constants, OP/ED skipping and renderer code were not changed.

## Verification

Targeted recommendation/cache/API-error tests and existing headless card stability
checks passed. Two live OpenRouter smoke requests (before and after tightening the
JSON schema) each returned two valid nonempty structured profiles and verified Windows
DPAPI credential reading. Total smoke cost: two requests, not a catalog-wide analysis.

Full regression: **576 tests, 74 suites, zero failures/errors/skips**.
`:desktop-app:test :desktop-app:bundleVlc` completed successfully. The headless
Compose source-thumbnail click opened the correct title and the reason fitted its
fixed-height area. The full XML/HTML test report is retained under the backup's
`verification` directory. Release jar: `desktop-app-1.0.2-855e4c4e6eca85c653cbbd28eb1af46.jar`.

Deployment: the recommendations were installed together with the subsequent chat/auto-timing changes
on 2026-09-03 at 05:15 Europe/Berlin after user-approved restart. Installed JAR:
`desktop-app-1.0.2-a8ee37924361c94ae1f086896d3c22ae.jar`; startup log and checksum verified.
The combined release passed 595 tests across 77 suites. See `desktop-auto-timings-chat-2026-09-03.md`.
