package com.vboard.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DownloadPolicyTest {

    private val bytes = 127_887_156L

    // --------------------------------------------------- every state x override

    @Test
    fun `unmetered without consent starts now and stays pinned to unmetered`() {
        val decision = DownloadPolicy.decide(NetworkState.UNMETERED, meteredConsent = false, bytes = bytes)
        assertEquals(DownloadDecision.Enqueue(allowMetered = false, startsImmediately = true), decision)
    }

    @Test
    fun `unmetered with consent starts now and may continue on a metered link`() {
        val decision = DownloadPolicy.decide(NetworkState.UNMETERED, meteredConsent = true, bytes = bytes)
        assertEquals(DownloadDecision.Enqueue(allowMetered = true, startsImmediately = true), decision)
    }

    @Test
    fun `metered without consent demands a confirmation that states the real size`() {
        val decision = DownloadPolicy.decide(NetworkState.METERED, meteredConsent = false, bytes = bytes)
        assertEquals(DownloadDecision.ConfirmMetered(bytes), decision)
        assertEquals("128 MB", (decision as DownloadDecision.ConfirmMetered).sizeText)
    }

    @Test
    fun `metered with consent starts now`() {
        val decision = DownloadPolicy.decide(NetworkState.METERED, meteredConsent = true, bytes = bytes)
        assertEquals(DownloadDecision.Enqueue(allowMetered = true, startsImmediately = true), decision)
    }

    @Test
    fun `offline without consent queues for wifi rather than dropping the request`() {
        val decision = DownloadPolicy.decide(NetworkState.OFFLINE, meteredConsent = false, bytes = bytes)
        assertEquals(DownloadDecision.Enqueue(allowMetered = false, startsImmediately = false), decision)
    }

    @Test
    fun `offline with consent queues unconstrained`() {
        val decision = DownloadPolicy.decide(NetworkState.OFFLINE, meteredConsent = true, bytes = bytes)
        assertEquals(DownloadDecision.Enqueue(allowMetered = true, startsImmediately = false), decision)
    }

    // ------------------------------------------------------------- invariants

    @Test
    fun `cellular data is never spent without explicit consent, in any state`() {
        for (network in NetworkState.entries) {
            val decision = DownloadPolicy.decide(network, meteredConsent = false, bytes = bytes)
            val allowsMetered = (decision as? DownloadDecision.Enqueue)?.allowMetered ?: false
            assertFalse(allowsMetered, "$network must not allow metered traffic without consent")
        }
    }

    @Test
    fun `consent is only ever asked for on a metered link`() {
        for (network in NetworkState.entries) {
            for (consent in listOf(true, false)) {
                val decision = DownloadPolicy.decide(network, consent, bytes)
                val asks = decision is DownloadDecision.ConfirmMetered
                assertEquals(
                    network == NetworkState.METERED && !consent,
                    asks,
                    "$network/consent=$consent",
                )
            }
        }
    }

    @Test
    fun `only an offline device is told the download has to wait`() {
        for (consent in listOf(true, false)) {
            val offline = DownloadPolicy.decide(NetworkState.OFFLINE, consent, bytes)
            assertIs<DownloadDecision.Enqueue>(offline)
            assertFalse(offline.startsImmediately)

            val online = DownloadPolicy.decide(NetworkState.UNMETERED, consent, bytes)
            assertIs<DownloadDecision.Enqueue>(online)
            assertTrue(online.startsImmediately)
        }
    }

    @Test
    fun `the confirmation quotes the size it was handed, not a catalog constant`() {
        val decision = DownloadPolicy.decide(NetworkState.METERED, meteredConsent = false, bytes = 2_400_000_000L)
        assertEquals("2.4 GB", (decision as DownloadDecision.ConfirmMetered).sizeText)
    }
}
