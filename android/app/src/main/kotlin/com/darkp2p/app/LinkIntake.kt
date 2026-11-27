package com.darkp2p.app

import com.darkp2p.protocol.RoomLink
import com.darkp2p.transport.PendingClaim

/** What a link, a scanned code or pasted text asks the app to do. */
sealed interface Intake {
    /** Join [link]. [trusted] is false for any server but the default one: the user is asked first. */
    data class Room(val link: RoomLink, val trusted: Boolean) : Intake

    /** The payment provider sent the buyer back: `https://<server>/?NP_id=<payment id>`. */
    data class PaymentReturn(val paymentId: String) : Intake

    data object Nothing : Intake
}

object LinkIntake {
    private val PAYMENT = Regex("[?&]NP_id=([^&#]*)")

    /**
     * Reads a share link (`https://host/#room:key`), or just its `room:key` part,
     * which then means the default server. Everything is validated by the same
     * rules as the web client; anything else is [Intake.Nothing].
     *
     * A link can carry a payment return as well as a room. The payment ID is
     * only ever honoured for the default server's own return address.
     */
    fun parse(text: String?, defaultOrigin: String = DEFAULT_ORIGIN): List<Intake> {
        val input = text?.trim().orEmpty()
        if (input.isEmpty() || input.length > 2048) return emptyList()
        val out = ArrayList<Intake>()

        val link = RoomLink.parse(input)
            ?: input.takeIf { "://" !in it }?.let { RoomLink.parseFragment(it.removePrefix("#")) }?.let { (id, key) -> RoomLink(defaultOrigin, id, key) }
        if (link != null) out += Intake.Room(link, trusted = link.origin == defaultOrigin)

        val origin = RoomLink.normalizeOrigin(input.substringBefore('#').substringBefore('?'))
        val paymentId = PAYMENT.find(input.substringBefore('#'))?.groupValues?.get(1)
        if (origin == defaultOrigin && paymentId != null && PendingClaim.isNowPaymentsId(paymentId)) out += Intake.PaymentReturn(paymentId)
        return out
    }
}
