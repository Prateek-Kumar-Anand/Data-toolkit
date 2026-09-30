package com.prateek.datatoolkit.core.network

import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URL

/**
 * SSRF guard for every URL this app fetches on the user's (or a scraped page's) behalf.
 * Only plain http(s) to a publicly routable host is allowed - never loopback, link-local,
 * private-use, CGNAT, multicast or reserved ranges, and never URLs with embedded credentials.
 */
object UrlSafety {

    class UnsafeUrlException(message: String) : IOException(message)

    const val MAX_REDIRECTS = 5

    /** Parses and validates [url]; throws [UnsafeUrlException] if it must not be fetched. */
    fun validate(url: String): URL {
        val trimmed = url.trim()
        val parsed = try {
            URL(trimmed)
        } catch (e: Exception) {
            throw UnsafeUrlException("\"$trimmed\" doesn't look like a valid URL")
        }
        val protocol = parsed.protocol.lowercase()
        if (protocol != "http" && protocol != "https") {
            throw UnsafeUrlException("Only http:// and https:// links are allowed")
        }
        if (!parsed.userInfo.isNullOrEmpty()) {
            throw UnsafeUrlException("Links with embedded credentials aren't allowed")
        }
        val host = parsed.host
        if (host.isNullOrBlank()) throw UnsafeUrlException("\"$trimmed\" has no host name")
        if (!isPubliclyRoutable(host)) {
            throw UnsafeUrlException("This address points to a private or local network, which isn't allowed")
        }
        return parsed
    }

    fun isPubliclyRoutable(host: String): Boolean = try {
        val addresses = InetAddress.getAllByName(host)
        addresses.isNotEmpty() && addresses.all { isPublicAddress(it) }
    } catch (_: Exception) {
        false
    }

    private fun isPublicAddress(addr: InetAddress): Boolean {
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress ||
            addr.isMulticastAddress || addr.isAnyLocalAddress
        ) return false
        if (addr is Inet6Address) {
            val first = (addr.address[0].toInt() and 0xFF)
            return (first and 0xFE) != 0xFC // fc00::/7 unique-local
        }
        if (addr is Inet4Address) {
            val b = addr.address.map { it.toInt() and 0xFF }
            val a = b[0]; val c = b[1]
            if (a == 0) return false                                  // 0.0.0.0/8
            if (a == 100 && c in 64..127) return false                // 100.64.0.0/10 CGNAT
            if (a == 192 && c == 0 && b[2] == 0) return false         // 192.0.0.0/24
            if (a == 198 && c in 18..19) return false                 // 198.18.0.0/15 benchmarking
            if (a >= 240) return false                                // reserved + broadcast
        }
        return true
    }

    /** Reads at most [maxBytes] from [input]; throws instead of buffering more. */
    fun readLimited(input: InputStream, maxBytes: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxBytes) throw IOException("Response is larger than the allowed ${maxBytes / (1024 * 1024)} MB")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
