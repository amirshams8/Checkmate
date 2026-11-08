package com.checkmate.workmode

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DomainPolicyTest {

    private val guardian = setOf("example-game.com")

    @Test
    fun urlCheckRunsDuringLockdownOutsideEnforcement() {
        // The bug: lockdown active, not enforcing -> no URL check at all.
        assertTrue(DomainPolicy.urlCheckActive(enforcing = false, inLockdown = true))
        assertTrue(DomainPolicy.urlCheckActive(enforcing = true, inLockdown = false))
        assertFalse(DomainPolicy.urlCheckActive(enforcing = false, inLockdown = false))
    }

    @Test
    fun lockdownBlocksWatchlistWebsites() {
        val domains = DomainPolicy.blockedDomains(false, true, guardian)
        assertEquals("youtube.com", DomainPolicy.match("youtube.com", domains))
        assertEquals("instagram.com", DomainPolicy.match("www.instagram.com", domains))
        assertEquals("example-game.com", DomainPolicy.match("example-game.com", domains))
    }

    @Test
    fun enforcementAloneUsesOnlyGuardianList() {
        val domains = DomainPolicy.blockedDomains(true, false, guardian)
        assertEquals(guardian, domains)
        assertNull(DomainPolicy.match("youtube.com", domains))
    }

    @Test
    fun neitherActiveBlocksNothing() {
        assertTrue(DomainPolicy.blockedDomains(false, false, guardian).isEmpty())
    }

    @Test
    fun matchDoesNotHitLookalikeHosts() {
        val domains = DomainPolicy.blockedDomains(false, true, emptySet())
        assertNull(DomainPolicy.match("notyoutube.com", domains))
        assertNull(DomainPolicy.match("youtube.com.evil.example", domains))
        assertEquals("x.com", DomainPolicy.match("m.x.com", domains))
        assertNull(DomainPolicy.match("netflix.com", domains))
    }
}
