package com.misura.phone

import com.misura.phone.access.ProtectedApps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProtectedAppsTest {
    private val self = "com.misura.phone"
    private fun refused(pkg: String, stored: Boolean?) = ProtectedApps.refusal(pkg, self, stored)

    @Test fun fixedBansSurviveAStoredOn() {
        listOf(ProtectedApps.SHIZUKU, self, "com.google.android.apps.authenticator2", "com.x8bit.bitwarden").forEach { assertNotNull(it, refused(it, true)) }
    }

    @Test fun knownBanksStartBlockedAndTheOwnerCanTurnOneOn() {
        val bank = "com.paypal.android.p2pmobile"
        assertNotNull(refused(bank, null))
        assertNull(refused(bank, true))
        assertNotNull(refused(bank, false))
    }

    @Test fun anyOtherAppIsAllowedUntilTheOwnerBlocksIt() {
        assertNull(refused("com.whatsapp", null))
        assertNotNull(refused("com.whatsapp", false))
    }

    /** Exact package match: a near miss of a catalogued name is an ordinary app. */
    @Test fun matchingIsExact() {
        assertNull(refused("com.paypal.android.p2pmobile.fake", null))
        assertNull(refused("Com.Paypal.Android.P2pmobile", null))
        assertNull(refused("moe.shizuku.privileged", null))
    }

    @Test fun refusalsAreOneLine() {
        listOf(refused(ProtectedApps.SHIZUKU, null), refused("com.paypal.android.p2pmobile", null), refused("com.whatsapp", false))
            .forEach { assertEquals(false, it!!.contains('\n')) }
    }
}

/** Settings can only see installed banks Android lets it query: every catalogued package is declared in the manifest. */
class ProtectedAppsManifestTest {
    @Test fun everyCataloguedBankIsQueryable() {
        val manifest = java.io.File("src/main/AndroidManifest.xml").readText()
        ProtectedApps.FINANCE.forEach { check("<package android:name=\"$it\" />" in manifest) { "missing <queries> entry for $it" } }
    }

    @Test fun moreBankPaymentAndCryptoAppsStartOff() {
        // Gaps found against OpenCyclone's gate/BlockedApps.kt (Apache-2.0), 22 Nov; exact Play package ids.
        listOf("com.citi.citimobile", "com.konylabs.capitalone", "com.americanexpress.android.acctsvcs.us", "com.samsung.android.spay",
            "net.one97.paytm", "com.phonepe.app", "io.metamask", "com.wallet.crypto.trustapp", "org.toshi", "com.eg.android.AlipayGphone",
            "com.ally.MobileBanking").forEach { pkg ->
            org.junit.Assert.assertNotNull(pkg, ProtectedApps.refusal(pkg, "com.misura.phone", stored = null))
            org.junit.Assert.assertNull(pkg, ProtectedApps.refusal(pkg, "com.misura.phone", stored = true))
        }
    }
}
