package com.misura.phone.access

import android.content.Context
import android.content.SharedPreferences
import com.misura.phone.BuildConfig

/**
 * Which apps the agent may operate (open, read, type into, reply through). Owner-operated launches (app sheet, setup, links on cards)
 * never pass through here. Exact package names only: Android has no finance category, so banking/payment detection is a curated list.
 * Scope (SYSTEM §5 M1): checked at the agent's entry points; an allowed app that redirects into a protected one mid-task is not caught.
 */
object ProtectedApps {
    const val SHIZUKU = "moe.shizuku.privileged.api"

    // ponytail: curated exact packages; a missing app is simply not protected by default (the owner can block it in settings).
    val AUTHENTICATORS = setOf(
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy", "com.duosecurity.duomobile",
        "org.fedorahosted.freeotp", "org.liberty.android.freeotpplus", "com.beemdevelopment.aegis", "com.twofasapp",
        "com.yubico.yubioath", "com.lastpass.authenticator", "com.okta.android.auth", "io.ente.auth", "com.salesforce.authenticator",
    )
    val PASSWORD_MANAGERS = setOf(
        "com.x8bit.bitwarden", "com.onepassword.android", "com.agilebits.onepassword", "com.lastpass.lpandroid", "com.dashlane",
        "keepass2android.keepass2android", "keepass2android.keepass2android_nonet", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
        "com.callpod.android_apps.keeper", "io.enpass.app", "proton.android.pass", "com.nordpass.android.app.password.manager",
    )
    /** Banking and payment apps: blocked until the owner switches one on. */
    val FINANCE = setOf(
        "com.paypal.android.p2pmobile", "com.google.android.apps.walletnfcrel", "com.revolut.revolut", "com.transferwise.android",
        "com.klarna.android", "de.number26.android", "de.dkb.portalapp", "com.starfinanz.smob.android.sfinanzstatus",
        "de.commerzbanking.mobil", "com.db.pwcc.dbmobile", "de.ingdiba.bankingapp", "de.comdirect.android", "de.consorsbank",
        "de.postbank.finanzassistent", "de.traderepublic.app", "co.uk.getmondo", "com.starlingbank.android", "com.barclays.android.barclaysmobilebanking",
        "com.chase.sig.android", "com.wf.wellsfargomobile", "com.infonow.bofa", "com.venmo", "com.squareup.cash", "com.coinbase.android",
        "com.binance.dev", "com.bunq.android", "com.ing.mobile", "nl.rabomobiel", "com.abnamro.nl.mobile.payments",
        // Added 22 Nov from a comparison with OpenCyclone's gate/BlockedApps.kt (Apache-2.0).
        "com.citi.citimobile", "com.konylabs.capitalone", "com.usbank.mobilebanking", "com.pnc.ecommerce.mobile", "com.tdbank",
        "com.ally.MobileBanking", "com.bbt.myfi", "com.discoverfinancial.mobile", "com.americanexpress.android.acctsvcs.us", "com.samsung.android.spay",
        "com.google.android.apps.nbu.paisa.user", "net.one97.paytm", "com.phonepe.app", "com.eg.android.AlipayGphone", "org.toshi",
        "io.metamask", "com.wallet.crypto.trustapp", "piuk.blockchain.android",
    )

    /** null = the agent may operate [pkg]. [stored]: the owner's switch (absent = the category default). Pure, for tests. */
    fun refusal(pkg: String, self: String, stored: Boolean?): String? = when {
        pkg == SHIZUKU -> "Misura never operates Shizuku"
        pkg == self -> "Misura never operates itself"
        pkg in AUTHENTICATORS -> "Misura never operates authenticator apps"
        pkg in PASSWORD_MANAGERS -> "Misura never operates password managers"
        stored == false -> "the owner blocked this app for Misura (Misura settings)"
        stored == true -> null
        pkg in FINANCE -> "banking and payment apps are off for Misura until the owner turns one on in Misura settings"
        else -> null
    }

    fun fixed(pkg: String, self: String) = pkg == SHIZUKU || pkg == self || pkg in AUTHENTICATORS || pkg in PASSWORD_MANAGERS
}

/** The owner's choices on top of [ProtectedApps]. Process-wide; works before [init] (bans and defaults still apply). */
object AppAccess {
    @Volatile private var prefs: SharedPreferences? = null
    @Volatile private var self: String = BuildConfig.APPLICATION_ID

    fun init(ctx: Context) {
        if (prefs != null) return
        prefs = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        self = ctx.packageName
    }

    /** Unreadable or wrongly typed entries read as absent: the category default, never "block everything". */
    private fun stored(pkg: String): Boolean? = runCatching { prefs?.takeIf { it.contains(pkg) }?.getBoolean(pkg, false) }.getOrNull()

    /** null = allowed. One plain line otherwise, for the step and the model. */
    fun refusal(pkg: String?): String? = pkg?.let { ProtectedApps.refusal(it, self, stored(it)) }

    fun allows(pkg: String?) = pkg != null && refusal(pkg) == null

    fun isFixed(pkg: String) = ProtectedApps.fixed(pkg, self)

    /** true/false = the owner's switch; null = back to the default. Fixed bans ignore it. */
    fun set(pkg: String, allowed: Boolean?) {
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"))) { "invalid package" }
        val p = checkNotNull(prefs) { "app access not ready" }
        val edit = p.edit()
        if (allowed == null) edit.remove(pkg) else edit.putBoolean(pkg, allowed)
        check(edit.commit()) { "app access not saved" }
    }

    /** Packages the owner set either way. */
    fun choices(): Map<String, Boolean> = runCatching { prefs?.all.orEmpty().mapNotNull { (k, v) -> (v as? Boolean)?.let { k to it } }.toMap() }.getOrDefault(emptyMap())

    const val PREFS = "app_access"
}
