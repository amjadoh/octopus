package app.octopus.local

import app.octopus.local.providers.*
import app.octopus.local.domain.ProviderId
import app.octopus.local.safety.*
import org.junit.Assert.*
import org.junit.Test

class HungerStationSpinPromoTest {
    private fun recorded(): CatalogFrame {
        val rows = javaClass.classLoader!!.getResourceAsStream("recorded-hs/hs-spin-promo-redacted.tsv")!!
            .bufferedReader(Charsets.UTF_8).use { it.readLines().drop(1) }
        return CatalogFrame(rows.map { row ->
            val c = row.split('\t')
            CatalogNode(c[0].toInt(), c[1].toInt(), c[2], c[3], c[4],
                c[5].toBoolean(), c[6].toBoolean(), c[7].toBoolean(), c[8].toBoolean())
        }, true)
    }

    @Test fun observedPromoResolvesOnlyTheCloseIconIdentifiedByUser() {
        val frame = recorded()
        val target = HungerStationSpinPromo.closeTarget(frame)!!
        assertEquals(25, target.index)
        assertEquals(CatalogScreen.SPIN_PROMO, HungerStationCatalog.screen(frame))
        assertEquals(target, app.octopus.local.automation.CatalogCommands.backTarget(frame))
        val guard = SafetyGuard()
        assertTrue(guard.allowsSpinPromoClose(frame, target, ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true))
        assertFalse(guard.allowsSpinPromoClose(frame, frame.nodes.single { it.index == 22 },
            ProviderId.HUNGERSTATION.packageName, HungerStationCatalog.TESTED_VERSION, true, true))
    }

    @Test fun UnknownOrChangedPromoDoesNotBecomeAnActionableScreen() {
        val frame = recorded()
        assertNull(HungerStationSpinPromo.closeTarget(frame.copy(complete = false)))
        assertNull(HungerStationSpinPromo.closeTarget(frame.copy(nodes = frame.nodes.dropLast(1))))
        assertNull(HungerStationSpinPromo.closeTarget(frame.copy(nodes = frame.nodes.map {
            if (it.index == 23) it.copy(label = "إتمام الدفع") else it
        })))
        assertNull(HungerStationSpinPromo.closeTarget(frame.copy(nodes = frame.nodes.map {
            if (it.index == 25) it.copy(parent = 8) else it
        })))
        assertNull(HungerStationSpinPromo.closeTarget(frame.copy(nodes = frame.nodes.map {
            if (it.index == 9) it.copy(label = "Verify you are human") else it
        })))
    }

    @Test fun ProviderVersionConsentAndFinancialControlChecksRemainMandatory() {
        val frame = recorded()
        val target = HungerStationSpinPromo.closeTarget(frame)!!
        val guard = SafetyGuard()
        assertFalse(guard.allowsSpinPromoClose(frame, target, ProviderId.KEETA.packageName, HungerStationCatalog.TESTED_VERSION, true, true))
        assertFalse(guard.allowsSpinPromoClose(frame, target, ProviderId.HUNGERSTATION.packageName, "unknown", true, true))
        assertFalse(guard.allowsSpinPromoClose(frame, target, ProviderId.HUNGERSTATION.packageName, HungerStationCatalog.TESTED_VERSION, false, true))
        assertFalse(guard.allowsSpinPromoClose(frame, target, ProviderId.HUNGERSTATION.packageName, HungerStationCatalog.TESTED_VERSION, true, false))
        val financial = frame.copy(nodes = frame.nodes.map { if (it.index == 25) it.copy(id = "payment_method") else it })
        assertFalse(guard.allowsSpinPromoClose(financial, financial.nodes.last(), ProviderId.HUNGERSTATION.packageName,
            HungerStationCatalog.TESTED_VERSION, true, true))
    }
}
