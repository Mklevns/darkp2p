package com.darkp2p.protocol

import java.security.SecureRandom

/**
 * One room, from joining to leaving: signaling, transport choice, the key
 * handshake and both transfer directions.
 *
 * This is a port of the web client's inline script (`public/index.html`), with
 * its variables as fields and its handlers as functions of the same names, so
 * the two can be read side by side. It is a pure state machine: it performs no
 * I/O and reads no clock of its own. The host feeds it [RoomInput]s one at a
 * time, on one thread, and carries out the [RoomEffect]s it returns, in order.
 * That single ordered stream stands in for the page's event loop and its three
 * promise queues (signaling, inbound frames, outbound frames).
 *
 * Differences from the page, all local (the wire is unchanged):
 *  - Hide My IP without TURN uses the WebSocket relay instead of blocking.
 *  - A tunnel that fails after it was up waits for a manual reconnect, or for
 *    the peer to ask for the relay, instead of falling back by itself.
 *  - ICE candidates are capped below the server's per-pairing limit.
 *  - Chat, used only when the peer's hello offers it too (never with the page).
 *  - A refused WebSocket upgrade with status 403 (wrong Origin) is not retried.
 */
public class RoomMachine(
    private val roomId: String,
    private val roomKey: RoomKey,
    private val clock: () -> Long,
    private val random: SecureRandom,
    policy: LocalPolicy = LocalPolicy(),
    token: String = "",
    /** In [0, 1): spreads reconnects. */
    private val jitter: () -> Double = { random.nextDouble() },
) {
    private enum class Handshake { IDLE, PENDING, OK, MISMATCH, OUTDATED }
    private enum class Mode { WEBRTC, WS }
    private enum class Blocked { ROOM_FULL, TUNNEL_CLOSED, RELAY_CLOSED }

    private class Tx(
        val token: Int,
        val id: TransferId,
        val name: String,
        val size: Long,
        val channel: ChannelRef,
        val keys: TransferKeyCache,
        val key: AeadKey,
        val window: TxWindow,
    ) {
        var readOutstanding = false
        var awaitingFlush = false
    }

    private class Rx(val token: Int, val id: TransferId, val keys: TransferKeyCache, val machine: RxStateMachine) {
        var pendingAck: Long? = null
        var committed = false
    }

    private object Overflow

    private val out = ArrayList<RoomEffect>()

    private var policy = policy
    private var token = token

    // ---- Signaling ----
    private var socket: Int? = null
    private var socketOpen = false
    private var socketCounter = 0
    private var wsAttempt = 0
    private var signalingLost = false // set when an established signaling connection drops
    private var fullRetryUntil = 0L
    private var rejoining = false
    private var fatal: FatalReason? = null
    private var started = false

    // ---- Pairing and session ----
    private var myRole: Role? = null
    private var peerRole: Role? = null
    private var handshake = Handshake.IDLE
    private var myNonce: ByteArray? = null
    private var peerNonce: ByteArray? = null
    private var session: TransferKeyCache? = null
    private var sessionUnconfirmed = false

    // ---- Chat (only when both peers offered it in this session's hellos) ----
    private var offeredChat = false // what our current hello said
    private var peerCaps: Set<String> = emptySet()
    private var myChatId: TransferId? = null
    private var myChatNext = 0L
    private var peerChatId: TransferId? = null
    private var peerChatNext = 0L
    private val chatUnacked = ArrayDeque<Pair<Long, Long>>() // (frame seq, host's localId), oldest first

    // WebSocket relay waiting states, all cleared by resetHandshake():
    private var relayRetryArmed = false // the server's relay was full; a retry is scheduled
    private var relayBusyShown = false
    private var relayIdle = false // the server released an idle relay; resumed on demand
    private var relayExhausted = false // the room's relay budget is spent

    // ---- Transport ----
    private var mode = Mode.WEBRTC
    private var pcCounter = 0
    private var pcActive = false
    private var dcOpen = false
    private var dcEverOpen = false
    private var pcDisconnected = false
    private var tunnelFailed = false
    private var iceMode = IceMode.ALL
    private var routeRelay: Boolean? = null
    private var turnEnabled = false
    private var iceServers: List<IceServer> = listOf(IceServer(listOf("stun:stun.l.google.com:19302")))
    private var relayBudget: RelayBudget? = null
    private var peerAway = false // the peer left the room while its DataChannel stayed up
    private var hideIpViaRelay = false
    private var candidatesSent = 0
    private var blocked: Blocked? = null

    // True once we've signaled an ICE candidate that reveals one of our real IPs,
    // or connected directly. It stays true for the life of this machine, even if
    // TURN or the WS relay wins later and even across re-pairings.
    private var localIpSignaled = false

    // ---- Transfers ----
    private var tx: Tx? = null
    private var rx: Rx? = null
    private var transferCounter = 0

    // Transfer IDs already started by the peer (hello or meta), so a relay cannot
    // replay an earlier file. Capped; the oldest ID is forgotten first.
    private val seenTransferIds = LinkedHashSet<String>()

    // Transfers that already ended (either direction): their late frames are
    // dropped quietly. Capped; the oldest ID is forgotten first.
    private val endedTransferIds = LinkedHashSet<String>()

    // Frames received but not yet processed; bounded by the credit window.
    private val inbound = ArrayDeque<Any>()
    private var rxBusy = false // a write or commit of the incoming file is in progress
    private var rxPendingData = 0
    private var rxPendingCtrl = 0
    private var rxOverflowQueued = false

    // ------------------------------------------------------------------
    // Host interface
    // ------------------------------------------------------------------

    /** Opens the first signaling connection. */
    public fun start(): List<RoomEffect> = run {
        if (!started) {
            started = true
            connectSignaling()
        }
    }

    public fun handle(input: RoomInput): List<RoomEffect> = run {
        when (input) {
            is RoomInput.SignalingOpened -> if (input.socket == socket) onSignalingOpened()
            is RoomInput.SignalingClosed -> if (input.socket == socket) onSignalingClosed(input.cause)
            is RoomInput.Server -> if (input.socket == socket) handleSignal(input.msg)
            is RoomInput.Frame -> enqueueIncomingBinary(input.channel, input.bytes)
            is RoomInput.FrameSent -> tx?.takeIf { it.token == input.token }?.let {
                it.awaitingFlush = false
                advanceTx(it)
            }
            is RoomInput.LocalSdp -> if (isCurrentPc(input.pc)) onLocalSdp(input.kind, input.sdp)
            is RoomInput.LocalCandidate -> if (isCurrentPc(input.pc)) onLocalCandidate(input)
            is RoomInput.PeerConnectionState -> if (isCurrentPc(input.pc)) onPcState(input.state)
            is RoomInput.DataChannelOpened -> if (isCurrentPc(input.pc)) onDataChannelOpened()
            is RoomInput.DataChannelClosed -> if (isCurrentPc(input.pc)) onDataChannelClosed()
            is RoomInput.SelectedRoute -> if (isCurrentPc(input.pc)) {
                routeRelay = input.relay
                // The peer now sees our address on the packets themselves.
                if (input.relay == false) localIpSignaled = true
            }
            is RoomInput.WebRtcFailed -> if (isCurrentPc(input.pc)) activateWsFallback(true)
            is RoomInput.TimerFired -> onTimer(input.id)
            is RoomInput.SendFile -> sendFile(input)
            RoomInput.CancelSend -> tx?.let { abortTx(it, AbortReason.USER, Notice.SEND_CANCELLED) }
            RoomInput.AcceptIncoming -> acceptRx()
            is RoomInput.DeclineIncoming -> declineRx(input.reason)
            RoomInput.CancelReceive -> abortRx(AbortReason.USER, Notice.RECEIVE_ABORTED, "cancelled")
            is RoomInput.SetPolicy -> setPolicy(input.policy)
            is RoomInput.SetToken -> token = input.token
            is RoomInput.SendChat -> sendChat(input.localId, input.text)
            RoomInput.ResumeRelay -> if (relayIdle) resumeRelay(true)
            RoomInput.Reconnect -> rejoinRoom(closeTunnel = true)
            is RoomInput.ChunkRead -> onChunkRead(input.transfer, input.bytes)
            is RoomInput.ChunkReadFailed -> tx?.takeIf { it.token == input.transfer }?.let {
                abortTx(it, AbortReason.DISK, Notice.SEND_ABORTED, "reading the file failed")
            }
            is RoomInput.ChunkWritten -> onChunkWritten(input.transfer)
            is RoomInput.SinkCommitted -> onSinkCommitted(input.transfer)
            is RoomInput.SinkFailed -> if (rx?.token == input.transfer) {
                rxBusy = false
                abortRx(AbortReason.DISK, Notice.RECEIVE_ABORTED, "saving the file failed")
            }
        }
    }

    private inline fun run(block: () -> Unit): List<RoomEffect> {
        out.clear()
        block()
        drainInbound()
        return ArrayList(out)
    }

    /** The state to show. */
    public val state: RoomState
        get() {
            fatal?.let { return RoomState.Fatal(it) }
            if (handshake == Handshake.MISMATCH) return RoomState.KeyMismatch
            if (handshake == Handshake.OUTDATED) return RoomState.PeerOutdated
            if (relayExhausted) {
                return RoomState.RelayExhausted(relayBudget?.cap, canTryDirect = policy.webRtcAvailable, proWouldRaise = !turnEnabled)
            }
            if (blocked == Blocked.ROOM_FULL) return RoomState.RoomFull
            if (relayRetryArmed) return RoomState.RelayBusy
            if (relayIdle) return RoomState.RelayIdle
            val directUp = isDirectTunnelUp()
            if (!socketOpen && !directUp) return RoomState.Connecting(wsAttempt, signalingLost)
            if (myRole == null) return RoomState.WaitingForPeer
            val transport = when {
                mode == Mode.WS -> Transport.WS_RELAY
                routeRelay == true || iceMode == IceMode.RELAY -> Transport.TURN
                else -> Transport.DIRECT
            }
            if (tunnelFailed) return RoomState.Interrupted(transport, canReconnect = true)
            if (pcDisconnected || blocked != null) return RoomState.Interrupted(transport, canReconnect = blocked == Blocked.TUNNEL_CLOSED)
            if (handshake == Handshake.OK && isOpen(activeChannel())) {
                val ip = when {
                    localIpSignaled -> IpExposure.EXPOSED
                    mode == Mode.WS || routeRelay == true -> IpExposure.MASKED
                    else -> IpExposure.UNVERIFIED
                }
                val onRelay = mode == Mode.WS
                return RoomState.Ready(
                    transport, ip, relayBudget?.cap.takeIf { onRelay }, relayBudget?.remaining.takeIf { onRelay },
                    peerSignalingAway = peerAway, hideIpViaRelay = hideIpViaRelay && onRelay, chat = chatEnabled(),
                )
            }
            if (handshake == Handshake.PENDING) return RoomState.Verifying(transport, sessionUnconfirmed)
            return RoomState.Negotiating(iceMode)
        }

    /** A file can be offered only now: the peer's key is verified and nothing is being sent. */
    public val canSend: Boolean get() = handshake == Handshake.OK && tx == null && isOpen(activeChannel()) && session != null

    public val outgoing: TransferProgress?
        get() = tx?.let {
            val sent = minOf(it.size, it.window.dataSent * WireConstants.CHUNK_SIZE)
            TransferProgress(it.name, it.size, sent, it.window.phase.wire)
        }

    public val incoming: TransferProgress?
        get() = rx?.let { TransferProgress(it.machine.meta.name, it.machine.meta.size, it.machine.bytes, it.machine.phase.wire) }

    /** The MIME type the sender announced for the incoming file. */
    public val incomingMime: String? get() = rx?.machine?.meta?.mime

    // ------------------------------------------------------------------
    // Small helpers
    // ------------------------------------------------------------------

    private fun notify(notice: Notice, detail: String? = null) {
        out += RoomEffect.Notify(RoomEvent(notice, detail))
    }

    private fun arm(id: TimerId, delayMs: Long) {
        out += RoomEffect.ArmTimer(id, delayMs)
    }

    private fun cancel(id: TimerId) {
        out += RoomEffect.CancelTimer(id)
    }

    private fun randomBytes(n: Int): ByteArray = ByteArray(n).also(random::nextBytes)

    private fun signal(msg: ClientMessage): Boolean {
        val s = socket
        if (s == null || !socketOpen) return false
        out += RoomEffect.SendSignal(s, msg)
        return true
    }

    private fun isCurrentPc(pc: Int): Boolean = pcActive && pc == pcCounter

    private fun activeChannel(): ChannelRef? =
        if (mode == Mode.WS) socket?.let { ChannelRef(ChannelKind.WS_RELAY, it) }
        else if (pcActive) ChannelRef(ChannelKind.DATA_CHANNEL, pcCounter) else null

    private fun isOpen(channel: ChannelRef?): Boolean = when (channel?.kind) {
        null -> false
        ChannelKind.WS_RELAY -> socketOpen && channel.id == socket
        ChannelKind.DATA_CHANNEL -> dcOpen && pcActive && channel.id == pcCounter
    }

    private fun rememberTransferId(id: TransferId) {
        seenTransferIds.add(id.hex)
        while (seenTransferIds.size > WireConstants.SEEN_TRANSFER_IDS_MAX) seenTransferIds.remove(seenTransferIds.first())
    }

    private fun markTransferEnded(id: TransferId) {
        endedTransferIds.add(id.hex)
        while (endedTransferIds.size > WireConstants.ENDED_TRANSFER_IDS_MAX) endedTransferIds.remove(endedTransferIds.first())
    }

    // Every outgoing frame is encrypted here and handed over in order, under the
    // keys of the session it belongs to.
    private fun sendFrame(channel: ChannelRef?, type: FrameType, id: TransferId, seq: Long, payload: ByteArray, key: AeadKey, notifyToken: Int? = null): Boolean {
        val role = myRole
        if (channel == null || role == null || !isOpen(channel)) return false
        val frame = FrameCodec.encode(type, role, id, seq, payload, key, randomBytes(WireConstants.FRAME_IV_LEN))
        // The relay counts every binary frame against the room's budget.
        if (channel.kind == ChannelKind.WS_RELAY) relayBudget?.charge(frame.size.toLong())
        out += RoomEffect.SendFrame(channel, frame, notifyToken)
        return true
    }

    private fun sendCtrl(channel: ChannelRef?, id: TransferId, seq: Long, json: String, keys: TransferKeyCache?): Boolean {
        if (keys == null) return false
        return sendFrame(channel, FrameType.CTRL, id, seq, json.toByteArray(Charsets.UTF_8), keys[id])
    }

    // ------------------------------------------------------------------
    // Handshake
    // ------------------------------------------------------------------

    private fun resetHandshake() {
        cancel(TimerId.HELLO)
        handshake = Handshake.IDLE
        myNonce = null
        peerNonce = null
        session = null
        peerCaps = emptySet()
        resetChat()
        sessionUnconfirmed = false
        cancel(TimerId.RELAY_RETRY)
        relayRetryArmed = false
        relayBusyShown = false
        relayIdle = false
        relayExhausted = false
    }

    // A failed key check stays until the next pairing.
    private fun handshakeFailed(): Boolean = handshake == Handshake.MISMATCH || handshake == Handshake.OUTDATED

    private fun failHandshake(kind: Handshake) {
        cancel(TimerId.HELLO)
        if (handshake == kind) return
        handshake = kind
        abortAllTransfersLocally()
        notify(if (kind == Handshake.MISMATCH) Notice.KEY_MISMATCH else Notice.PEER_OUTDATED)
    }

    private fun sendHello(channel: ChannelRef?, nonce: ByteArray) {
        val id = TransferId(randomBytes(WireConstants.TRANSFER_ID_LEN))
        // The page reads only `v` and `nonce`; `caps` is for a peer that knows it.
        offeredChat = policy.chat
        val caps = if (offeredChat) listOf(Capabilities.CHAT) else emptyList()
        sendFrame(channel, FrameType.HELLO, id, 0, Ctrl.hello(nonce, caps).toByteArray(Charsets.UTF_8), FrameKeys.hello(roomKey, id))
    }

    // Derives the session once both nonces are known and confirms it to the peer.
    private fun updateSession() {
        val mine = myNonce ?: return
        val theirs = peerNonce ?: return
        val role = myRole ?: return
        val nonces = if (role == Role.INITIATOR) SessionNonces(mine, theirs) else SessionNonces(theirs, mine)
        val keys = TransferKeyCache(roomKey, nonces)
        session = keys
        val channel = activeChannel()
        if (isOpen(channel)) sendCtrl(channel, TransferId(randomBytes(WireConstants.TRANSFER_ID_LEN)), 0, Ctrl.helloAck(), keys)
    }

    // A session change ends any transfer of the old session.
    private fun endTransfersForNewSession() {
        if (tx != null || rx != null) {
            notify(Notice.SESSION_RENEWED)
            abortAllTransfersLocally()
        }
    }

    private fun enterPending() {
        handshake = Handshake.PENDING
        sessionUnconfirmed = false
        arm(TimerId.HELLO, WireConstants.HELLO_TIMEOUT_MS)
    }

    // Called whenever a transport opens (DataChannel or WS relay): new nonce, new session.
    private fun startHandshake(channel: ChannelRef?) {
        val nonce = randomBytes(WireConstants.SESSION_NONCE_LEN)
        myNonce = nonce
        session = null
        resetChat()
        sendHello(channel, nonce)
        if (handshakeFailed()) return
        endTransfersForNewSession()
        enterPending()
        updateSession()
    }

    private fun acceptHello(hello: Ctrl.Hello) {
        if (!hello.versionOk) {
            failHandshake(Handshake.OUTDATED)
            return
        }
        val nonce = hello.nonce?.let(Base64Url::decode)
        if (nonce == null || nonce.size != WireConstants.SESSION_NONCE_LEN) throw ProtocolError(AbortReason.INTEGRITY, "Invalid hello nonce")
        if (Bytes.constantTimeEquals(nonce, peerNonce)) return
        endTransfersForNewSession()
        peerNonce = nonce
        session = null
        peerCaps = hello.caps
        resetChat()
        if (handshake == Handshake.OK || handshake == Handshake.PENDING) enterPending()
        updateSession()
    }

    // The peer derived the same session key (its hello-ack decrypted under it).
    private fun acceptHelloAck() {
        if (handshake != Handshake.PENDING) return
        cancel(TimerId.HELLO)
        handshake = Handshake.OK
        sessionUnconfirmed = false
        notify(Notice.KEY_VERIFIED)
    }

    // ------------------------------------------------------------------
    // Chat
    // ------------------------------------------------------------------

    private fun chatEnabled(): Boolean =
        offeredChat && Capabilities.CHAT in peerCaps && handshake == Handshake.OK && session != null && isOpen(activeChannel())

    // A session change starts new chat streams; receipts of the old one cannot arrive any more.
    private fun resetChat() {
        while (chatUnacked.isNotEmpty()) out += RoomEffect.ChatFailed(chatUnacked.removeFirst().second, ChatFailure.SESSION_ENDED)
        myChatId = null
        myChatNext = 0
        peerChatId = null
        peerChatNext = 0
    }

    // The next frame of our chat stream. Returns its seq, or null if it could not be sent.
    private fun sendChatFrame(json: String): Long? {
        val id = myChatId ?: TransferId(randomBytes(WireConstants.TRANSFER_ID_LEN)).also { myChatId = it }
        if (myChatNext > FrameCodec.MAX_SEQ) return null
        if (!sendCtrl(activeChannel(), id, myChatNext, json, session)) return null
        return myChatNext++
    }

    private fun sendChat(localId: Long, text: String) {
        val failure = when {
            !chatEnabled() -> ChatFailure.UNAVAILABLE
            text.isEmpty() -> ChatFailure.EMPTY
            text.toByteArray(Charsets.UTF_8).size > ChatConstants.MAX_TEXT_BYTES -> ChatFailure.TOO_LONG
            chatUnacked.size >= ChatConstants.WINDOW -> ChatFailure.BUSY
            else -> null
        }
        if (failure != null) {
            out += RoomEffect.ChatFailed(localId, failure)
            return
        }
        val json = Ctrl.chat(text)
        // The relay counts the message and its receipt against the room.
        val cost = 2L * WireConstants.FRAME_OVERHEAD + json.toByteArray(Charsets.UTF_8).size + 64
        if (mode == Mode.WS && relayBudget?.let { it.remaining - cost < ChatConstants.RELAY_RESERVE } == true) {
            out += RoomEffect.ChatFailed(localId, ChatFailure.NO_RELAY_BUDGET)
            return
        }
        val seq = sendChatFrame(json)
        if (seq == null) out += RoomEffect.ChatFailed(localId, ChatFailure.UNAVAILABLE) else chatUnacked.addLast(seq to localId)
    }

    // A frame of the peer's chat stream. A violation refuses the frame and nothing
    // else: chat never ends a file transfer.
    private fun onChatFrame(id: TransferId, seq: Long, ctrl: Ctrl) {
        val known = peerChatId
        val inOrder = if (known == null) seq == 0L else id == known && seq == peerChatNext
        if (!chatEnabled() || !inOrder) {
            notify(Notice.CHAT_FRAME_REFUSED)
            return
        }
        when (ctrl) {
            is Ctrl.Chat -> {
                val text = ctrl.text
                if (text.isNullOrEmpty() || text.toByteArray(Charsets.UTF_8).size > ChatConstants.MAX_TEXT_BYTES) {
                    notify(Notice.CHAT_FRAME_REFUSED)
                    return
                }
                peerChatId = id
                peerChatNext = seq + 1
                out += RoomEffect.ChatReceived(text)
                sendChatFrame(Ctrl.chatAck(seq))
            }
            is Ctrl.ChatAck -> {
                val upTo = ctrl.seq
                // A receipt for a frame we have not sent is not a receipt.
                if (upTo == null || upTo < 0 || upTo >= myChatNext) {
                    notify(Notice.CHAT_FRAME_REFUSED)
                    return
                }
                peerChatId = id
                peerChatNext = seq + 1
                while (chatUnacked.isNotEmpty() && chatUnacked.first().first <= upTo) {
                    out += RoomEffect.ChatDelivered(chatUnacked.removeFirst().second)
                }
            }
            else -> notify(Notice.CHAT_FRAME_REFUSED)
        }
    }

    // ------------------------------------------------------------------
    // WebSocket relay
    // ------------------------------------------------------------------

    // The room's relay budget is spent: nothing more can cross the relay, so no
    // handshake is attempted (its frames would only be dropped by the server).
    private fun showRelayExhausted() {
        relayBudget?.exhaust()
        abortAllTransfersLocally()
        if (relayExhausted || handshakeFailed()) return
        resetHandshake()
        relayExhausted = true
        notify(Notice.RELAY_EXHAUSTED)
    }

    // (Re)starts the key handshake over the relay: after the server freed an idle
    // relay, or when a full relay is retried. notifyPeer asks the server for the
    // relay again, which also tells the peer.
    private fun resumeRelay(notifyPeer: Boolean) {
        if (mode != Mode.WS || !socketOpen) return
        cancel(TimerId.RELAY_RETRY)
        relayRetryArmed = false
        relayIdle = false
        if (notifyPeer) signal(ClientMessage.WsFallback)
        if (relayBudget?.belowHandshakeMinimum == true) {
            showRelayExhausted()
            return
        }
        startHandshake(activeChannel())
    }

    private fun activateWsFallback(notifyPeer: Boolean) {
        if (mode == Mode.WS) {
            // The peer asked for the relay while we were waiting for it.
            if (!notifyPeer && (relayIdle || relayRetryArmed)) resumeRelay(false)
            return
        }
        cleanupPeer()
        mode = Mode.WS
        tunnelFailed = false
        if (!socketOpen) {
            notify(Notice.RELAY_CLOSED)
            blocked = Blocked.RELAY_CLOSED
            return
        }
        blocked = null
        if (notifyPeer) signal(ClientMessage.WsFallback)
        if (relayBudget?.belowHandshakeMinimum == true) {
            showRelayExhausted()
            return
        }
        notify(if (hideIpViaRelay) Notice.RELAY_ACTIVE_HIDE_IP_NO_PRO else Notice.RELAY_ACTIVE)
        // Files can be sent only once the key handshake completes.
        startHandshake(activeChannel())
    }

    // Leaves and rejoins the room on a new signaling connection. The server then
    // pairs both peers afresh, so each side renegotiates from its current settings.
    private fun rejoinRoom(closeTunnel: Boolean): Boolean {
        val s = socket
        if (s == null || !socketOpen) return false
        notify(Notice.REJOINING)
        if (closeTunnel) {
            // Otherwise a verified tunnel would be kept by the next `ready`.
            cleanupPeer()
            if (!handshakeFailed()) resetHandshake()
        }
        rejoining = true
        out += RoomEffect.CloseSignaling(s)
        onSignalingClosed(CloseCause.Closed(1000))
        return true
    }

    private fun setPolicy(next: LocalPolicy) {
        val prev = policy
        policy = next
        if (myRole == null) return // without a peer the next `ready` reads the policy anyway

        if (next.forceWsRelay && !prev.forceWsRelay) {
            if (pcActive) activateWsFallback(true)
            return
        }
        val wouldUseWebRtc = next.webRtcAvailable && !next.forceWsRelay && !(next.hideIp && !turnEnabled)
        when {
            // On the relay with no reason left to be: there is no way back to WebRTC
            // on the same pairing, so re-pair.
            mode == Mode.WS && wouldUseWebRtc && (prev.forceWsRelay || prev.hideIp) -> rejoinRoom(closeTunnel = false)
            // The tunnel was negotiated under the other candidate policy.
            pcActive && (next.hideIp != (iceMode == IceMode.RELAY) || !wouldUseWebRtc) -> rejoinRoom(closeTunnel = true)
        }
    }

    // ------------------------------------------------------------------
    // WebRTC
    // ------------------------------------------------------------------

    private fun cleanupPeer() {
        cancel(TimerId.ICE)
        if (pcActive) {
            out += RoomEffect.CloseWebRtc(pcCounter)
            pcActive = false
        }
        dcOpen = false
        pcDisconnected = false
        routeRelay = null
        abortAllTransfersLocally()
    }

    private fun initPeer(initiator: Boolean) {
        mode = Mode.WEBRTC
        hideIpViaRelay = false
        if (policy.forceWsRelay || !policy.webRtcAvailable) {
            activateWsFallback(true)
            return
        }
        if (policy.hideIp && !turnEnabled) {
            // No TURN for this room: the relay also keeps our address from the peer.
            hideIpViaRelay = true
            activateWsFallback(true)
            return
        }
        cleanupPeer()
        pcCounter += 1
        pcActive = true
        dcEverOpen = false
        iceMode = if (policy.hideIp) IceMode.RELAY else IceMode.ALL
        out += RoomEffect.StartWebRtc(pcCounter, initiator, iceServers, iceMode)
        arm(TimerId.ICE, ICE_TIMEOUT_MS)
    }

    private fun onLocalSdp(kind: SdpKind, sdp: String) {
        // Candidates travel only as `candidate` messages, where the policy sees each one.
        val clean = sdp.split("\r\n").filterNot { it.startsWith("a=candidate:") }.joinToString("\r\n")
        signal(if (kind == SdpKind.OFFER) ClientMessage.Offer(clean) else ClientMessage.Answer(clean))
    }

    private fun onLocalCandidate(c: RoomInput.LocalCandidate) {
        if (!socketOpen) return
        val line = CandidatePolicy.toSignal(c.candidate, iceMode) ?: return
        if (candidatesSent >= CandidatePolicy.MAX_CANDIDATES_PER_PAIRING) return
        candidatesSent += 1
        if (CandidatePolicy.exposesIp(line)) localIpSignaled = true
        signal(ClientMessage.Candidate(line, c.sdpMid, c.sdpMLineIndex))
    }

    private fun onPcState(state: PcState) {
        when (state) {
            PcState.CONNECTED -> pcDisconnected = false
            PcState.DISCONNECTED -> {
                pcDisconnected = true
                notify(Notice.TUNNEL_DISCONNECTED)
            }
            PcState.FAILED ->
                if (!dcEverOpen) {
                    notify(Notice.WEBRTC_FAILED)
                    activateWsFallback(true)
                } else {
                    // The tunnel was up and is gone. Gathering candidates again (possibly
                    // on another network) is the user's decision: wait for Reconnect,
                    // or for the peer to ask for the relay.
                    cleanupPeer()
                    if (!handshakeFailed()) resetHandshake()
                    tunnelFailed = true
                    notify(Notice.TUNNEL_FAILED)
                }
        }
    }

    private fun onDataChannelOpened() {
        cancel(TimerId.ICE)
        dcOpen = true
        dcEverOpen = true
        pcDisconnected = false
        notify(Notice.TUNNEL_OPEN)
        startHandshake(activeChannel())
    }

    private fun onDataChannelClosed() {
        dcOpen = false
        notify(Notice.TUNNEL_CLOSED)
        if (peerAway) {
            // The peer had already left the room: it is gone, wait for a new pairing.
            enterStandby()
            return
        }
        abortAllTransfersLocally()
        blocked = Blocked.TUNNEL_CLOSED
    }

    // ------------------------------------------------------------------
    // Signaling
    // ------------------------------------------------------------------

    // Back to "waiting for a peer": no transport, no session, no roles.
    private fun enterStandby() {
        peerAway = false
        mode = Mode.WEBRTC
        turnEnabled = false
        hideIpViaRelay = false
        tunnelFailed = false
        blocked = null
        cleanupPeer()
        resetHandshake()
        myRole = null
        peerRole = null
    }

    // A verified DataChannel does not need signaling any more.
    private fun isDirectTunnelUp(): Boolean = mode == Mode.WEBRTC && pcActive && dcOpen && handshake == Handshake.OK

    private fun connectSignaling() {
        socketCounter += 1
        socket = socketCounter
        socketOpen = false
        out += RoomEffect.OpenSignaling(socketCounter)
    }

    private fun onSignalingOpened() {
        socketOpen = true
        // The backoff resets only once this connection has proved stable.
        arm(TimerId.STABLE, WireConstants.RECONNECT_STABLE_MS)
        notify(if (signalingLost) Notice.REJOINING else Notice.SIGNALING_CONNECTED)
        signal(ClientMessage.Join(roomId, token))
    }

    private fun onSignalingClosed(cause: CloseCause) {
        cancel(TimerId.STABLE)
        socket = null
        socketOpen = false
        if (!signalingLost) {
            signalingLost = true
            fullRetryUntil = clock() + WireConstants.FULL_RETRY_MS
            // rejoinRoom() closed it on purpose and has already said why.
            if (!rejoining) notify(if (isDirectTunnelUp()) Notice.SIGNALING_LOST_TUNNEL_UP else Notice.SIGNALING_LOST)
        }
        rejoining = false
        if (mode == Mode.WS) {
            // The relay was the transport: its transfers and session are gone.
            abortAllTransfersLocally()
            resetHandshake()
            blocked = Blocked.RELAY_CLOSED
        }
        if (cause is CloseCause.HttpRejected && cause.status == 403) {
            // A wrong Origin stays wrong: retrying would only spend the server's rate limit.
            fatal = FatalReason.ORIGIN_REJECTED
            notify(Notice.ORIGIN_REJECTED)
            return
        }
        val delay = Backoff.reconnectDelay(wsAttempt, jitter())
        wsAttempt += 1
        arm(TimerId.RECONNECT, delay)
    }

    private fun handleSignal(msg: ServerMessage) {
        when (msg) {
            ServerMessage.WsFallback -> activateWsFallback(false)

            ServerMessage.Full -> {
                // After a drop, the server may still hold our old socket until its
                // heartbeat notices: retry for a while before giving up.
                val s = socket
                if (signalingLost && clock() < fullRetryUntil && s != null) {
                    out += RoomEffect.CloseSignaling(s)
                    onSignalingClosed(CloseCause.Closed(1000))
                    return
                }
                blocked = Blocked.ROOM_FULL
                notify(Notice.ROOM_FULL)
            }

            is ServerMessage.Error -> onServerError(msg.reason)

            ServerMessage.PeerLeft -> {
                if (isDirectTunnelUp()) {
                    // Only the peer's signaling connection dropped: the tunnel and any
                    // transfer carry on. If the DataChannel closes later, standby follows.
                    peerAway = true
                    notify(Notice.PEER_SIGNALING_AWAY)
                    return
                }
                notify(Notice.PEER_LEFT)
                enterStandby()
            }

            is ServerMessage.Ready -> {
                peerAway = false
                if (signalingLost) {
                    signalingLost = false
                    notify(Notice.SIGNALING_RESTORED)
                }
                relayBudget = RelayBudget.fromReady(msg.wsRelayCap, msg.wsRelayRemaining, msg.turnEnabled)
                if (isDirectTunnelUp()) {
                    // Re-pairing after a signaling drop: the verified tunnel to the same
                    // peer is still up, so keep it (and its roles and session).
                    turnEnabled = msg.turnEnabled
                    notify(Notice.KEPT_TUNNEL)
                    return
                }
                resetHandshake()
                myRole = if (msg.initiator) Role.INITIATOR else Role.RESPONDER
                peerRole = myRole?.peer
                turnEnabled = msg.turnEnabled
                if (msg.iceServers.isNotEmpty()) iceServers = msg.iceServers
                blocked = null
                tunnelFailed = false
                candidatesSent = 0 // the server counts candidates per pairing
                notify(Notice.PEER_DETECTED, if (turnEnabled) "turn" else "stun")
                initPeer(msg.initiator)
            }

            is ServerMessage.Offer -> if (pcActive) out += RoomEffect.ApplyRemoteSdp(pcCounter, SdpKind.OFFER, msg.sdp)
            is ServerMessage.Answer -> if (pcActive) out += RoomEffect.ApplyRemoteSdp(pcCounter, SdpKind.ANSWER, msg.sdp)
            is ServerMessage.Candidate ->
                // Browsers mark the end of candidates with an empty one.
                if (pcActive && msg.candidate.isNotBlank()) {
                    out += RoomEffect.AddRemoteCandidate(pcCounter, msg.candidate, msg.sdpMid, msg.sdpMLineIndex)
                }
        }
    }

    private fun onServerError(reason: String?) {
        when (reason) {
            ServerMessage.RELAY_LIMIT_EXCEEDED ->
                if (mode == Mode.WS) showRelayExhausted() else relayBudget?.exhaust()

            ServerMessage.RELAY_BUSY -> {
                // The server's relay is full and it did not carry our hello: that is
                // not an outdated peer. Say so and retry, instead of "verifying".
                cancel(TimerId.HELLO)
                if (mode == Mode.WS && !handshakeFailed()) {
                    handshake = Handshake.IDLE
                    if (!relayBusyShown) {
                        relayBusyShown = true
                        notify(Notice.RELAY_BUSY)
                    }
                    relayRetryArmed = true
                    arm(TimerId.RELAY_RETRY, WireConstants.RELAY_BUSY_RETRY_MS)
                }
            }

            ServerMessage.RELAY_IDLE ->
                // The server freed this room's relay slot after a while without transfers.
                if (mode == Mode.WS && !handshakeFailed() && !relayExhausted) {
                    abortAllTransfersLocally()
                    resetHandshake()
                    relayIdle = true
                    notify(Notice.RELAY_IDLE)
                }

            ServerMessage.INVALID_ROOM -> {
                fatal = FatalReason.INVALID_ROOM
                notify(Notice.SERVER_ERROR, reason)
            }

            else -> notify(Notice.SERVER_ERROR, reason?.take(64))
        }
    }

    private fun onTimer(id: TimerId) {
        when (id) {
            TimerId.RECONNECT -> if (socket == null && fatal == null) connectSignaling()
            TimerId.STABLE -> if (socketOpen) wsAttempt = 0
            TimerId.HELLO -> {
                if (handshake != Handshake.PENDING || !isOpen(activeChannel())) return
                if (peerNonce == null) failHandshake(Handshake.OUTDATED)
                else {
                    sessionUnconfirmed = true
                    notify(Notice.SESSION_UNCONFIRMED)
                }
            }
            TimerId.ICE -> if (pcActive && !dcOpen) {
                notify(Notice.WEBRTC_TIMEOUT)
                activateWsFallback(true)
            }
            TimerId.RELAY_RETRY -> if (relayRetryArmed) {
                relayRetryArmed = false
                resumeRelay(true)
            }
            TimerId.TX_WATCHDOG -> onTxWatchdog()
        }
    }

    // ------------------------------------------------------------------
    // Transfers: shared
    // ------------------------------------------------------------------

    // The transport is gone: end both directions without notifying the peer.
    private fun abortAllTransfersLocally() {
        tx?.let { t ->
            if (t.window.endUnconfirmed()) {
                notify(Notice.SENT_UNCONFIRMED, t.name)
                finishTx(t)
            } else {
                abortTx(t, null, Notice.SEND_INTERRUPTED)
            }
        }
        rx?.let { notify(Notice.RECEIVE_INTERRUPTED, it.machine.meta.name) }
        resetRxState()
    }

    // ------------------------------------------------------------------
    // Transfers: receiving
    // ------------------------------------------------------------------

    private fun resetRxState() {
        val prev = rx ?: return
        rx = null
        rxBusy = false
        markTransferEnded(prev.id)
        if (!prev.committed) out += RoomEffect.DiscardSink(prev.token)
    }

    // Sends a reply (receiver -> sender) for an incoming transfer.
    private fun rxReply(state: Rx, json: String) {
        sendCtrl(activeChannel(), state.id, state.machine.nextReplySeq(), json, state.keys)
    }

    private fun endRx(state: Rx) {
        if (rx === state) resetRxState()
    }

    // Local abort of the incoming transfer; tells the sender why.
    private fun abortRx(reason: AbortReason, notice: Notice, detail: String? = null) {
        val state = rx ?: return
        if (state.machine.abort()) rxReply(state, Ctrl.abort(reason))
        notify(notice, detail ?: state.machine.meta.name)
        endRx(state)
    }

    private fun onIncomingMeta(id: TransferId, ctrl: Ctrl.Meta) {
        val channel = activeChannel()
        val keys = session
        val meta = try {
            Ctrl.parseMeta(ctrl)
        } catch (e: ProtocolError) {
            markTransferEnded(id)
            sendCtrl(channel, id, 1, Ctrl.abort(AbortReason.INTEGRITY), keys)
            throw e
        }
        // One incoming transfer at a time; an outgoing one does not count.
        if (rx != null) {
            markTransferEnded(id)
            sendCtrl(channel, id, 1, Ctrl.decline(DeclineReason.BUSY), keys)
            notify(Notice.DECLINED_BUSY, meta.name)
            return
        }
        transferCounter += 1
        rx = Rx(transferCounter, id, keys ?: throw ProtocolError(AbortReason.INTEGRITY, "No session"), RxStateMachine(meta))
        notify(Notice.INCOMING_OFFER, meta.name)
    }

    private fun acceptRx() {
        val state = rx ?: return
        if (state.machine.phase != RxStateMachine.Phase.OFFERED) return
        state.machine.accept()
        rxReply(state, Ctrl.accept())
        notify(Notice.RECEIVING, state.machine.meta.name)
    }

    private fun declineRx(reason: DeclineReason) {
        val state = rx ?: return
        if (state.machine.phase != RxStateMachine.Phase.OFFERED) return
        state.machine.decline()
        rxReply(state, Ctrl.decline(reason))
        endRx(state)
    }

    private fun onIncomingData(state: Rx, seq: Long, data: ByteArray) {
        state.pendingAck = state.machine.onData(seq, data.size)
        // The next frame is looked at only after this chunk is written, so replies
        // keep their order and at most the credit window is held in memory.
        rxBusy = true
        out += RoomEffect.WriteChunk(state.token, data)
    }

    private fun onChunkWritten(token: Int) {
        val state = rx?.takeIf { it.token == token } ?: return
        rxBusy = false
        state.pendingAck?.let { rxReply(state, Ctrl.ack(it)) }
        state.pendingAck = null
    }

    private fun onIncomingDone(state: Rx, seq: Long, done: Ctrl.Done) {
        val ack = state.machine.onDone(seq, done)
        rxReply(state, Ctrl.ack(ack, final = true))
        // Committed to its destination only now, after verification.
        rxBusy = true
        out += RoomEffect.CommitSink(state.token)
    }

    private fun onSinkCommitted(token: Int) {
        val state = rx?.takeIf { it.token == token } ?: return
        rxBusy = false
        state.machine.complete()
        state.committed = true
        rxReply(state, Ctrl.complete())
        notify(Notice.SAVED, state.machine.meta.name)
        endRx(state)
    }

    private fun onIncomingAbort(state: Rx, seq: Long, abort: Ctrl.Abort) {
        state.machine.onAbort(seq)
        notify(Notice.SENDER_ABORTED, AbortReason.fromWire(abort.reason)?.wire ?: "unknown")
        endRx(state)
    }

    // Refuses frames before decryption: DATA and replies only for an active
    // transfer, and a new transfer (seq 0) only with an unseen transferId.
    private fun lookupRxKey(id: TransferId, type: FrameType, seq: Long): AeadKey {
        if (type == FrameType.HELLO) {
            if (seq != 0L) throw FrameError.Rejected("Malformed hello")
            if (id.hex in seenTransferIds) throw FrameError.Rejected("Replayed hello")
            return FrameKeys.hello(roomKey, id)
        }
        // Session frames before both nonces are known (e.g. a hello-ack of a superseded session).
        val keys = session ?: throw FrameError.Stale()
        val rxActive = rx?.id == id
        val txActive = tx?.id == id
        if (!rxActive && !txActive && id.hex in endedTransferIds) throw FrameError.Dropped()
        if (type == FrameType.DATA || seq != 0L) {
            // Later frames of the peer's chat stream; onChatFrame checks their order.
            if (type == FrameType.CTRL && peerChatId == id && handshake == Handshake.OK) return keys[id]
            val allowed = rxActive || (txActive && type == FrameType.CTRL)
            if (!allowed || handshake != Handshake.OK) throw FrameError.Rejected("Frame from an unknown transfer")
        } else if (id.hex in seenTransferIds) {
            throw FrameError.Rejected("Replayed transfer")
        }
        return keys[id]
    }

    private fun handleIncomingFrame(bytes: ByteArray) {
        val senderRole = peerRole ?: return
        if (handshakeFailed()) return

        val frame = try {
            FrameCodec.decode(bytes, senderRole, ::lookupRxKey)
        } catch (e: FrameError) {
            when {
                e is FrameError.Legacy -> {
                    failHandshake(Handshake.OUTDATED)
                    return
                }
                e is FrameError.Stale -> return
                e is FrameError.Auth && handshake != Handshake.OK -> {
                    // A hello that fails is a wrong room key. A session frame that fails
                    // before the session is confirmed is a hello-ack from a superseded
                    // session: ignore it.
                    if ((bytes[0].toInt() and 0xff) == FrameType.HELLO.wire) failHandshake(Handshake.MISMATCH)
                    return
                }
                else -> throw e
            }
        }

        if (frame.type == FrameType.HELLO) {
            rememberTransferId(frame.transferId)
            val hello = Ctrl.decode(frame.payload) as? Ctrl.Hello ?: throw ProtocolError(AbortReason.INTEGRITY, "Unexpected hello frame")
            acceptHello(hello)
            return
        }

        // Replies to our own outgoing transfer.
        tx?.takeIf { it.id == frame.transferId }?.let { state ->
            val ctrl = try {
                Ctrl.decode(frame.payload)
            } catch (e: ProtocolError) {
                abortTx(state, AbortReason.INTEGRITY, Notice.SEND_ABORTED, "malformed reply")
                return
            }
            onTxReply(state, frame.seq, ctrl)
            return
        }

        if (frame.type == FrameType.CTRL) {
            val ctrl = Ctrl.decode(frame.payload)
            if (frame.seq == 0L) {
                rememberTransferId(frame.transferId)
                if (ctrl == Ctrl.HelloAck) {
                    acceptHelloAck()
                    return
                }
                if (handshake != Handshake.OK) throw ProtocolError(AbortReason.INTEGRITY, "Control frame before the session is confirmed")
                if (ctrl is Ctrl.Chat || ctrl is Ctrl.ChatAck) {
                    onChatFrame(frame.transferId, frame.seq, ctrl)
                    return
                }
                if (ctrl is Ctrl.Meta) {
                    onIncomingMeta(frame.transferId, ctrl)
                    return
                }
                throw ProtocolError(AbortReason.INTEGRITY, "Unexpected control frame")
            }
            if (frame.transferId == peerChatId) {
                onChatFrame(frame.transferId, frame.seq, ctrl)
                return
            }
            val state = rx?.takeIf { it.id == frame.transferId } ?: throw ProtocolError(AbortReason.INTEGRITY, "Control frame from an unknown transfer")
            when (ctrl) {
                is Ctrl.Done -> onIncomingDone(state, frame.seq, ctrl)
                is Ctrl.Abort -> onIncomingAbort(state, frame.seq, ctrl)
                else -> throw ProtocolError(AbortReason.INTEGRITY, "Unexpected control frame")
            }
            return
        }

        // DATA
        val state = rx?.takeIf { it.id == frame.transferId } ?: throw ProtocolError(AbortReason.INTEGRITY, "Chunk from an unknown transfer")
        onIncomingData(state, frame.seq, frame.payload)
    }

    private fun enqueueIncomingBinary(channel: ChannelRef, bytes: ByteArray) {
        // The server counted this frame against the room, whatever becomes of it here.
        if (channel.kind == ChannelKind.WS_RELAY) {
            if (channel.id != socket) return
            if (mode == Mode.WS) relayBudget?.charge(bytes.size.toLong())
        } else if (!isCurrentPc(channel.id)) {
            return
        }
        val isData = bytes.isNotEmpty() && (bytes[0].toInt() and 0xff) == FrameType.DATA.wire
        val full = if (isData) rxPendingData >= WireConstants.RX_MAX_PENDING_CHUNKS else rxPendingCtrl >= WireConstants.RX_MAX_PENDING_CTRL
        if (full) {
            // The sender ignored the credit window: drop the frame and abort once.
            if (!rxOverflowQueued) {
                rxOverflowQueued = true
                inbound.addLast(Overflow)
            }
            return
        }
        if (isData) rxPendingData += 1 else rxPendingCtrl += 1
        inbound.addLast(bytes)
    }

    private fun drainInbound() {
        while (!rxBusy && inbound.isNotEmpty()) {
            val item = inbound.removeFirst()
            if (item === Overflow) {
                rxOverflowQueued = false
                abortRx(AbortReason.INTEGRITY, Notice.RECEIVE_ABORTED, "the peer exceeded the credit window")
                continue
            }
            val bytes = item as ByteArray
            if (bytes.isNotEmpty() && (bytes[0].toInt() and 0xff) == FrameType.DATA.wire) rxPendingData -= 1 else rxPendingCtrl -= 1
            try {
                handleIncomingFrame(bytes)
            } catch (e: FrameError) {
                if (e is FrameError.Dropped) continue
                // A refused frame from another transfer does not end the active one.
                if (rx != null && e !is FrameError.Rejected) abortRx(AbortReason.INTEGRITY, Notice.RECEIVE_ABORTED, e.code)
                else notify(Notice.FRAME_REFUSED, e.code)
            } catch (e: ProtocolError) {
                if (rx != null) abortRx(e.reason, Notice.RECEIVE_ABORTED, e.reason.wire) else notify(Notice.FRAME_REFUSED, e.reason.wire)
            }
        }
    }

    // ------------------------------------------------------------------
    // Transfers: sending
    // ------------------------------------------------------------------

    private fun sendFile(file: RoomInput.SendFile) {
        val channel = activeChannel()
        val keys = session
        if (!isOpen(channel) || channel == null || tx != null || handshake != Handshake.OK || keys == null) return
        if (file.size < 0) return
        val chunkCount = (file.size + WireConstants.CHUNK_SIZE - 1) / WireConstants.CHUNK_SIZE
        if (chunkCount > WireConstants.MAX_FILE_CHUNKS) {
            notify(Notice.FILE_TOO_LARGE, file.name)
            return
        }
        // The relay cap is per room and counts both directions, so check what is left.
        if (mode == Mode.WS && relayBudget?.fits(file.size) == false) {
            notify(Notice.DOES_NOT_FIT_RELAY, file.name)
            return
        }

        val id = TransferId(randomBytes(WireConstants.TRANSFER_ID_LEN))
        transferCounter += 1
        val state = Tx(transferCounter, id, file.name, file.size, channel, keys, keys[id], TxWindow(chunkCount, clock()))
        tx = state
        arm(TimerId.TX_WATCHDOG, WireConstants.TX_WATCHDOG_MS)
        if (!sendCtrl(channel, id, 0, Ctrl.meta(file.name, file.size, file.mime, chunkCount), keys)) {
            abortTx(state, null, Notice.SEND_INTERRUPTED)
            return
        }
        notify(Notice.OFFERED, file.name)
    }

    // Moves the outgoing transfer on after anything that may have unblocked it.
    private fun advanceTx(state: Tx) {
        if (tx !== state) return
        val win = state.window
        when (win.phase) {
            TxWindow.Phase.OFFERED, TxWindow.Phase.AWAITING_COMPLETE -> Unit
            TxWindow.Phase.SENDING -> {
                if (state.readOutstanding || state.awaitingFlush) return
                if (!isOpen(state.channel)) {
                    abortTx(state, null, Notice.SEND_INTERRUPTED)
                    return
                }
                if (win.dataSent == win.chunks) {
                    val seq = win.doneSeq(clock())
                    sendCtrl(state.channel, state.id, seq, Ctrl.done(win.chunks, state.size), state.keys)
                    notify(Notice.AWAITING_CONFIRMATION, state.name)
                } else if (win.canSendData()) {
                    val offset = win.dataSent * WireConstants.CHUNK_SIZE
                    state.readOutstanding = true
                    out += RoomEffect.ReadChunk(state.token, offset, minOf(WireConstants.CHUNK_SIZE.toLong(), state.size - offset).toInt())
                }
            }
            TxWindow.Phase.DECLINED -> {
                notify(
                    when (win.declineReason) {
                        DeclineReason.SIZE -> Notice.NOT_SENT_TOO_LARGE_FOR_PEER
                        DeclineReason.BUSY -> Notice.NOT_SENT_PEER_BUSY
                        else -> Notice.NOT_SENT_DECLINED
                    },
                    state.name,
                )
                finishTx(state)
            }
            TxWindow.Phase.DELIVERED -> {
                notify(Notice.DELIVERED, state.name)
                finishTx(state)
            }
            TxWindow.Phase.ABORTED, TxWindow.Phase.UNCONFIRMED -> finishTx(state)
        }
    }

    private fun onChunkRead(token: Int, bytes: ByteArray) {
        val state = tx?.takeIf { it.token == token } ?: return
        state.readOutstanding = false
        val win = state.window
        if (win.phase != TxWindow.Phase.SENDING) {
            advanceTx(state)
            return
        }
        val offset = win.dataSent * WireConstants.CHUNK_SIZE
        val expected = minOf(WireConstants.CHUNK_SIZE.toLong(), state.size - offset).toInt()
        if (bytes.size != expected) {
            abortTx(state, AbortReason.DISK, Notice.SEND_ABORTED, "the file changed while it was being sent")
            return
        }
        val seq = win.nextDataSeq(clock())
        state.awaitingFlush = true
        if (!sendFrame(state.channel, FrameType.DATA, state.id, seq, bytes, state.key, notifyToken = state.token)) {
            abortTx(state, null, Notice.SEND_INTERRUPTED)
        }
    }

    private fun finishTx(state: Tx) {
        if (tx !== state) return
        cancel(TimerId.TX_WATCHDOG)
        markTransferEnded(state.id)
        tx = null
    }

    // Local abort of the outgoing transfer. reason null = transport gone, peer not told.
    private fun abortTx(state: Tx, reason: AbortReason?, notice: Notice, detail: String? = null) {
        val seq = state.window.abortSeq(reason ?: AbortReason.USER) ?: return
        if (reason != null) sendCtrl(state.channel, state.id, seq, Ctrl.abort(reason), state.keys)
        notify(notice, detail ?: state.name)
        finishTx(state)
    }

    private fun onTxReply(state: Tx, seq: Long, ctrl: Ctrl) {
        val kind = try {
            state.window.onReply(seq, ctrl, clock())
        } catch (e: ProtocolError) {
            abortTx(state, e.reason, Notice.SEND_ABORTED, "protocol violation")
            return
        }
        if (kind == TxWindow.Reply.ABORT) notify(Notice.RECEIVER_ABORTED, state.window.abortReason?.wire)
        if (kind == TxWindow.Reply.ACCEPT) notify(Notice.SENDING, state.name)
        advanceTx(state)
    }

    private fun onTxWatchdog() {
        val state = tx ?: return
        when (state.window.timedOut(clock())) {
            null -> arm(TimerId.TX_WATCHDOG, WireConstants.TX_WATCHDOG_MS)
            TxWindow.Timeout.UNCONFIRMED -> {
                state.window.endUnconfirmed()
                notify(Notice.SENT_UNCONFIRMED, state.name)
                finishTx(state)
            }
            TxWindow.Timeout.ACCEPT -> abortTx(state, AbortReason.TIMEOUT, Notice.SEND_ABORTED, "no answer from the peer within 5 minutes")
            TxWindow.Timeout.ACK -> abortTx(state, AbortReason.TIMEOUT, Notice.SEND_ABORTED, "the receiver stopped acknowledging chunks")
            TxWindow.Timeout.COMPLETE -> abortTx(state, AbortReason.TIMEOUT, Notice.SEND_ABORTED, "the receiver did not confirm the end of the file")
        }
    }

    public companion object {
        /** How long WebRTC may take to open the DataChannel before the relay is used instead. */
        public const val ICE_TIMEOUT_MS: Long = 12_000
    }
}
