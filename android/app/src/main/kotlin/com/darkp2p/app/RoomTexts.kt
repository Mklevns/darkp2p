package com.darkp2p.app

import com.darkp2p.protocol.FatalReason
import com.darkp2p.protocol.IpExposure
import com.darkp2p.protocol.Notice
import com.darkp2p.protocol.RoomEvent
import com.darkp2p.protocol.RoomState
import com.darkp2p.protocol.Transport

/** Which words describe a room. Kept apart from the screens so every state provably has some. */
object RoomTexts {
    fun state(state: RoomState): Int = when (state) {
        is RoomState.Connecting -> if (state.reconnecting) R.string.state_reconnecting else R.string.state_connecting
        RoomState.WaitingForPeer -> R.string.state_waiting
        is RoomState.Negotiating -> R.string.state_negotiating
        is RoomState.Verifying -> if (state.unconfirmed) R.string.state_unconfirmed else R.string.state_verifying
        is RoomState.Ready -> when (state.transport) {
            Transport.DIRECT -> R.string.state_ready_direct
            Transport.TURN -> R.string.state_ready_turn
            Transport.WS_RELAY -> R.string.state_ready_relay
        }
        is RoomState.Interrupted -> if (state.canReconnect) R.string.state_interrupted_reconnect else R.string.state_interrupted
        RoomState.RelayBusy -> R.string.state_relay_busy
        RoomState.RelayIdle -> R.string.state_relay_idle
        is RoomState.RelayExhausted -> if (state.canTryDirect) R.string.state_relay_exhausted_direct else R.string.state_relay_exhausted
        RoomState.KeyMismatch -> R.string.state_key_mismatch
        RoomState.PeerOutdated -> R.string.state_peer_outdated
        RoomState.RoomFull -> R.string.state_room_full
        is RoomState.Fatal -> if (state.reason == FatalReason.ORIGIN_REJECTED) R.string.state_fatal_origin else R.string.state_fatal_room
    }

    /** States in which something is wrong and the user should not send. */
    fun isProblem(state: RoomState): Boolean = when (state) {
        RoomState.KeyMismatch, RoomState.PeerOutdated, RoomState.RoomFull, is RoomState.Fatal, is RoomState.RelayExhausted -> true
        is RoomState.Interrupted -> state.canReconnect
        is RoomState.Verifying -> state.unconfirmed
        else -> false
    }

    fun ip(ip: IpExposure): Int = when (ip) {
        IpExposure.EXPOSED -> R.string.ip_exposed
        IpExposure.MASKED -> R.string.ip_masked
        IpExposure.UNVERIFIED -> R.string.ip_unverified
    }

    /**
     * The line a notice adds to the room's activity list, as (text, takes the file
     * name), or null for the many notices the state line already covers.
     */
    fun notice(event: RoomEvent): Pair<Int, Boolean>? = when (event.notice) {
        Notice.DELIVERED -> R.string.notice_delivered to true
        Notice.SENT_UNCONFIRMED -> R.string.notice_sent_unconfirmed to true
        Notice.NOT_SENT_DECLINED -> R.string.notice_declined to true
        Notice.NOT_SENT_PEER_BUSY -> R.string.notice_peer_busy to true
        Notice.NOT_SENT_TOO_LARGE_FOR_PEER -> R.string.notice_too_large_for_peer to true
        Notice.DOES_NOT_FIT_RELAY, Notice.FILE_TOO_LARGE -> R.string.notice_does_not_fit to true
        Notice.SEND_ABORTED, Notice.SEND_CANCELLED -> R.string.notice_send_aborted to true
        Notice.SEND_INTERRUPTED -> R.string.notice_send_interrupted to true
        Notice.RECEIVE_ABORTED -> R.string.notice_receive_aborted to true
        Notice.RECEIVE_INTERRUPTED -> R.string.notice_receive_interrupted to true
        Notice.SENDER_ABORTED -> R.string.notice_sender_aborted to false
        Notice.RECEIVER_ABORTED -> R.string.notice_receiver_aborted to false
        Notice.KEY_VERIFIED -> R.string.notice_key_verified to false
        Notice.PEER_LEFT -> R.string.notice_peer_left to false
        Notice.SESSION_RENEWED -> R.string.notice_session_renewed to false
        else -> null
    }

    /**
     * What to tell the user when a privacy switch is flipped in an open room and the
     * result is not what the switch alone suggests, or null when it simply applies.
     *
     *  - "Hide my IP" turned on after the other device has seen the address: nothing
     *    takes that back, and the status line rightly keeps saying so.
     *  - The last reason to stay on the relay turned off: the room pairs again to
     *    try a direct connection (RoomMachine.setPolicy), which shows the address.
     */
    fun policyNotice(before: Prefs, after: Prefs, state: RoomState?): Int? {
        if (state !is RoomState.Ready || after.tor) return null
        return when {
            after.hideIp && !before.hideIp && state.ip == IpExposure.EXPOSED -> R.string.room_hide_ip_late
            state.transport == Transport.WS_RELAY && (before.forceRelay || before.hideIp) && !after.forceRelay && !after.hideIp ->
                R.string.room_reconnect_direct
            else -> null
        }
    }

    /** Lets the app reach devices on the same network. A runtime permission; older releases do not have it. */
    const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    /** The first Android release that keeps apps without [LOCAL_NETWORK_PERMISSION] off the local network (Android 17). */
    const val LOCAL_NETWORK_ENFORCED_SDK = 37

    /**
     * Whether to offer the local-network permission in this room: only when it would
     * help and is missing. The room is on a relay although both devices are behind
     * the same router, this Android release enforces the permission, it is not
     * granted, and nothing is being transferred (connecting again would end it).
     */
    fun offersLocalNetwork(state: RoomState?, sdk: Int, granted: Boolean, transferring: Boolean): Boolean =
        state is RoomState.Ready && state.relayedOnSameNetwork && sdk >= LOCAL_NETWORK_ENFORCED_SDK && !granted && !transferring

    /** What the room says under its status when it is on a relay for a reason the user can do something about. */
    enum class RelayHint {
        NONE,

        /** A VPN is this app's network: point at its split-tunnel settings. A line of text, nothing to tap. */
        VPN,

        /** Same router, permission missing: the banner that asks for it. */
        LOCAL_NETWORK,
    }

    /**
     * Which hint a room on a relay shows. A VPN comes first: inside one, the
     * local-network permission cannot make the connection direct, so it is not
     * offered. No hint in Tor mode, in a build without WebRTC, or when the relay
     * is what the user asked for ([Prefs.forceRelay], [Prefs.hideIp]).
     */
    fun relayHint(state: RoomState?, prefs: Prefs, webRtc: Boolean, vpn: Boolean, sdk: Int, granted: Boolean, transferring: Boolean): RelayHint {
        if (state !is RoomState.Ready || state.transport == Transport.DIRECT) return RelayHint.NONE
        if (prefs.tor || !webRtc || prefs.forceRelay || prefs.hideIp) return RelayHint.NONE
        if (vpn) return RelayHint.VPN
        return if (offersLocalNetwork(state, sdk, granted, transferring)) RelayHint.LOCAL_NETWORK else RelayHint.NONE
    }

    /** Sizes as people read them. */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(java.util.Locale.ROOT, bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024))
        else -> "%.2f GB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024 * 1024))
    }

    /** When a Pro token expires (Unix seconds), from its `<expiry>.<signature>` form. Not a check of the signature. */
    fun tokenExpiry(token: String): Long? = token.substringBefore('.').takeIf { it.length in 1..12 && it.all(Char::isDigit) }?.toLongOrNull()

    private val TOKEN_SHAPE = Regex("^\\d{1,12}\\.[a-fA-F0-9]{64}$")

    /**
     * True if [token] has the form the server issues and accepts (`verifyProToken` in
     * server.js): `<expiry>.<64 hex digits>`. Only the server can tell whether the
     * signature is good; this keeps text that can never be a token out of the vault.
     */
    fun isTokenShaped(token: String): Boolean = token.trim().let { it.length <= 128 && TOKEN_SHAPE.matches(it) }

    /**
     * What to say when a room needs Tor and Orbot is not installed: an onion room can
     * only be reached that way, any other room needs it because the Tor setting is on.
     */
    fun orbotMissing(onionRoom: Boolean): Int = if (onionRoom) R.string.tor_needs_orbot else R.string.tor_setting_needs_orbot
}
