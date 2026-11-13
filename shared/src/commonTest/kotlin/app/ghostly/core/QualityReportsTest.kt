package app.ghostly.core

import app.ghostly.core.telemetry.QualityBatch
import app.ghostly.core.telemetry.QualityEvent
import app.ghostly.core.telemetry.QualityReports
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QualityReportsTest {

    @Test
    fun batchGoesOutWhenFullOrOld() {
        val t0 = 1_000_000L
        assertFalse(QualityReports.due(0, t0, t0 + QualityReports.EVERY_MS * 5), "nothing to send")
        assertFalse(QualityReports.due(3, t0, t0 + 1_000), "a few fresh ones wait")
        assertTrue(QualityReports.due(QualityReports.BATCH, t0, t0 + 1_000), "a full batch goes at once")
        assertTrue(QualityReports.due(1, t0, t0 + QualityReports.EVERY_MS), "an old one goes")
    }

    @Test
    fun offlineKeepsOnlyTheNewest() {
        val list = (1..10).toMutableList()
        QualityReports.trim(list, 4)
        assertEquals(listOf(7, 8, 9, 10), list)
        QualityReports.trim(list, 10)
        assertEquals(4, list.size)
    }

    @Test
    fun batchCarriesNothingThatIdentifiesTheDevice() {
        val batch = QualityBatch(app = "0.3.56", os = "Android", core = "XRAY", events = listOf(
            QualityEvent("ping", "🇳🇱 Hysteria2", "hysteria2", ok = true, ms = 84, net = "CELLULAR", at = 1_794_560_000, m = "PROXY_GET"),
        ))
        val json = JsonX.parseToJsonElement(JsonX.encodeToString(QualityBatch.serializer(), batch)).jsonObject
        assertEquals(setOf("v", "app", "os", "core", "events"), json.keys)
        val event = json["events"].toString()
        assertTrue("\"net\":\"CELLULAR\"" in event)
        assertFalse("hwid" in json.toString() || "sub" in json.keys)
    }
}
