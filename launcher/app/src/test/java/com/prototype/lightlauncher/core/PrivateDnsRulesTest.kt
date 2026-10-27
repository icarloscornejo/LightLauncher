package com.prototype.lightlauncher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PrivateDnsRulesTest {

    @Test
    fun belowAndroid9ThereIsNoPrivateDns() {
        assertEquals(DnsBlockState.UNAVAILABLE, PrivateDnsRules.state(27, "hostname", "dns.adguard-dns.com"))
        assertNull(PrivateDnsRules.customServer(27, "hostname", "dns.example.net"))
    }

    @Test
    fun onlyHostnameModeWithOurResolverIsYes() {
        assertEquals(DnsBlockState.ON, PrivateDnsRules.state(31, "hostname", "dns.adguard-dns.com"))
        assertEquals(DnsBlockState.ON, PrivateDnsRules.state(31, "hostname", " DNS.AdGuard-DNS.com "))
    }

    @Test
    fun anotherServerOrAnotherModeIsNo() {
        assertEquals(DnsBlockState.OFF, PrivateDnsRules.state(31, "hostname", "dns.example.net"))
        assertEquals(DnsBlockState.OFF, PrivateDnsRules.state(31, "opportunistic", "dns.adguard-dns.com"))
        assertEquals(DnsBlockState.OFF, PrivateDnsRules.state(31, "off", null))
        assertEquals(DnsBlockState.OFF, PrivateDnsRules.state(31, null, null))
        assertEquals(DnsBlockState.OFF, PrivateDnsRules.state(31, "hostname", null))
    }

    @Test
    fun theOwnersServerIsNamedBeforeItIsReplaced() {
        assertEquals("dns.example.net", PrivateDnsRules.customServer(31, "hostname", " dns.example.net "))
        assertNull(PrivateDnsRules.customServer(31, "hostname", "dns.adguard-dns.com"))
        assertNull(PrivateDnsRules.customServer(31, "opportunistic", "dns.example.net"))
        assertNull(PrivateDnsRules.customServer(31, "hostname", ""))
    }
}
