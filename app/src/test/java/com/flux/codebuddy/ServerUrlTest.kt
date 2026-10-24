package com.flux.codebuddy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerUrlTest {

    private fun ok(input: String): ServerTarget =
        (ServerUrl.parse(input) as UrlResult.Ok).target

    private fun reason(input: String): UrlError =
        (ServerUrl.parse(input) as UrlResult.Error).reason

    @Test
    fun barePrivateAddressDefaultsToHttpAndRootPath() {
        val target = ok("192.168.1.100:7890")
        assertEquals("http://192.168.1.100:7890/", target.startUrl)
        assertEquals("http://192.168.1.100:7890/", target.probeUrl)
        assertEquals("http://192.168.1.100:7890", target.origin)
        assertEquals("192.168.1.100:7890", target.displayHost)
        assertEquals(7890, target.port)
        assertTrue(target.isPrivateHost)
    }

    @Test
    fun preservesQueryAndFragmentButProbeDropsThem() {
        val target = ok(
            "http://192.168.1.100:7890/?hostId=local&hostEpoch=0&folder=%2Fhome%2Fflux#/chat",
        )
        assertEquals(
            "http://192.168.1.100:7890/?hostId=local&hostEpoch=0&folder=%2Fhome%2Fflux#/chat",
            target.startUrl,
        )
        assertEquals("http://192.168.1.100:7890/", target.probeUrl)
        assertEquals("http://192.168.1.100:7890", target.origin)
    }

    @Test
    fun httpsPublicHostIsAllowed() {
        val target = ok("https://example.com")
        assertEquals("https://example.com/", target.startUrl)
        assertEquals("https://example.com", target.origin)
        assertEquals("example.com", target.displayHost)
        assertEquals(443, target.port)
        assertFalse(target.isPrivateHost)
    }

    @Test
    fun httpPublicHostIsRejected() {
        assertEquals(UrlError.PUBLIC_HTTP, reason("http://example.com"))
        assertEquals(UrlError.PUBLIC_HTTP, reason("http://8.8.8.8:7890"))
        assertEquals(UrlError.PUBLIC_HTTP, reason("http://172.32.0.1:7890"))
        assertEquals(UrlError.PUBLIC_HTTP, reason("http://100.128.0.1:7890"))
        assertEquals(UrlError.PUBLIC_HTTP, reason("http://[2001:db8::1]:7890"))
    }

    @Test
    fun privateIpv4RangesAreAccepted() {
        listOf(
            "http://10.0.0.1:7890",
            "http://172.16.0.1:7890",
            "http://172.31.255.255:7890",
            "http://192.168.0.1:7890",
            "http://127.0.0.1:7890",
            "http://169.254.1.1:7890",
            "http://100.64.0.1:7890",
        ).forEach { url ->
            assertTrue("expected private: $url", ok(url).isPrivateHost)
        }
    }

    @Test
    fun localNamesAreAccepted() {
        listOf(
            "http://localhost:7890",
            "http://mybox.local:7890",
            "http://mybox.lan:7890",
            "http://mybox.home:7890",
            "http://mybox.internal:7890",
            "http://mybox:7890",
        ).forEach { url ->
            assertTrue("expected private: $url", ok(url).isPrivateHost)
        }
    }

    @Test
    fun credentialsInUrlAreRejected() {
        assertEquals(
            UrlError.CREDENTIALS,
            reason("http://user:pass@192.168.1.10:7890"),
        )
    }

    @Test
    fun passwordQueryParameterIsRejected() {
        assertEquals(
            UrlError.PASSWORD_QUERY,
            reason("http://192.168.1.10:7890/?password=secret"),
        )
        assertEquals(
            UrlError.PASSWORD_QUERY,
            reason("http://192.168.1.10:7890/?passwd=secret"),
        )
        assertEquals(
            UrlError.PASSWORD_QUERY,
            reason("http://192.168.1.10:7890/?Password=secret"),
        )
        assertEquals(
            UrlError.PASSWORD_QUERY,
            reason("http://192.168.1.10:7890/?a=1&password=secret"),
        )
        assertEquals(
            UrlError.PASSWORD_QUERY,
            reason("http://192.168.1.10:7890/?pass%77ord=secret"),
        )
    }

    @Test
    fun invalidPortsAreRejected() {
        assertEquals(UrlError.BAD_PORT, reason("http://192.168.1.10:0"))
        assertEquals(UrlError.BAD_PORT, reason("http://192.168.1.10:99999"))
    }

    @Test
    fun malformedInputIsRejected() {
        assertEquals(UrlError.EMPTY, reason("   "))
        assertEquals(UrlError.INVALID, reason("not a url"))
        assertEquals(UrlError.INVALID, reason("ftp://192.168.1.10:7890"))
        assertEquals(UrlError.INVALID, reason("javascript:alert(1)"))
        assertEquals(UrlError.INVALID, reason("http://"))
    }

    @Test
    fun ipv6LoopbackIsAccepted() {
        val target = ok("http://[::1]:7890")
        assertEquals("::1", target.host)
        assertEquals("http://[::1]:7890", target.origin)
        assertEquals("[::1]:7890", target.displayHost)
        assertTrue(target.isPrivateHost)
    }

    @Test
    fun ipv6UlaAndLinkLocalArePrivate() {
        assertTrue(ok("http://[fc00::1]:7890").isPrivateHost)
        assertTrue(ok("http://[fd12:3456::1]:7890").isPrivateHost)
        assertTrue(ok("http://[fe80::1]:7890").isPrivateHost)
    }

    @Test
    fun explicitNonDefaultPortIsKeptInOrigin() {
        val target = ok("https://example.com:8443/path")
        assertEquals("https://example.com:8443/path", target.startUrl)
        assertEquals("https://example.com:8443", target.origin)
        assertEquals("example.com:8443", target.displayHost)
    }

    @Test
    fun encodedPathIsPreserved() {
        val target = ok("http://192.168.1.10:7890/a%20b")
        assertEquals("http://192.168.1.10:7890/a%20b", target.startUrl)
        assertEquals("http://192.168.1.10:7890/a%20b", target.probeUrl)
    }

    @Test
    fun schemeIsNormalized() {
        val target = ok("HTTP://192.168.1.10:7890")
        assertEquals("http", target.scheme)
        assertEquals("http://192.168.1.10:7890/", target.startUrl)
    }
}
