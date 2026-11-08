package com.checkmate.workmode

/**
 * DomainPolicy — pure decision helper for AppAutomationService's browser URL check.
 *
 * BUGFIX (post-skip lockdown website bypass): the URL check ran only while
 * WorkModeManager.isEnforcing() and only looked at the "blocked_domains" pref, while the
 * post-skip lockdown works on package names (the escalation watchlist). Outside the
 * scheduled window, with no active session, a lockdown therefore bounced the Instagram /
 * YouTube apps but left instagram.com / youtube.com open in any browser. During a lockdown
 * the check now also runs, and also blocks the web addresses of the default watchlist apps.
 *
 * Kept free of Android and prefs access so it can be unit tested on the JVM.
 */
object DomainPolicy {

    /** Web addresses of the apps in WorkModeManager's default escalation watchlist. */
    val LOCKDOWN_DOMAINS: Set<String> = setOf(
        "instagram.com",
        "youtube.com",
        "youtu.be",
        "tiktok.com",
        "snapchat.com",
        "facebook.com",
        "fb.com",
        "twitter.com",
        "x.com",
        "reddit.com"
    )

    /** True when the browser URL check must run: Work Mode enforcement OR a post-skip lockdown. */
    fun urlCheckActive(enforcing: Boolean, inLockdown: Boolean): Boolean = enforcing || inLockdown

    /**
     * Domains treated as blocked: the guardian's blocked list while enforcing, and that list
     * plus [LOCKDOWN_DOMAINS] while a lockdown is active. Empty when neither applies.
     */
    fun blockedDomains(enforcing: Boolean, inLockdown: Boolean, blocked: Set<String>): Set<String> {
        val result = mutableSetOf<String>()
        if (enforcing || inLockdown) result += blocked
        if (inLockdown) result += LOCKDOWN_DOMAINS
        return result
    }

    /** The blocked domain [hostname] equals or is a subdomain of, or null. */
    fun match(hostname: String, domains: Set<String>): String? =
        domains.firstOrNull { d -> hostname == d || hostname.endsWith(".$d") }
}
