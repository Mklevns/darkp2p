package com.darkp2p.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** Replays the traces recorded from the web client's RxStateMachine, TxWindow and helpers. */
class MachineVectorsTest {
    // What a step returned, in the form the trace records it.
    private fun render(value: Any?): JsonValue? = when (value) {
        null -> JsonValue.Null
        Unit -> null
        is Boolean -> JsonValue.Bool(value)
        is Long -> Json.num(value)
        is String -> Json.str(value)
        is JsonValue -> value
        else -> error("unexpected $value")
    }

    private fun expectStep(trace: String, index: Int, step: JsonValue.Obj, state: JsonValue?, run: () -> Any?) {
        val where = "$trace, step $index (${step.str("op")})"
        val error = step["error"]?.stringOrNull
        try {
            val ret = render(run())
            if (error != null) fail("$where: expected abort reason $error, got $ret")
            assertEquals(step["ret"], ret, where)
        } catch (e: ProtocolError) {
            assertEquals(error, e.reason.wire, where)
        }
        assertEquals(step["state"], state, "$where: state")
    }

    private fun doneOf(value: JsonValue) = Ctrl.of(value as JsonValue.Obj) as Ctrl.Done

    @Test
    fun `RxStateMachine traces`() {
        val traces = Vectors.list("rxTraces")
        assertTrue(traces.size >= 20)
        for (t in traces) {
            var m: RxStateMachine? = null
            fun state(): JsonValue? = m?.let {
                Json.obj(
                    "phase" to Json.str(it.phase.wire), "active" to JsonValue.Bool(it.active), "expectedSeq" to Json.num(it.expectedSeq),
                    "chunks" to Json.num(it.chunks), "bytes" to Json.num(it.bytes), "replySeq" to Json.num(it.replySeq),
                )
            }
            t.arr("steps").forEachIndexed { i, raw ->
                val step = raw as JsonValue.Obj
                val args = step.arr("args")
                val result: Result<Any?>
                result = runCatching<Any?> {
                    when (step.str("op")) {
                        "new" -> {
                            val meta = args[0] as JsonValue.Obj
                            m = RxStateMachine(ValidMeta(meta.str("name"), meta.long("size"), meta.str("mime"), meta.long("chunks")))
                            Unit
                        }
                        "accept" -> m!!.accept()
                        "decline" -> m!!.decline()
                        "complete" -> m!!.complete()
                        "nextReplySeq" -> m!!.nextReplySeq()
                        "onData" -> Json.obj("ack" to (m!!.onData(args[0].long(), args[1].long().toInt())?.let(Json::num) ?: JsonValue.Null))
                        "onDone" -> Json.obj("ack" to Json.num(m!!.onDone(args[0].long(), doneOf(args[1]))))
                        "onAbort" -> m!!.onAbort(args[0].long())
                        "abort" -> m!!.abort()
                        else -> error("unknown op")
                    }
                }
                expectStep(t.str("name"), i, step, state()) { result.getOrThrow() }
            }
        }
    }

    @Test
    fun `TxWindow traces`() {
        val traces = Vectors.list("txTraces")
        assertTrue(traces.size >= 20)
        for (t in traces) {
            var w: TxWindow? = null
            fun state(): JsonValue? = w?.let {
                Json.obj(
                    "phase" to Json.str(it.phase.wire), "active" to JsonValue.Bool(it.active), "inFlight" to Json.num(it.inFlight),
                    "dataSent" to Json.num(it.dataSent), "acked" to Json.num(it.acked), "forwardSeq" to Json.num(it.forwardSeq),
                    "expectedReplySeq" to Json.num(it.expectedReplySeq), "since" to Json.num(it.since),
                    "declineReason" to (it.declineReason?.wire?.let(Json::str) ?: JsonValue.Null),
                    "abortReason" to (it.abortReason?.wire?.let(Json::str) ?: JsonValue.Null),
                    "doneAcked" to JsonValue.Bool(it.doneAcked),
                )
            }
            t.arr("steps").forEachIndexed { i, raw ->
                val step = raw as JsonValue.Obj
                val args = step.arr("args")
                val result = runCatching<Any?> {
                    when (step.str("op")) {
                        "new" -> {
                            val o = args[0] as JsonValue.Obj
                            // A fractional chunk count cannot be expressed in Kotlin's Long: the caller rejects it.
                            val chunks = o["chunks"]!!.safeIntegerOrNull ?: throw ProtocolError(AbortReason.INTEGRITY, "Invalid chunk count")
                            w = TxWindow(
                                chunks, o.long("now"),
                                window = o["window"]?.long()?.toInt() ?: WireConstants.TX_WINDOW,
                                acceptTimeoutMs = o["acceptTimeoutMs"]?.long() ?: WireConstants.ACCEPT_TIMEOUT_MS,
                                ackStallMs = o["ackStallMs"]?.long() ?: WireConstants.ACK_STALL_MS,
                                completeTimeoutMs = o["completeTimeoutMs"]?.long() ?: WireConstants.COMPLETE_TIMEOUT_MS,
                            )
                            Unit
                        }
                        "canSendData" -> w!!.canSendData()
                        "nextDataSeq" -> w!!.nextDataSeq(args[0].long())
                        "doneSeq" -> w!!.doneSeq(args[0].long())
                        "abortSeq" -> w!!.abortSeq(AbortReason.fromWire(args[0].stringOrNull)!!)
                        "onReply" -> w!!.onReply(args[0].long(), (args[1] as? JsonValue.Obj)?.let(Ctrl::of), args[2].long()).wire
                        "timedOut" -> w!!.timedOut(args[0].long())?.wire
                        "endUnconfirmed" -> w!!.endUnconfirmed()
                        else -> error("unknown op")
                    }
                }
                expectStep(t.str("name"), i, step, state()) { result.getOrThrow() }
            }
        }
    }

    @Test
    fun `meta validation`() {
        val vectors = Vectors.list("meta")
        assertTrue(vectors.size >= 30)
        for (v in vectors) {
            val json = v.str("json")
            val expected = v["meta"] as? JsonValue.Obj
            val result = runCatching {
                val ctrl = Ctrl.decode(json.toByteArray())
                // parseMeta: "Not a meta frame" for any other type.
                Ctrl.parseMeta(ctrl as? Ctrl.Meta ?: throw ProtocolError(AbortReason.INTEGRITY, "Not a meta frame"))
            }
            if (expected != null) {
                assertEquals(ValidMeta(expected.str("name"), expected.long("size"), expected.str("mime"), expected.long("chunks")), result.getOrThrow(), json)
            } else {
                assertEquals(v.str("error"), (result.exceptionOrNull() as? ProtocolError)?.reason?.wire, json)
            }
        }
    }

    @Test
    fun `relay budget`() {
        val group = Vectors.obj("relayBudget")
        for (v in group.arr("wireBytes").map { it as JsonValue.Obj }) {
            assertEquals(v.long("wireBytes"), RelayBudget.wireBytes(v.long("size"), v.int("chunkSize")), "size ${v.long("size")}")
        }
        for (run in group.arr("runs").map { it as JsonValue.Obj }) {
            val name = run.str("name")
            val init = run.obj("init")
            val budget = RelayBudget.of(init["cap"]?.safeIntegerOrNull, init["remaining"]?.safeIntegerOrNull)
            if (run["error"] != null) {
                assertNull(budget, name)
                continue
            }
            assertNotNull(budget, name)
            for (step in run.arr("steps").map { it as JsonValue.Obj }) {
                val args = step.arr("args")
                when (step.str("op")) {
                    // A fractional byte count cannot reach Kotlin's Long API; the page ignores it.
                    "charge" -> args[0].safeIntegerOrNull?.let(budget::charge)
                    "fits" -> assertEquals(step.bool("ret"), budget.fits(args[0].long(), args[1].long().toInt()), name)
                }
                assertEquals(step.long("remaining"), budget.remaining, name)
            }
        }
        assertEquals(RelayBudget.FALLBACK_CAP_FREE, RelayBudget.fromReady(null, null, pro = false).remaining)
        assertEquals(RelayBudget.FALLBACK_CAP_PRO, RelayBudget.fromReady(10, 11, pro = true).cap)
        assertEquals(7, RelayBudget.fromReady(10, 7, pro = true).remaining)
    }

    @Test
    fun `reconnect delay`() {
        val vectors = Vectors.list("reconnectDelay")
        assertTrue(vectors.size >= 80)
        for (v in vectors) {
            // A fractional attempt count is not an integer to the page, which then uses 0.
            val attempt = v["attempt"]!!.safeIntegerOrNull?.toInt() ?: 0
            val rand = (v["rand"] as JsonValue.Num).value
            assertEquals(v.long("delayMs"), Backoff.reconnectDelay(attempt, rand), "attempt $attempt rand $rand")
        }
    }

    @Test
    fun `candidates that expose an address`() {
        val vectors = Vectors.list("candidates")
        assertTrue(vectors.size >= 25)
        for (v in vectors) {
            val c = v["candidate"] as? JsonValue.Obj
            val exposes = CandidatePolicy.exposesIp(
                c?.get("candidate")?.stringOrNull, c?.get("type")?.stringOrNull, c?.get("address")?.stringOrNull, c?.get("relatedAddress")?.stringOrNull,
            )
            assertEquals(v.bool("exposesIp"), exposes, Json.stringify(v["candidate"]!!))
        }
    }

    @Test
    fun `links parse as the page parses them`() {
        val vectors = Vectors.list("links")
        assertTrue(vectors.size >= 15)
        for (v in vectors) {
            val name = v.str("name")
            // location.hash.slice(1)
            val parsed = RoomLink.parseFragment(v.str("hash").drop(1))
            if (!v.bool("valid")) {
                assertNull(parsed, name)
                continue
            }
            assertNotNull(parsed, name)
            assertEquals(v.str("roomId"), parsed.first, name)
            assertEquals(v.str("fragment"), RoomLink("https://darkp2p.com", parsed.first, parsed.second).fragment, name)
            assertEquals(Base64Url.encode(v.hex("key")), parsed.second.toBase64Url(), name)
        }
    }
}
