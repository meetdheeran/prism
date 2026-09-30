package com.meetdheeran.prism.agent

/**
 * What the agent may never touch, and which taps need the user's OK first. These checks run in code
 * on every step — they don't depend on the model behaving.
 */
object AgentPolicy {

    /** Banking, payments, wallets and password managers, by package. Name patterns catch the rest. */
    private val BLOCKED_PACKAGES = setOf(
        // Payments / wallets
        "com.google.android.apps.walletnfcrel", "com.google.android.apps.nbu.paisa.user", "com.samsung.android.spay",
        "com.paypal.android.p2pmobile", "com.venmo", "com.squareup.cash", "com.revolut.revolut", "com.transferwise.android",
        "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp", "in.amazon.mShop.android.shopping.pay",
        "com.careem.acma.wallet", "ae.payby.android",
        // UAE banks
        "com.emiratesnbd.android", "com.enbd.mobilebanking", "com.adcb.bank", "com.adcbhayyak", "com.fab.personalbanking",
        "com.mashreq.mobile", "com.mashreqneo", "ae.rakbank.mobile", "com.dib.app", "com.adib.mobile", "com.cbd.mobile",
        "com.hsbc.hsbcuae", "ae.wio.retail", "com.liv.bank",
        // India / global banks commonly installed
        "com.sbi.lotusintouch", "com.snapwork.hdfc", "com.csam.icici.bank.imobile", "com.axis.mobile", "com.msf.kbank.mobile",
        "com.chase.sig.android", "com.wf.wellsfargomobile", "com.infonow.bofa", "com.barclays.android.barclaysmobilebanking",
        // Password managers / authenticators
        "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.agilebits.onepassword", "com.onepassword.android",
        "com.dashlane", "keepass2android.keepass2android", "com.kunzisoft.keepass.free",
        "com.google.android.apps.authenticator2", "com.azure.authenticator", "com.authy.authy",
    )

    private val BLOCKED_NAME = Regex(
        "\\b(bank|banking|wallet|upi|pay\\w*|paisa|password|passwords|authenticator|vault|crypto|binance|coinbase|trading|broker)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val BLOCKED_PACKAGE_PART = Regex("(bank|wallet|upi|paypal|paytm|phonepe|passw|authenticator|keepass|bitwarden|lastpass|binance|coinbase)", RegexOption.IGNORE_CASE)

    /** Null when the app may be used; otherwise the reason to show the user. */
    fun blockReason(packageName: String, appLabel: String, ownPackage: String): String? = when {
        packageName.isEmpty() -> null
        packageName == ownPackage -> null // handled by the runner (it steps out of Prism first)
        packageName in BLOCKED_PACKAGES || BLOCKED_PACKAGE_PART.containsMatchIn(packageName) || BLOCKED_NAME.containsMatchIn(appLabel) ->
            "I don't operate banking, payment or password apps ($appLabel). You'll need to do that part yourself."
        else -> null
    }

    /**
     * Words on a button that mean "this can't be taken back". A tap on anything like this waits for
     * the user's OK, whatever the model said.
     */
    private val IRREVERSIBLE = Regex(
        // "Reply"/"Forward" only open a composer, so they're left out; the composer's Send is caught.
        "\\b(send|post|publish|share|tweet|upload|submit|confirm|delete|remove|erase|clear|reset|" +
            "pay|buy|purchase|order|checkout|place order|subscribe|transfer|donate|book|call|dial|video call|" +
            "allow|grant|accept|agree|install|uninstall|sign in|log in|login|sign out|log out|block|report|unfriend|unfollow|leave|" +
            "turn off|disable|factory|format|archive)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val IRREVERSIBLE_ID = Regex("(send|post|submit|confirm|delete|pay|buy|checkout|call|share|publish)", RegexOption.IGNORE_CASE)

    fun looksIrreversible(e: UiElement?): Boolean = e != null && looksIrreversible(e.label, e.viewId)

    fun looksIrreversible(label: String, viewId: String?): Boolean =
        IRREVERSIBLE.containsMatchIn(label) || (viewId != null && IRREVERSIBLE_ID.containsMatchIn(viewId))

    /** Pressing enter in a chat box usually sends. Only search boxes are safe to submit silently. */
    fun submitNeedsConfirm(e: UiElement?): Boolean = submitNeedsConfirm(e?.label.orEmpty(), e?.viewId)

    fun submitNeedsConfirm(label: String, viewId: String?): Boolean {
        val l = (label + " " + viewId.orEmpty()).lowercase()
        return !(l.contains("search") || l.contains("find") || l.contains("url") || l.contains("address"))
    }
}
