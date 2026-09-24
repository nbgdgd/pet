package com.aniblaze.desktop

import com.aniblaze.aggregator.model.*
import com.aniblaze.aggregator.source.*
import com.aniblaze.desktop.player.*
import com.aniblaze.desktop.ui.buildStats
import com.aniblaze.desktop.ui.statsMetadataBatch
import com.aniblaze.network.HttpClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.suspendCoroutineUninterceptedOrReturn
import kotlin.test.*

class TargetedFixRegressionTest {
    private val card = PersistedAnime("audit:a", "Audit", "")
    private fun state() = PersistedState(watchedAtBackfilled=true, vostMergeMigrated=true,
        episodeCounts=mapOf(card.id to 12))
    private fun save(s: PersistedState, ep: Int, pos: Long, at: Long, measured: Long? = null) =
        applyEpisodeProgress(s, card, ep, pos, 1_000_000, at, "2026-09-05", measured)
    private fun app(s: PersistedState = state()): AppSettings {
        val f=Files.createTempDirectory("aniblaze-fix-test-").resolve("state.json").toFile()
        f.writeText(Json.encodeToString(PersistedState.serializer(),s))
        return AppSettings(f)
    }

    @Test fun staleContinueRemovedButFreshReplayKept() {
        val done=save(save(state(),9,400_000,100),10,950_000,200)
        val settings=app(done)
        assertTrue(settings.continueWatching().isEmpty())
        assertEquals(11,settings.resumeSegment(card.id,card.id,(1..12).toList()){true})
        val replay=app(save(done,9,200_000,300))
        assertEquals(listOf(card.id),replay.continueWatching().map{it.id})
        assertEquals(9,replay.resumeSegment(card.id,card.id,(1..12).toList()){true})
    }
    @Test fun lastCompletedIsChronologicalAndBarCountsMarks() {
        val s=save(save(state(),10,950_000,100),2,950_000,200)
        val w=buildWatchIndex(s).getValue(card.id)
        assertEquals(2,w.episode)
        assertEquals(2f/12,w.overall,0.0001f)
        assertEquals(3,resumeSegmentOf(s,card.id,card.id,(1..12).toList()){true})
        val finalOnly=buildWatchIndex(save(state(),12,950_000,100)).getValue(card.id)
        assertFalse(finalOnly.finished); assertEquals(1f/12,finalOnly.overall,0.0001f)
    }
    @Test fun replayDoesNotDoubleCountWholeTitleProgress() {
        val done=state().copy(watched=setOf("audit:a#1","audit:a#2"))
        val w=buildWatchIndex(save(done,1,500_000,200)).getValue(card.id)
        assertEquals(2f/12,w.overall,0.0001f)
    }
    @Test fun unconfirmedSeekCannotCompleteEpisode() {
        val timeline=ConfirmedPlaybackProgress()
        timeline.observe("audit:a:10",100_000,1_000_000,true,0)
        timeline.interrupt()
        timeline.observe("audit:a:10",995_000,1_000_000,true,3_000_000_000,true)
        val checkpoint=assertNotNull(timeline.drain())
        assertEquals(100_000,checkpoint.positionMs)
        val saved=save(state(),10,checkpoint.positionMs,3000,checkpoint.watchedDeltaMs)
        assertFalse("audit:a#10" in saved.watched)
        assertEquals(100_000,saved.progress.single().positionMs)
    }
    @Test fun watchTimeExcludesSeekPauseAndStallButCountsSlowPlayback() {
        val t=ConfirmedPlaybackProgress()
        t.observe("a",100_000,1_000_000,true,0)
        t.observe("a",101_000,1_000_000,true,2_000_000_000) // 0.5x
        assertEquals(2_000, t.drain()!!.watchedDeltaMs)
        assertEquals(0, t.drain()!!.watchedDeltaMs)
        t.interrupt()
        t.observe("a",140_000,1_000_000,true,3_000_000_000)
        assertEquals(0,t.drain()!!.watchedDeltaMs)
        t.observe("a",140_000,1_000_000,false,4_000_000_000)
        t.observe("a",140_000,1_000_000,false,8_000_000_000)
        assertEquals(0,t.drain()!!.watchedDeltaMs)
        t.reset(); assertNull(t.drain())
        t.observe("b",20_000,100_000,true,9_000_000_000)
        assertEquals("b",t.drain()!!.mediaKey)
    }
    @Test fun timeKeepsCountingAfterNinetyPercentAndPersistsHourly() {
        var s=save(state(),1,905_000,1_000,2_000)
        s=save(s,1,907_000,3_000,2_000)
        assertEquals(4_000,s.watchedMs)
        assertEquals(4_000,s.watchedByDay.values.sum())
        assertEquals(4_000,s.watchedByHour.values.sum())
        val restored=app(s).state.value
        assertEquals(s.watchedByHour,restored.watchedByHour)
    }
    @Test fun writeFailureVisibleAndRetryRecoversLatestState() {
        val dir=Files.createTempDirectory("aniblaze-io-fix-").toFile()
        val target=File(dir,"state.json"); target.mkdir()
        val blocker=File(target,"blocker"); blocker.writeText("test")
        val settings=AppSettings(target)
        settings.setGridColumns(3)
        assertFalse(settings.flush()); assertNotNull(settings.persistenceError.value)
        settings.setGridColumns(6)
        assertTrue(blocker.delete()); assertTrue(target.delete()) // synthetic fixture only
        settings.retrySave()
        assertTrue(settings.flush()); assertNull(settings.persistenceError.value)
        assertEquals(6,AppSettings(target).state.value.gridColumns)
    }
    @Test fun commentsUpsertWithoutDuplicatingOrReordering() {
        val a=CommentAccumulator()
        val original=TitleComment(1,"source","u","u","","old",100,1)
        assertEquals(1,a.add(listOf(original)))
        assertEquals(0,a.add(listOf(original.copy(message="new",votes=9,isSpoiler=true))))
        assertEquals("new",a.snapshot().single().message)
        assertTrue(a.snapshot().single().isSpoiler)
        a.add(listOf(original.copy(id=2)))
        assertEquals(listOf(1L,2L),a.snapshot().map{it.id})
        a.retainIdentities(setOf(commentIdentity(original.copy(id=2))))
        assertEquals(listOf(2L),a.snapshot().map{it.id})
    }
    @Test fun transientOpeningRecoversWithoutRefetchingEnding() = runBlocking {
        var now=0L; var recovered=false; var opCalls=0; var edCalls=0
        val client=OkHttpClient.Builder().addInterceptor{c->
            val shiki=c.request().url.host.contains("shikimori")
            val types=c.request().url.queryParameter("types")
            val op=types=="op"
            if(!shiki) {if(op) opCalls++ else if(types=="ed") edCalls++}
            val code=if(!shiki && op && !recovered) 503 else 200
            // Рекап (третий тип) в этой выдумке отсутствует: честное «не найдено».
            val body=if(shiki) """[{"id":1,"name":"Audit","russian":"Audit","kind":"tv","episodes":12}]"""
                else if(code==503) "{}"
                else if(types=="recap") """{"found":false}"""
                else """{"found":true,"results":[{"skipType":"${if(op) "op" else "ed"}","interval":{"startTime":10,"endTime":100}}]}"""
            Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(code)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        val skip=AniskipTimings(HttpClient(client)){now}
        val first=skip.timings("Audit",2)
        assertTrue(first.retryable); assertNull(first.opening); assertNotNull(first.ending)
        val count=opCalls; recovered=true
        skip.timings("Audit",2); assertEquals(count,opCalls)
        now=31_000
        val fixed=skip.timings("Audit",2)
        assertNotNull(fixed.opening); assertFalse(fixed.retryable); assertEquals(1,edCalls)
        val last=opCalls; skip.timings("Audit",2); assertEquals(last,opCalls)
    }
    @Test fun probeKeepsUnknownButRejectsAllDead() = runBlocking {
        suspend fun probe(unknown: Boolean): ContentResult? {
            val client=OkHttpClient.Builder().addInterceptor{c->
                if(unknown && c.request().url.encodedPath=="/unknown") throw IOException("fixture timeout")
                Response.Builder().request(c.request()).protocol(Protocol.HTTP_1_1).code(404)
                    .message("fixture").body("".toResponseBody()).build()
            }.build()
            val http=HttpClient(client); val skip=AniskipTimings(http); val settings=app()
            val repo=DesktopRepository(emptyList(),emptyList(),settings,aniskip=skip,
                balancer=BalancerSource(http,KodikExtractor(client)),airDates=EpisodeAirDates(http,skip),
                http=http,commentCacheDirectory=settings.dataDirectory.resolve("comments"))
            val input=ContentResult("https://audit.invalid/missing","720p",variants=listOf(
                StreamVariant("720p","https://audit.invalid/missing"),StreamVariant("480p","https://audit.invalid/unknown")))
            val method=DesktopRepository::class.java.getDeclaredMethod("withLiveVariantFirst",
                ContentResult::class.java,Continuation::class.java).apply{isAccessible=true}
            return suspendCoroutineUninterceptedOrReturn{method.invoke(repo,input,it)}
        }
        assertNotNull(probe(true)); assertNull(probe(false))
    }
    @Test fun statsNeverInventHoursOrCountAnOpenedTitleAsStarted() {
        val s=state().copy(history=listOf(card),watched=(1..12).map{"audit:a#$it"}.toSet(),
            watchedMs=0,progress=emptyList())
        assertEquals(0,buildStats(s).hours)
        assertEquals(1,buildStats(s).titles)
        assertEquals(0,buildStats(state().copy(history=listOf(card))).titles)
    }
    @Test fun statsMarathonIsByDateNotLongestSeries() {
        val today=LocalDate.of(2026,9,5)
        val at=today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val s=state().copy(watched=setOf("audit:a#1","audit:a#2","audit:a#3"),
            watchedAt=mapOf("audit:a#1" to at,"audit:a#2" to at-86_400_000,"audit:a#3" to at-172_800_000))
        assertEquals(1,buildStats(s,today).busiestDayEpisodes)
        assertEquals(0,buildStats(s,today).daysActive)
    }
    @Test fun statsUseMeasuredHoursNotUnfinishedEpisodeTimestamps() {
        val s=save(state(),1,400_000,100).copy(watchedByHour=mapOf(20 to 180_000),watchedMs=3_600_000)
        val before=buildStats(s); val after=buildStats(s.copy(progress=emptyList()))
        assertEquals(3,before.hourly[20]); assertEquals(before.hourly,after.hourly)
        assertEquals(20,after.peakHour); assertEquals(1,after.hours)
    }
    @Test fun statsMetadataAndCompletionReactWithoutCollectionSizeChange() {
        val s=state().copy(history=listOf(card.copy(airingStatus=2,episodesTotal=2,episodesAvailable=2)),
            watched=setOf("audit:a#1","audit:a#2"),episodeCounts=mapOf(card.id to 2))
        assertEquals(0,buildStats(s).finishedTitles)
        val enriched=s.copy(history=listOf(s.history.single().copy(airingStatus=1,genres="Drama")))
        assertEquals(1,buildStats(enriched).finishedTitles)
        assertEquals(listOf("Drama" to 1),buildStats(enriched).genres)
    }
    @Test fun statsEnrichmentAttemptsEachMissingTitleAtMostOncePerPass() {
        val s=state().copy(history=(1..30).map{card.copy(id="audit:$it")})
        val tried=mutableSetOf<String>()
        repeat(3){val batch=statsMetadataBatch(s,tried); assertTrue(batch.isNotEmpty());tried.addAll(batch.map{it.id})}
        assertEquals(30,tried.size);assertTrue(statsMetadataBatch(s,tried).isEmpty())
    }
}
