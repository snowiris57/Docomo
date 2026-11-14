package io.github.snowiris57.reconnect

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceLogTest {
    private val t0 = 1_700_000_000_000L

    private fun place(at: Long, lat: Double, lon: Double, kind: PlaceKind, rsrp: Int? = -115, acc: Float = 30f) =
        Place(at, at, lat, lon, acc, kind, rsrp, "LTE", 1, if (kind == PlaceKind.BAD) 1 else 0, null)

    @After fun tearDown() { PlaceLog.places.value = emptyList() }

    @Test fun classify_bad_only_skips_good_and_paused() {
        assertNull(PlaceLog.classify(PlaceMode.OFF, Health.BAD, -120, -110))
        assertNull(PlaceLog.classify(PlaceMode.BAD_ONLY, Health.OK, -100, -110))
        assertNull(PlaceLog.classify(PlaceMode.BAD_ONLY, Health.PAUSED, -125, -110))
        assertNull(PlaceLog.classify(PlaceMode.ALL, Health.RECOVERING, -125, -110))
        assertEquals(PlaceKind.WEAK, PlaceLog.classify(PlaceMode.BAD_ONLY, Health.OK, -111, -110))
        assertEquals(PlaceKind.UNSTABLE, PlaceLog.classify(PlaceMode.BAD_ONLY, Health.UNSTABLE, -90, -110))
        assertEquals(PlaceKind.BAD, PlaceLog.classify(PlaceMode.BAD_ONLY, Health.BAD, null, -110))
        assertEquals(PlaceKind.GOOD, PlaceLog.classify(PlaceMode.ALL, Health.OK, -100, -110))
        // 電波の強さが取れないときは「弱い」とはしない
        assertEquals(PlaceKind.GOOD, PlaceLog.classify(PlaceMode.ALL, Health.OK, null, -110))
    }

    @Test fun same_spot_is_merged_and_keeps_the_worst() {
        PlaceLog.add(place(t0, 36.5613, 136.6562, PlaceKind.WEAK, -112))
        PlaceLog.add(place(t0 + 15_000, 36.56132, 136.65622, PlaceKind.BAD, -121))
        PlaceLog.add(place(t0 + 30_000, 36.56131, 136.65621, PlaceKind.WEAK, -116))
        val list = PlaceLog.places.value
        assertEquals(1, list.size)
        assertEquals(PlaceKind.BAD, list[0].kind)
        assertEquals(-121, list[0].minRsrp)
        assertEquals(3, list[0].count)
        assertEquals(1, list[0].fails)
        assertEquals(t0, list[0].start)
        assertEquals(t0 + 30_000, list[0].end)
    }

    @Test fun far_or_late_or_different_trend_is_a_new_record() {
        PlaceLog.add(place(t0, 36.5613, 136.6562, PlaceKind.WEAK))
        PlaceLog.add(place(t0 + 15_000, 36.5633, 136.6562, PlaceKind.WEAK)) // 約 220m 先
        PlaceLog.add(place(t0 + 30_000, 36.5633, 136.6562, PlaceKind.GOOD)) // 同じ場所だが良好
        PlaceLog.add(place(t0 + 30_000 + 11 * 60_000, 36.5633, 136.6562, PlaceKind.GOOD)) // 11 分後
        assertEquals(4, PlaceLog.places.value.size)
    }

    @Test fun extend_adds_to_the_last_record_without_a_new_location() {
        PlaceLog.add(place(t0, 36.5613, 136.6562, PlaceKind.WEAK, -112))
        assertEquals(true, PlaceLog.extend(PlaceKind.BAD, -119, null, t0 + 15_000))
        val p = PlaceLog.places.value.single()
        assertEquals(PlaceKind.BAD, p.kind)
        assertEquals(-119, p.minRsrp)
        assertEquals(2, p.count)
        assertEquals(1, p.fails)
        assertEquals(36.5613, p.lat, 0.0)
        // 傾向が違う（良好）・間が空きすぎ のときは足さない
        assertEquals(false, PlaceLog.extend(PlaceKind.GOOD, -90, 40, t0 + 30_000))
        assertEquals(false, PlaceLog.extend(PlaceKind.WEAK, -115, 40, t0 + 15_000 + 11 * 60_000))
        assertEquals(1, PlaceLog.places.value.size)
    }

    @Test fun hotspots_need_two_separate_bad_visits() {
        val day = 24 * 60 * 60 * 1000L
        val places = listOf(
            place(t0, 36.5613, 136.6562, PlaceKind.BAD),
            place(t0 + day, 36.5612, 136.6563, PlaceKind.WEAK),
            place(t0 + day, 36.6000, 136.7000, PlaceKind.BAD),
            place(t0 + day, 36.6500, 136.7500, PlaceKind.GOOD),
            place(t0 + 2 * day, 36.6500, 136.7500, PlaceKind.GOOD),
        )
        val hot = PlaceLog.hotspots(places)
        assertEquals(1, hot.size)
        assertEquals(2, hot[0].second)
        assertEquals(t0 + day, hot[0].first.end)
    }

    @Test fun distance_is_roughly_right() {
        // 緯度 0.001 度 ≒ 111m
        assertEquals(111.3, PlaceLog.distanceM(36.0, 136.0, 36.001, 136.0), 0.5)
    }
}
