package livekit

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.store.KeyStore
import de.connect2x.trixnity.client.store.StoreTransactionManager
import de.connect2x.trixnity.crypto.olm.OlmEventHandler
import de.connect2x.trixnity.crypto.olm.OlmEncryptionService
import de.connect2x.trixnity.crypto.olm.OlmStore
import de.connect2x.trixnity.core.model.UserId
import de.connect2x.trixnity.core.model.events.ClientEvent
import de.connect2x.trixnity.core.model.events.UnknownEventContent
import de.connect2x.trixnity.core.model.events.block.EventContentBlocks
import de.connect2x.trixnity.core.model.events.m.room.EncryptedToDeviceEventContent
import de.connect2x.trixnity.core.model.keys.KeyAlgorithm
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import repository.ConfirmedLeaveLedger

/** How long after a self-nudge the call UI shows calm "repairing" copy instead
 *  of alarming reconnect/waiting states — covers the 2.5s membership gap plus
 *  peer reaction time. Read by CallManager; file-scope so the private companion
 *  of [CallMediaKeyExchange] stays encapsulated. */
const val NUDGE_RECOVERY_VISIBLE_MS = 20_000L

/**
 * matrixRTC E2EE media-key exchange for encrypted-room calls, mirroring Element
 * Call's deployed protocol: each DEVICE generates a 16-byte key (index starting
 * at 0), distributes it olm-wrapped via to-device `io.element.call.encryption_keys`
 * events, registers it locally in the LiveKit KeyProvider under its LiveKit
 * identity, and reacts to membership changes (leaver -> rotate, joiner -> resend
 * while fresh). Received keys are fed into per-PARTICIPANT KeyProvider slots
 * keyed `${userId}:${deviceId}` so the native frame cryptors can decrypt peers.
 */
class CallMediaKeyExchange(
    private val client: MatrixClient,
    private val scope: CoroutineScope,
    private val roomId: String,
    private val ownUserId: String,
    private val ownDeviceId: String,
    private val ownLivekitIdentity: String,
    private val devicesFlow: Flow<Set<CallDevice>>,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /**
     * Recovery lever for the observed regression where a peer's client never
     * reacts to our join (throttled background tab / missed sync) and its media
     * key never arrives — RX stays silent forever. Element clients only act on
     * membership DIFFS (leaver -> rotate+redistribute, joiner -> resend), so a
     * clear-then-republish cycle of OUR OWN membership event forces every live
     * peer through both paths without touching the LiveKit session.
     */
    private val nudgeMembership: (suspend () -> Unit)? = null
) {
    private val ring = CallMediaKeys.OwnKeyRing(nowMs)
    private val receivedFilter = CallMediaKeys.OutdatedKeyFilter()
    private val mutex = Mutex()
    private var knownTargets: Set<CallDevice>? = null
    private var unsubscribe: (() -> Unit)? = null
    private var collectJob: Job? = null
    private var retryWatchJob: Job? = null
    private var nudgeWatchJob: Job? = null
    private var settleNudgeJob: Job? = null
    private val pendingRetryDevices = java.util.Collections.synchronizedSet(mutableSetOf<CallDevice>())
    private val directQueriedUsers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // Per-device attempt counter for the olm key re-query, and the last time each
    // user was queried. Bounded and backed off -- see the retry watcher.
    private val keyFetchAttempts = java.util.Collections.synchronizedMap(mutableMapOf<String, Int>())
    private val lastKeyFetchMs = java.util.Collections.synchronizedMap(mutableMapOf<String, Long>())
    // Devices abandoned because they have no published keys. Recorded so a later
    // sweep can exclude them instead of re-deriving the same conclusion every
    // 2.5 seconds.
    private val exhaustedDevices = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val keyStore: KeyStore? =
        runCatching { client.di.get<KeyStore>(KeyStore::class) }.getOrNull()
            .also { if (it == null) println("ERROR [CallMediaKeyExchange] KeyStore unavailable in DI") }
    /**
 * Mark [ids] outdated so Trixnity refetches, verifies and stores their device keys.
 *
 * ## Why this wrapper exists
 *
 * Trixnity 5.9.2 changed the shape of `KeyStore.updateOutdatedKeys`:
 *
 * ```
 * // 5.4.0
 * suspend fun updateOutdatedKeys(lambda: suspend (Set<UserId>) -> Set<UserId>)
 * // 5.9.2 -- on KeyStore, taking the transaction; StoreWriteTransaction does not
 * //          expose it
 * fun updateOutdatedKeys(txn: StoreWriteTransaction, lambda: (Set<UserId>) -> Set<UserId>)
 * ```
 *
 * It is no longer `suspend`, it no longer supplies its own transaction, and its
 * lambda is no longer `suspend`. So the caller must now open a write transaction
 * and hand it to `KeyStore`.
 *
 * Both call sites go through here so that exists once. This is the single writer
 * for "ask Trixnity to verify these peers' keys" -- see the security note on
 * [fetchAndStorePeerDeviceKeys] for why it must not be a direct store write.
 */
private suspend fun markKeysOutdated(ids: Set<UserId>) {
    if (ids.isEmpty()) return
    val tm = runCatching {
        client.di.get<StoreTransactionManager>(StoreTransactionManager::class)
    }.getOrNull()
    if (tm == null) {
        util.OrbitLog.w(
            "CallMediaKeyExchange",
            "StoreTransactionManager unavailable in DI; cannot request device keys for $ids"
        )
        return
    }
    val keys = runCatching { client.di.get<KeyStore>(KeyStore::class) }.getOrNull()
    if (keys == null) {
        util.OrbitLog.w("CallMediaKeyExchange", "KeyStore unavailable in DI; cannot request device keys")
        return
    }
    tm.writeTransaction {
        // 5.9.2 made the transaction a context receiver rather than a parameter,
        // so `writeTransaction` supplies it; there is nothing to pass.
        keys.updateOutdatedKeys { current ->
            val merged = current + ids
            util.OrbitLog.d(
                "CallMediaKeyExchange",
                "marked outdated: +${ids.size} ids, ${current.size} -> ${merged.size} for $ids"
            )
            merged
        }
    }
    // Read back through the same public API the distribute path uses, so this
    // proves the write committed rather than merely that the lambda ran.
    util.OrbitLog.d(
        "CallMediaKeyExchange",
        "outdated now = " + runCatching {
            val out = keys.getOutdatedKeys()
            "${out.size} ids"
        }.getOrElse { "ERR ${it.message}" }
    )
}

private val olmStore: OlmStore? =
        runCatching { client.di.get<OlmStore>(OlmStore::class) }.getOrNull()
    private val keysRequestedFor = mutableSetOf<String>()
    private var syncProbeUnsubscribe: (() -> Unit)? = null
    private val seenToDeviceTypes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile private var rawOlmToDeviceSeen = 0
    @Volatile private var syncBatchesSeen = 0
    @Volatile private var stopped = false

    // LiveKit identities whose media key we actually received this call — the
    // nudge watcher compares the live roster against this to detect peers that
    // never sent us a key.
    private val receivedPeerIdentities = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    @Volatile private var nudgesFired = 0
    @Volatile private var lastNudgeMs = 0L
    @Volatile private var settleNudgeFired = false
    // Last ghost count reported by nudgeMissingPeers, so the (otherwise
    // identical) line is emitted only when the number actually moves.
    @Volatile private var lastLoggedGhostCount = -1

    // Per-device timestamp of the last reply-on-receive resend (rate/loop guard).
    private val lastReplyToDeviceMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Per-user timestamp of the last post-encryption-failure device-key refresh.
    private val lastDeviceKeyRefreshMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // Us→them convergence ledger + roster memory. The sweeper re-sends our
    // current key to any live device whose CONFIRMED delivery index lags
    // ring.currentIndex — heals peers that diff-only distribution misses
    // (transient roster leavers like the L18225 flicker that orphaned a peer
    // for two sessions, mid-call client restarts, deferred devices the retry
    // watcher never picked back up).
    private val deliveredIndex = DeliveredKeyIndex()
    private val knownDevices = java.util.concurrent.ConcurrentHashMap<String, CallDevice>()
    private val deviceLastSeenMs = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private var convergeJob: Job? = null
    private var latecomerWatchJob: Job? = null
    // First-seen stamps for LiveKit participants; drives the latecomer nudge
    // that rescues RX when a joiner's client ignores plain joins (throttled
    // Element clients only act on membership DIFFS).
    private val latecomers = LatecomerTracker()
    private val startTimeMs: Long = nowMs()

    fun start() {
        // FIRST key is distributed and registered locally immediately, without
        // the use-key delay that only applies to CHANGED keys.
        deliverNativeMediaKey(ownLivekitIdentity, ring.currentKey, ring.currentIndex)
        println(
            "DEBUG [CallMediaKeyExchange] started room=$roomId own=$ownLivekitIdentity " +
                "keyIndex=${ring.currentIndex}"
        )
        unsubscribe = subscribeReceive()
        // Independent probe on the RAW sync stream: counts olm to-device events
        // as the server delivers them, before any olm processing. Distinguishes
        // "peers never sent us keys / server never delivered" from
        // "delivered but trixnity could not decrypt or dispatch".
        syncProbeUnsubscribe = runCatching {
            client.api.sync.subscribe { events ->
                syncBatchesSeen++
                if (syncBatchesSeen % 25 == 1) {
                    println(
                        "DEBUG [CallMediaKeyExchange] sync probe alive batch=$syncBatchesSeen " +
                            "events=${events.size} olmSoFar=$rawOlmToDeviceSeen"
                    )
                }
                for (event in events) {
                    val toDevice = event as? ClientEvent.ToDeviceEvent<*> ?: continue
                    val typeName = toDevice.content?.let { it::class.simpleName } ?: "null"
                    if (seenToDeviceTypes.add(typeName)) {
                        println(
                            "DEBUG [CallMediaKeyExchange] to-device channel first-seen type=$typeName " +
                                "from=${runCatching { toDevice.sender.full }.getOrDefault("?")}"
                        )
                    }
                    if (toDevice.content !is EncryptedToDeviceEventContent.OlmEncryptedToDeviceEventContent) continue
                    rawOlmToDeviceSeen++
                    println(
                        "DEBUG [CallMediaKeyExchange] raw olm to-device #$rawOlmToDeviceSeen " +
                            "from=${runCatching { toDevice.sender.full }.getOrDefault("?")}"
                    )
                }
            }
        }.onFailure { println("WARN [CallMediaKeyExchange] raw sync probe unavailable: ${it.message}") }
            .getOrNull()
        // /keys/claim CONSUMES one of our server-side one-time keys and trixnity
        // replenishment is unverified (pool showed a single spare), so the probe
        // only runs when inbound olm is genuinely absent after a grace window —
        // exactly the broken scenario it exists to diagnose.
        scope.launch {
            delay(DIAG_ZERO_INBOUND_GRACE_MS)
            if (!stopped && rawOlmToDeviceSeen == 0) {
                diagnoseOwnKeyServerState()
            } else {
                println(
                    "DEBUG [CallMediaKeyExchange] DIAG skipped " +
                        "(inbound olm seen=$rawOlmToDeviceSeen stopped=$stopped)"
                )
            }
        }
        // RX-dead recovery: if a live peer never delivered its media key, force
        // a membership leave+rejoin cycle so every peer re-runs its leaver
        // (rotate+redistribute) and joiner (resend) key paths.
        nudgeWatchJob = scope.launch {
            while (!stopped) {
                delay(NUDGE_WATCH_INTERVAL_MS)
                if (stopped) break
                val targets = knownTargets ?: continue
                if (targets.isEmpty()) continue
                val startMs = this@CallMediaKeyExchange.startTimeMs
                val now = nowMs()
                if (now - startMs < NUDGE_GRACE_MS) continue
                val lastNudge = lastNudgeMs
                if (lastNudge != 0L && now - lastNudge < NUDGE_COOLDOWN_MS) continue
                if (nudgesFired >= NUDGE_MAX_PER_CALL) break
                val missing = nudgeMissingPeers(targets)
                if (missing.isEmpty()) continue
                val callback = nudgeMembership
                if (callback == null) {
                    println(
                        "WARN [CallMediaKeyExchange] missing peer keys ${missing.map { "${it.userId}:${it.deviceId}" }} " +
                            "but no membership nudge available"
                    )
                    break
                }
                nudgesFired += 1
                lastNudgeMs = now
                println(
                    "WARN [CallMediaKeyExchange] no key from ${missing.size} live peer(s) " +
                        "(${missing.map { "${it.userId}:${it.deviceId}" }}); nudging membership " +
                        "leave+rejoin attempt=$nudgesFired/$NUDGE_MAX_PER_CALL"
                )
                runCatching { callback() }
                    .onFailure { println("WARN [CallMediaKeyExchange] membership nudge failed: ${it.message}") }
            }
        }
        // Early "settle" nudge: Element peers only act on membership DIFFS and a
        // throttled/backgrounded peer reliably ignores our fresh join while
        // answering a leave+rejoin cycle within seconds (proven across three
        // live sessions). If NO peer key has arrived shortly after joining, run
        // the cycle up front instead of staying deaf until the 45s watcher
        // grace. Stamps lastNudgeMs so the reactive watcher cannot double-fire
        // inside its cooldown; keeps its own one-shot slot so the reactive
        // budget stays available for later-joining silent peers.
        settleNudgeJob = scope.launch {
            delay(NUDGE_SETTLE_DELAY_MS)
            if (stopped || settleNudgeFired) return@launch
            val targets = knownTargets ?: return@launch
            if (targets.isEmpty()) return@launch
            if (receivedPeerIdentities.isNotEmpty()) {
                println(
                    "DEBUG [CallMediaKeyExchange] settle check: ${receivedPeerIdentities.size} peer key(s) " +
                        "already received — no settle nudge needed"
                )
                return@launch
            }
            val livePeers = nudgeMissingPeers(targets)
            if (livePeers.isEmpty()) {
                println(
                    "DEBUG [CallMediaKeyExchange] settle check: ${targets.size} roster device(s) but none " +
                        "present in the LiveKit room — no settle nudge needed"
                )
                return@launch
            }
            val callback = nudgeMembership ?: return@launch
            settleNudgeFired = true
            lastNudgeMs = nowMs()
            println(
                "WARN [CallMediaKeyExchange] settle nudge: no peer key ${NUDGE_SETTLE_DELAY_MS / 1000}s after join " +
                    "(${livePeers.size} live peer(s)); forcing membership leave+rejoin early"
            )
            runCatching { callback() }
                .onFailure { println("WARN [CallMediaKeyExchange] settle nudge failed: ${it.message}") }
        }
        // Latecomer nudge: the settle nudge only covers "ZERO keys at join+5s".
        // A peer joining LATER — while keys already flow — whose throttled
        // client ignores our membership diff stays RX-dead for us until the
        // 45s reactive grace. Track each LiveKit participant's first-seen time
        // and fire the leave+rejoin cycle ~8s after a participant appears
        // without ever having sent us a key. Shares the reactive watcher's
        // budget (NUDGE_MAX_PER_CALL) and cooldown so total membership churn
        // per call stays bounded.
        latecomerWatchJob = scope.launch {
            while (!stopped) {
                delay(LATECOMER_WATCH_INTERVAL_MS)
                if (stopped) break
                val livekitIds = runCatching {
                    LiveKitRemoteVideoFrameStore.remoteParticipants.value
                }.getOrNull().orEmpty()
                if (livekitIds.isEmpty()) continue
                val now = nowMs()
                latecomers.observe(livekitIds, ownLivekitIdentity, now) { newIdentity ->
                    println("DEBUG [CallMediaKeyExchange] new/rejoined LiveKit participant: $newIdentity -> clearing delivery cache")
                    deliveredIndex.invalidate(newIdentity)
                    receivedPeerIdentities.remove(newIdentity)
                    val dev = callDeviceFromParticipantIdentity(newIdentity)
                    if (dev != null) {
                        scope.launch {
                            val (payload, keyIndex) = mutex.withLock { currentPayload() to ring.currentIndex }
                            distribute(payload, setOf(dev), keyIndex)
                        }
                    }
                }
                val overdue = latecomers.overdue(receivedPeerIdentities, now, LATECOMER_GRACE_MS)
                if (overdue.isEmpty()) continue
                val lastNudge = lastNudgeMs
                if (lastNudge != 0L && now - lastNudge < NUDGE_COOLDOWN_MS) continue
                if (nudgesFired >= NUDGE_MAX_PER_CALL) break
                val callback = nudgeMembership
                if (callback == null) {
                    println(
                        "WARN [CallMediaKeyExchange] latecomer(s) ${overdue} keyless but no " +
                            "membership nudge available"
                    )
                    break
                }
                nudgesFired += 1
                lastNudgeMs = now
                println(
                    "WARN [CallMediaKeyExchange] latecomer nudge: ${overdue.size} participant(s) " +
                        "$overdue present >=${LATECOMER_GRACE_MS / 1000}s without sending a key; " +
                        "refreshing membership attempt=$nudgesFired/$NUDGE_MAX_PER_CALL"
                )
                // Re-deliver the CURRENT key to each overdue peer BEFORE the
                // membership nudge, and never rotate on suspicion alone.
                //
                // This path used to go straight to the membership cycle, and that is
                // what desynced a peer who had simply dropped their connection.
                //
                // Run 66, hellishsaa cut the call and rejoined. A hard cut publishes
                // NO leave event, so his m.call.member stays on the server as a
                // ghost (docs/planning/ESCAPE_FROM_HELL.md H8-V2) -- and a ghost is
                // indistinguishable from a latecomer: present, no key. The nudge
                // fired, the membership cycle saw a leaver, and rotated:
                //
                //   ORBIT SENDS index=0
                //   HE INSTALLS keyIndex=3          (correct, from before the cut)
                //   remote participant disconnected
                //   remote participant connected
                //   ORBIT SENDS index=1             (rotated)
                //   HE INSTALLS keyIndex=0          (never caught up)
                //
                // Orbit then encrypted with index 1 while he decrypted with 0. Every
                // frame arrived and every frame was undecodable, so he heard nothing
                // while Orbit's own counters -- TXSTAT sending, mic.read=500, no
                // jitter -- all read perfectly healthy. A ring lit up, because that
                // is measured pre-decryption.
                //
                // The latecomer rule ("present without a key") is a statement about
                // what we have NOT heard from them. It is never evidence that
                // anything about OUR key should change. Rotating on that inference
                // breaks every peer already in sync to fix one that may not be.
                //
                // Re-delivering the current index cannot desync anyone: it is the key
                // we are already using, so a peer receiving it lands on the index we
                // are transmitting with.
                val (resendPayload, resendIndex) = mutex.withLock { currentPayload() to ring.currentIndex }
                val resendTargets = overdue.mapNotNull { identity ->
                    callDeviceFromParticipantIdentity(identity)
                }.toSet()
                if (resendTargets.isNotEmpty()) {
                    println(
                        "DEBUG [CallMediaKeyExchange] latecomer re-delivering CURRENT key " +
                            "index=$resendIndex to ${resendTargets.size} keyless peer(s) " +
                            "(no rotation)"
                    )
                    distribute(resendPayload, resendTargets, resendIndex)
                } else {
                    println(
                        "WARN [CallMediaKeyExchange] latecomer(s) $overdue could not be " +
                            "resolved to a device; relying on the membership nudge alone"
                    )
                }
                runCatching { callback() }
                    .onFailure { println("WARN [CallMediaKeyExchange] latecomer nudge failed: ${it.message}") }
            }
        }
        // Convergence sweeper: diff-only distribution is structurally incomplete
        // in BOTH directions — a device absent from the roster at rotation time
        // never learns the new index unless it happens to send us a key (reply
        // path), so it decrypts NOTHING of ours while our RX of it works fine.
        // Every CONVERGE_INTERVAL_MS, re-send the current key to every live
        // device whose CONFIRMED delivery index lags ring.currentIndex. Live
        // = seen in the matrixRTC roster recently OR currently connected in the
        // LiveKit room (the LiveKit view rescues peers whose membership event
        // dropped out of the sticky-roster while they are still talking).
        convergeJob = scope.launch {
            while (!stopped) {
                delay(CONVERGE_INTERVAL_MS)
                if (stopped) break
                // Snapshot payload+index atomically so a concurrent rotate
                // cannot split the pair across the sweep.
                val (payload, keyIndex) = mutex.withLock { currentPayload() to ring.currentIndex }
                val now = nowMs()
                val candidates = buildMap<String, CallDevice> {
                    for ((id, dev) in knownDevices) {
                        if ((deviceLastSeenMs[id] ?: 0L) >= now - SWEEP_DEVICE_TTL_MS) put(id, dev)
                    }
                    val livekitDevices = runCatching {
                        LiveKitRemoteVideoFrameStore.remoteParticipants.value
                    }.getOrNull().orEmpty()
                    for (identity in livekitDevices) {
                        val dev = callDeviceFromParticipantIdentity(identity) ?: continue
                        if (dev.userId == ownUserId && dev.deviceId == ownDeviceId) continue
                        put(CallMediaKeys.participantIdentity(dev.userId, dev.deviceId), dev)
                    }
                }
                val underdelivered = candidates.filterKeys { id ->
                    // A device we have already given up on stays given up on.
                    //
                    // Without this the sweep re-derives "underdelivered" for an
                    // unencryptable device every 2.5s for the whole call, logs it,
                    // hands it to `distribute`, which defers it straight back into
                    // `pendingRetryDevices`, and the retry watcher then
                    // re-partitions it -- all to reach the same answer, several
                    // times per cycle, for the length of the call.
                    if (id in exhaustedDevices) return@filterKeys false
                    deliveredIndex.isUnderdelivered(id, keyIndex)
                }
                if (underdelivered.isEmpty()) continue
                println(
                    "DEBUG [CallMediaKeyExchange] convergence sweep: resending key index=$keyIndex to " +
                        "${underdelivered.size} underdelivered device(s) " +
                        "(${underdelivered.values.map { "${it.userId}:${it.deviceId}" }})"
                )
                distribute(payload, underdelivered.values.toSet(), keyIndex)
            }
        }
        // Devices whose keys were not yet stored when a distribution ran get
        // retried here — WITHOUT blocking membership processing (the round-8
        // regression: an inline 10s poll delayed the initial seed until it
        // collided with call teardown).
        retryWatchJob = scope.launch {
            while (!stopped) {
                delay(RETRY_WATCH_INTERVAL_MS)
                if (stopped) break
                val snapshot = pendingRetryDevices.toList()
                if (snapshot.isEmpty()) continue
                val now = nowMs()
                // Give up on a device that has been asked for repeatedly and has
                // never published a key.
                //
                // The convergence sweeper is a doorman, not a queue: it re-sends to
                // anything whose CONFIRMED delivery index lags the ring, and a
                // device we cannot encrypt to is never marked delivered. So a peer
                // that has no published olm keys -- a stale MatrixRTC membership
                // record for a device that logged out months ago, or an identity
                // the server will never answer for -- is re-attempted on EVERY
                // sweep, for the entire call, forever.
                //
                // A live run showed exactly that, every 10 s, all call:
                //
                //   convergence sweep: resending key index=0 to 1
                //     underdelivered device(s) ([@nfak_ism:matrix.org:ZVK65HTOZW])
                //   deferring 1 device(s) without stored keys:
                //     [@nfak_ism:matrix.org:ZVK65HTOZW]
                //
                // -- the same device, deferred immediately after being retried.
                //
                // Worse, the key query that could fix it was one-shot:
                // `directQueriedUsers.add(it)` returns false on the second call, so
                // each user's device keys were fetched exactly once for the whole
                // call. If that query missed, nothing ever re-asked.
                val (exhausted, stillTrying) = snapshot.partition { dev ->
                    val id = CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
                    val attempts = (keyFetchAttempts[id] ?: 0) + 1
                    keyFetchAttempts[id] = attempts
                    attempts > KEY_FETCH_MAX_ATTEMPTS
                }
                if (exhausted.isNotEmpty()) {
                    pendingRetryDevices.removeAll(exhausted.toSet())
                    exhausted.forEach { dev ->
                        exhaustedDevices.add(
                            CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
                        )
                    }
                    println(
                        "WARN [CallMediaKeyExchange] giving up on ${exhausted.size} device(s) " +
                            "with no published olm keys after $KEY_FETCH_MAX_ATTEMPTS queries: " +
                            "${exhausted.map { "${it.userId}:${it.deviceId}" }}. " +
                            "This is a stale MatrixRTC membership record, not a live peer; " +
                            "they are excluded from key exchange for the rest of this call."
                    )
                }
                val live = stillTrying
                if (live.isEmpty()) continue

                // Re-query, with backoff, instead of once per user per call.
                //
                // A device CAN publish its keys after we first asked -- it may have
                // been mid-login, or its identity keys may have just rotated -- so
                // one attempt is too few. Bounded and spaced, so this cannot become
                // the server-hammering loop the delivery-failure path avoids.
                val dueForQuery = live.map { it.userId }.distinct().filter { userId ->
                    val last = lastKeyFetchMs[userId] ?: 0L
                    if (last != 0L && now - last < KEY_FETCH_RETRY_INTERVAL_MS) return@filter false
                    lastKeyFetchMs[userId] = now
                    true
                }
                if (dueForQuery.isNotEmpty()) fetchAndStorePeerDeviceKeys(dueForQuery)
                val ready = live.filter { dev ->
                    // Same rule as distribute(): a cancelled scope is not evidence
                    // about a peer's keys. See the long note there.
                    try {
                        olmStore?.getDeviceKeys(UserId(dev.userId))?.containsKey(dev.deviceId) == true
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        util.OrbitLog.w(
                            "CallMediaKeyExchange",
                            "getDeviceKeys failed for ${dev.userId}: ${e.message}"
                        )
                        false
                    }
                }
                if (ready.isNotEmpty()) {
                    println(
                        "DEBUG [CallMediaKeyExchange] retry distributing to ${ready.size} " +
                            "newly-ready device(s)"
                    )
                    pendingRetryDevices.removeAll(ready.toSet())
                    val (payload, keyIndex) = mutex.withLock { currentPayload() to ring.currentIndex }
                    distribute(payload, ready.toSet(), keyIndex)
                }
            }
        }
        collectJob = scope.launch {
            devicesFlow.collect { devices -> onDevices(devices) }
        }
    }

    /**
     * Proves whether peers can address our device AT ALL: /keys/query must list
     * our deviceId (else Element skips us when building key recipients), and
     * /keys/claim must return a signed_curve25519 one-time key (else nobody can
     * START an olm session to us, so no key event ever arrives — the round-7
     * symptom where zero inbound olm to-device events were seen all call).
     */
    private suspend fun diagnoseOwnKeyServerState() {
        runCatching {
            client.api.key.getKeys(mapOf(UserId(ownUserId) to emptySet<String>()), 10_000L).getOrThrow()
        }.onSuccess { response ->
            val mine = response.deviceKeys?.get(UserId(ownUserId)).orEmpty()
            println(
                "DIAG [CallMediaKeyExchange] /keys/query own devices=${mine.keys} " +
                    "oursPresent=${mine.containsKey(ownDeviceId)}"
            )
            if (!mine.containsKey(ownDeviceId)) {
                println("ERROR [CallMediaKeyExchange] own device keys NOT on server — peers cannot address us via olm")
            }
        }.onFailure { println("WARN [CallMediaKeyExchange] /keys/query own failed: ${it.message}") }
        runCatching {
            client.api.key.claimKeys(
                mapOf(UserId(ownUserId) to mapOf(ownDeviceId to KeyAlgorithm.SignedCurve25519)),
                10_000L
            ).getOrThrow()
        }.onSuccess { response ->
            // Response shape: oneTimeKeys[user] = Map<deviceId, Keys> where Keys is
            // a Set<Key>; each Key's fullId looks like "signed_curve25519:<id>".
            val claimedDevices = response.oneTimeKeys[UserId(ownUserId)].orEmpty()
            val otkKeys = claimedDevices[ownDeviceId]?.keys.orEmpty()
                .filter { it.algorithm == KeyAlgorithm.SignedCurve25519 }
            println(
                "DIAG [CallMediaKeyExchange] /keys/claim own devices=${claimedDevices.keys} " +
                    "otkCount=${otkKeys.size} fullIds=${otkKeys.map { it.fullId }}"
            )
            if (otkKeys.isEmpty()) {
                println("ERROR [CallMediaKeyExchange] no one-time key claimable for own device — peers cannot start olm sessions to us")
            }
        }.onFailure { println("WARN [CallMediaKeyExchange] /keys/claim own failed: ${it.message}") }
    }

    /** UI hook: wall-clock stamp of our last self-nudge (leave+rejoin); 0 = never fired. */
    fun lastSelfNudgeAtMs(): Long = lastNudgeMs

    fun rearmOwnKey() {
        deliverNativeMediaKey(ownLivekitIdentity, ring.currentKey, ring.currentIndex)
    }

    fun stop() {
        stopped = true
        unsubscribe?.invoke()
        unsubscribe = null
        syncProbeUnsubscribe?.invoke()
        syncProbeUnsubscribe = null
        collectJob?.cancel()
        collectJob = null
        retryWatchJob?.cancel()
        retryWatchJob = null
        nudgeWatchJob?.cancel()
        nudgeWatchJob = null
        settleNudgeJob?.cancel()
        settleNudgeJob = null
        convergeJob?.cancel()
        convergeJob = null
        latecomerWatchJob?.cancel()
        latecomerWatchJob = null
        println("DEBUG [CallMediaKeyExchange] stopped room=$roomId")
    }

    private fun olmEncryptor(): OlmEncryptionService? =
        runCatching { client.di.get<OlmEncryptionService>(OlmEncryptionService::class) }.getOrNull()
            .also { if (it == null) println("ERROR [CallMediaKeyExchange] OlmEncryptionService unavailable in DI") }

    /**
     * encryptOlm needs each peer's device keys in the store; trixnity only
     * fetches them (/keys/query) for users whose room membership CHANGES while
     * we sync, so peers who joined before us can still be missing. Mark call
     * participants as outdated ourselves — trixnity's update loop then fetches,
     * signature-verifies and stores their keys. Purely fire-and-forget warm-up:
     * distribution NEVER waits on this (devices still missing keys are handed
     * to the retry watcher instead — blocking here caused the round-8
     * regression where the seed distribution collided with call teardown).
     */
    private suspend fun ensurePeerDeviceKeys(targets: Set<CallDevice>) {
        val store = keyStore ?: return
        val users = targets.map { it.userId }.toSet()
        val missing = users - keysRequestedFor
        if (missing.isNotEmpty()) {
            val missingIds = missing.map { UserId(it) }.toSet()
            runCatching { markKeysOutdated(missingIds) }
                .onFailure { println("WARN [CallMediaKeyExchange] marking peers outdated failed: ${it.message}") }
                .onSuccess { println("DEBUG [CallMediaKeyExchange] requested device keys for $missing") }
            keysRequestedFor += missing
        }
    }

    /**
     * Ensure [users]' device keys are fetched AND signature-verified.
     *
     * SECURITY: this now marks keys outdated via `updateOutdatedKeys` and lets
     * trixnity's `OutdatedKeysHandler` fetch, verify and store them.
     *
     * It used to call /keys/query itself and write the store directly with
     * `saveDeviceKeys`, which is `deviceKeysCache.set(...)`. Two defects:
     *   1. it stored `KeySignatureTrustLevel.Valid(verified = false)`, skipping
     *      the `signService.verify` that Trixnity's own path requires;
     *   2. it *replaced* the user's entire device map, discarding already-verified
     *      cross-signed keys and silently unblocking blocked devices.
     *
     * That let a malicious or compromised homeserver substitute Curve25519 identity
     * keys and receive the olm-encrypted 16-byte call media key -- i.e. every frame
     * of the call -- which is the one thing this whole exchange exists to prevent.
     *
     * It also ran on the hot path (before every reply, in the retry watcher, and
     * after every olm encryption failure), so it was not an edge case.
     */
    private suspend fun fetchAndStorePeerDeviceKeys(users: Collection<String>) {
        val store = keyStore ?: return
        for (user in users) {
            val ids = setOf(UserId(user))
            // SECURITY: route through trixnity's OutdatedKeysHandler, the single
            // writer that fetches, signature-verifies and stores. This function
            // used to call /keys/query itself and store the result as
            // `KeySignatureTrustLevel.Valid(verified = false)` via `saveDeviceKeys`,
            // which (a) skipped `signService.verify` entirely and (b) *replaced*
            // every device entry for the user rather than merging -- since
            // saveDeviceKeys is `deviceKeysCache.set(...)`. Blocked devices were
            // silently unblocked and verified cross-signed keys were overwritten.
            //
            // A malicious homeserver could serve substituted Curve25519 identity
            // keys and clients would olm-encrypt the 16-byte call media key to the
            // attacker, handing the operator every frame -- defeating the exact
            // property this exchange exists to provide.
            runCatching { markKeysOutdated(ids) }
                .onSuccess {
                    util.OrbitLog.d(
                        "CallMediaKeyExchange",
                        "requested signature-verified device keys for $user"
                    )
                }
                .onFailure {
                    util.OrbitLog.w(
                        "CallMediaKeyExchange",
                        "marking $user outdated for key verification failed: ${it.message}"
                    )
                }
        }
    }

    private fun subscribeReceive(): (() -> Unit)? {
        val decrypter = runCatching { client.di.get<OlmEventHandler>(OlmEventHandler::class) }.getOrNull()
        if (decrypter == null) {
            println("ERROR [CallMediaKeyExchange] OlmEventHandler unavailable in DI")
            return null
        }
        return decrypter.subscribe { container ->
            if (stopped) return@subscribe
            runCatching { handleDecrypted(container) }.onFailure {
                println("WARN [CallMediaKeyExchange] key event handling failed: ${it.message}")
            }
        }
    }

    private fun handleDecrypted(container: de.connect2x.trixnity.crypto.olm.DecryptedOlmEventContainer) {
        val decrypted = container.decrypted
        // Sender identity comes from the olm ENVELOPE (authenticated), never from
        // the JSON body which a malicious sender could forge.
        val sender = decrypted.sender.full
        val content = decrypted.content as? UnknownEventContent ?: run {
            println(
                "DEBUG [CallMediaKeyExchange] olm event from=$sender " +
                    "content=${decrypted.content?.let { it::class.simpleName }} not Unknown, skipped"
            )
            return
        }
        if (content.eventType != CallMediaKeys.EVENT_TYPE) {
            println("DEBUG [CallMediaKeyExchange] olm event from=$sender type=${content.eventType} not a media-key event")
            return
        }
        val parsed = CallMediaKeys.parseEncryptionKeysPayload(content.raw) ?: run {
            println("WARN [CallMediaKeyExchange] malformed key event from $sender ignored")
            return
        }
        val eventRoomId = parsed.roomId
        if (eventRoomId != null && eventRoomId != roomId) {
            println("DEBUG [CallMediaKeyExchange] key event from $sender for other room=$eventRoomId ignored")
            return
        }
        val claimedDevice = parsed.claimedDeviceId ?: run {
            println("WARN [CallMediaKeyExchange] key event from $sender without claimed_device_id ignored")
            return
        }
        if (!receivedFilter.accept(sender, claimedDevice, parsed.keyIndex, parsed.sentTsMs ?: nowMs())) {
            println(
                "DEBUG [CallMediaKeyExchange] outdated key event from $sender device=$claimedDevice " +
                    "index=${parsed.keyIndex} ignored"
            )
            return
        }
        val identity = CallMediaKeys.participantIdentity(sender, claimedDevice)
        if (identity == ownLivekitIdentity) {
            println("DEBUG [CallMediaKeyExchange] ignoring echoed own key from $sender")
            return
        }
        receivedPeerIdentities.add(identity)
        val delivered = deliverNativeMediaKey(identity, parsed.key, parsed.keyIndex)
        println(
            "DEBUG [CallMediaKeyExchange] peer key sender=$sender device=$claimedDevice " +
                "index=${parsed.keyIndex} identity=$identity delivered=$delivered"
        )
        maybeReplyWithOwnKey(sender, claimedDevice)
    }

    /**
     * TX-dead recovery: distribution above is roster-DIFF-driven (Element
     * semantics), which silently misses peers who reset their key ring
     * mid-call (same device id = no diff = never resent to them) or joined
     * after the last pass covered their device — they hold none/stale of our
     * keys and decrypt NOTHING we send while their RX of us is the only thing
     * broken. A freshly accepted peer key event (the outdated filter above
     * already rejected duplicates) proves that peer's olm direction works
     * RIGHT NOW, so replying with our current key on it is the most reliable
     * delivery path available. Loop safety for Orbit-to-Orbit pairs: our
     * resend carries an index the peer has already seen, so its filter drops
     * it before any reciprocal hook — only genuine peer rotations trigger a
     * reply, one each.
     */
    private fun maybeReplyWithOwnKey(sender: String, claimedDevice: String) {
        val identity = CallMediaKeys.participantIdentity(sender, claimedDevice)

        // Rate limit -- and FINALLY USE IT.
        //
        // `REPLY_ON_RECEIVE_MIN_INTERVAL_MS` and `lastReplyToDeviceMs` have both
        // existed since this path was written, and neither was ever read: every
        // received key event produced an immediate resend plus a /keys/query. The
        // KDoc above argued loop safety purely from the peer's OutdatedKeyFilter,
        // which is a property of the OTHER implementation, not a local guarantee.
        // If a peer ever echoes our key back to us -- which a buggy or
        // older client can -- this is the loop, and it is a Matrix room event
        // each.
        val nowMs = System.currentTimeMillis()
        val previous = lastReplyToDeviceMs.put(identity, nowMs) ?: 0L
        if (nowMs - previous < REPLY_ON_RECEIVE_MIN_INTERVAL_MS) {
            return
        }

        deliveredIndex.invalidate(identity)
        scope.launch {
            if (stopped) return@launch
            // Snapshot payload+index atomically under the roster mutex so a
            // concurrent rotate can't split the pair across the build.
            val (payload, keyIndex) = mutex.withLock { currentPayload() to ring.currentIndex }
            // Their device keys may never have been queried (late joiner not in
            // roster yet); encryptOlm needs them stored or it fails per-device.
            runCatching { fetchAndStorePeerDeviceKeys(listOf(sender)) }
                .onFailure { println("WARN [CallMediaKeyExchange] reply /keys/query for $sender failed: ${it.message}") }
            println(
                "DEBUG [CallMediaKeyExchange] reply-on-receive: resending current key " +
                    "index=$keyIndex to $sender:$claimedDevice"
            )
            distribute(payload, setOf(CallDevice(userId = sender, deviceId = claimedDevice)), keyIndex)
        }
    }

    /**
     * Nudge scope: only devices that are REALLY in the room right now (LiveKit
     * participant view) AND have never sent us a key. The matrixRTC roster
     * keeps stale-but-unexpired membership events of crashed clients for hours
     * (legacy TTL 12 h), which burned the reactive nudge budget on ghost
     * devices while genuinely keyless peers went unheeded. Falls back to the
     * full roster if the LiveKit view is empty so a native-wiring regression
     * can never silence the nudges entirely.
     */
    private fun nudgeMissingPeers(targets: Set<CallDevice>): List<CallDevice> {
        val livekitIds = runCatching { LiveKitRemoteVideoFrameStore.remoteParticipants.value }
            .getOrNull().orEmpty()
        return targets.filter { dev ->
            val identity = CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
            val live = livekitIds.isEmpty() || identity in livekitIds
            live && identity !in receivedPeerIdentities
        }.also { missing ->
            val ghostCount = targets.size - missing.size
            // Only when the ghost count CHANGES. This runs on every nudge tick
            // (8 s) and every settle/latecomer pass, so an unchanged count means
            // an unchanged, already-reported situation — logging it again is pure
            // noise that buried the lines that actually explain a failure (it
            // repeated hundreds of times per call and pushed the real nudge and
            // distribution lines off screen). A change in the count is new
            // information and still gets logged, at the same DEBUG level as
            // before.
            if (ghostCount > 0 && livekitIds.isNotEmpty() && ghostCount != lastLoggedGhostCount) {
                lastLoggedGhostCount = ghostCount
                println(
                    "DEBUG [CallMediaKeyExchange] nudge scope: $ghostCount of ${targets.size} roster " +
                        "device(s) not present in the LiveKit room — excluded as ghosts"
                )
            }
        }
    }

    private suspend fun onDevices(all: Set<CallDevice>) {
        mutex.withLock {
            if (stopped) return
            val targets = all.filterNot { it.userId == ownUserId && it.deviceId == ownDeviceId }.toSet()
            if (targets.isNotEmpty()) ensurePeerDeviceKeys(targets)
            val seenAt = nowMs()
            for (dev in targets) {
                val id = CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
                knownDevices[id] = dev
                deviceLastSeenMs[id] = seenAt
            }
            val previous = knownTargets
            knownTargets = targets
            if (previous == null) {
                // First emission seeds the roster AND pushes our current key to
                // everyone already in the call — otherwise peers can never decrypt
                // our media when we join an existing call.
                if (targets.isNotEmpty()) {
                    println(
                        "DEBUG [CallMediaKeyExchange] distributing initial key index=${ring.currentIndex} " +
                            "to ${targets.size} device(s)"
                    )
                    distribute(currentPayload(), targets, ring.currentIndex)
                }
                return
            }
            val old = previous
            val joiners = targets - old

            // Rotation is the one operation in this file that desynchronises
            // EVERY peer in the call at once. It is therefore gated on having
            // actual key-exchange history with the departing device, not merely
            // on that device having been present in the roster a moment ago.
            //
            // Run 67, group room, hellishsaa cut his call and rejoined twice.
            // The first cycle was clean. The second was silent, and the cause was
            // never hellishsaa:
            //
            //     rotating key: leavers=1 joiners=0 keyAgeMs=26065
            //     deferring 1 device(s) without stored keys: [@nfak_ism:ZVK65HTOZW]
            //     WARN giving up on 1 device(s) with no published olm keys after
            //          4 queries: [@nfak_ism] ... stale MatrixRTC membership record
            //
            // @nfak_ism was a stale MatrixRTC membership record left in the roster
            // by a crashed client. When its device keys could not be fetched it was
            // dropped from targets, and `old - targets` reported that as a LEAVER.
            // Element rotates on a leaver too -- that rule is correct, and it is
            // why Orbit and Element interoperate. The difference is what counts as
            // a leaver. Orbit counted the absence of a roster entry it had never
            // exchanged a single key with, then rotated the shared key on that
            // fiction. hellishsaa was connected throughout and was desynced by a
            // dead record being evicted.
            //
            // Rotating for a device we never reached protects nothing: it holds no
            // key of ours and we never delivered one, so there is no shared state
            // to revoke. It costs everything: every live peer is moved to a new
            // index and each must learn it before audio resumes.
            //
            // A real leaver is in the exchange ledger -- we delivered a key to them
            // or they sent us one -- so it still rotates, and forward secrecy is
            // preserved for the case that rule exists to cover.
            val exchangePeers: Set<String> =
                deliveredIndex.snapshot().keys.toSet() + receivedPeerIdentities
            val departing = old - targets
            val leavers = departing.filter { dev ->
                CallMediaKeys.participantIdentity(dev.userId, dev.deviceId) in exchangePeers
            }
            val ghosts = departing - leavers.toSet()
            if (ghosts.isNotEmpty()) {
                println(
                    "DEBUG [CallMediaKeyExchange] ${ghosts.size} roster entr(ies) left without ever " +
                        "exchanging a key -- NOT counted as leavers, no rotation: " +
                        ghosts.map { CallMediaKeys.participantIdentity(it.userId, it.deviceId) }
                )
            }

            // A device vanishing from the roster is NOT evidence that it left. It is
            // evidence only that it is no longer listed, and those are different
            // things: a participant whose connection dropped leaves its membership on
            // the server and stops being counted by the roster within seconds, while
            // a genuine departure publishes an explicit leave event. The roster parser
            // drops leave events (they must not appear in a list of who is present),
            // so until it recorded them separately every disappearance looked
            // identical and Orbit treated a dropped connection as a departure.
            //
            // This matters because rotation is the one action here that desynchronises
            // everyone at once. It is justified for a real departure -- it revokes the
            // departed member's ability to decrypt anything sent after they left. It is
            // not justified for a network blip: nothing about the key material changed
            // and there is nothing to revoke, while every peer in the call is moved to
            // a new index and each has to learn it before audio resumes. A peer that
            // misses that window is deaf, and the only repair is another rotation,
            // which is the same trap again.
            //
            // Rejoin is the case that actually bit: a peer cuts and rejoins, the ghost
            // membership reconciles away, and Orbit rotated the shared key on the
            // strength of a transport event Element never treats as a departure.
            val unconfirmed = departing.filter { dev ->
                val identity = CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
                identity in exchangePeers &&
                    !ConfirmedLeaveLedger.isConfirmedLeaver(identity)
            }
            if (unconfirmed.isNotEmpty()) {
                println(
                    "DEBUG [CallMediaKeyExchange] ${unconfirmed.size} device(s) vanished from the roster " +
                        "with NO leave event -- treating as a drop, not a departure; " +
                        "no rotation, re-delivering current key index=${ring.currentIndex} instead"
                )
                val resendTargets = unconfirmed.toSet()
                if (resendTargets.isNotEmpty()) {
                    distribute(currentPayload(), resendTargets, ring.currentIndex)
                }
            }
            val realLeavers = departing.filter { dev ->
                val identity = CallMediaKeys.participantIdentity(dev.userId, dev.deviceId)
                ConfirmedLeaveLedger.isConfirmedLeaver(identity)
            }
            when (CallMediaKeys.decideMembershipReaction(realLeavers.size, joiners.size, ring.ageMs())) {
                CallMediaKeys.MembershipReaction.Rotate -> {
                    println(
                        "DEBUG [CallMediaKeyExchange] rotating key: confirmedLeavers=${realLeavers.size} " +
                            "joiners=${joiners.size} keyAgeMs=${ring.ageMs()}"
                    )
                    rotateAndDistribute(targets)
                }
                CallMediaKeys.MembershipReaction.ResendCurrent -> {
                    println(
                        "DEBUG [CallMediaKeyExchange] resending current key to joiners=" +
                            "${joiners.size} (keyAgeMs=${ring.ageMs()} within grace)"
                    )
                    distribute(currentPayload(), targets, ring.currentIndex)
                }
                CallMediaKeys.MembershipReaction.None -> {}
            }
        }
    }

    private fun currentPayload(): UnknownEventContent = UnknownEventContent(
        raw = CallMediaKeys.buildEncryptionKeysPayload(
            keyIndex = ring.currentIndex,
            key = ring.currentKey,
            roomId = roomId,
            memberId = "${ownUserId}:${ownDeviceId}",
            claimedDeviceId = ownDeviceId,
            sentTsMs = nowMs()
        ),
        blocks = EventContentBlocks(),
        eventType = CallMediaKeys.EVENT_TYPE
    )

    private suspend fun rotateAndDistribute(targets: Set<CallDevice>) {
        ring.rotate()
        val payload = currentPayload()
        distribute(payload, targets, ring.currentIndex)
        // Element sleeps ~1s after distributing a CHANGED key before using it
        // locally, giving slower peers time to learn it.
        scope.launch {
            delay(CallMediaKeys.USE_KEY_DELAY_MS)
            if (!stopped) {
                deliverNativeMediaKey(ownLivekitIdentity, ring.currentKey, ring.currentIndex)
            }
        }
    }

    private suspend fun distribute(payload: UnknownEventContent, targets: Set<CallDevice>, keyIndex: Int) {
        if (targets.isEmpty()) return
        val olm = olmEncryptor() ?: return
        // encryptOlm is PER DEVICE: (content, userId, deviceId) — the third
        // parameter selects one device from the user's device-key map, NOT a
        // room id (passing one made every lookup miss with "Curve25519 key not
        // found"). Devices without stored keys must NOT block or poison the
        // rest: they are deferred to the retry watcher instead of failing the
        // whole user's batch.
        val ready = mutableListOf<CallDevice>()
        val missing = mutableListOf<CallDevice>()
        for (device in targets) {
            // SECURITY/CORRECTNESS: CancellationException must be rethrown, not
            // swallowed.
            //
            // `runCatching` catches Throwable, so when the caller's scope died
            // mid-lookup it returned null here -- which read as "this peer has no
            // device keys". Every device was therefore deferred, the retry watcher
            // never converged, the watchdog fired and tore the call down. The log
            // line that gave it away was:
            //
            //   getDeviceKeys threw for @hawk_eye_360: StandaloneCoroutine was cancelled
            //   no usable key for @hawk_eye_360 (store returned null device(s))
            //
            // A cancelled scope is a lifecycle event, not a statement about a peer's
            // keys, and treating it as one turned a routine teardown into a total
            // key-exchange failure. Rethrowing lets the scope's own owner decide what
            // cancellation means, and leaves a genuine "no keys" as a genuine empty map.
            val present = try {
                olmStore?.getDeviceKeys(UserId(device.userId))?.containsKey(device.deviceId) == true
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                util.OrbitLog.w(
                    "CallMediaKeyExchange",
                    "getDeviceKeys failed for ${device.userId}: ${e.message}"
                )
                false
            }
            if (present) ready.add(device) else {
                missing.add(device)
                // The first deferral of a call is the moment to explain WHY a device
                // has no usable key: whether the store returned nothing at all, or
                // returned other devices for that user but not this one, or the
                // lookup itself threw. Those are three different bugs and the single
                // "without stored keys" line above cannot tell them apart.
                if (pendingRetryDevices.add(device)) {
                    val all = try {
                        olmStore?.getDeviceKeys(UserId(device.userId))
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        util.OrbitLog.w("CallMediaKeyExchange", "getDeviceKeys failed for ${device.userId}: ${e.message}")
                        null
                    }
                    util.OrbitLog.w(
                        "CallMediaKeyExchange",
                        "no usable key for ${device.userId}:${device.deviceId} " +
                            "(store returned ${all?.size ?: "null"} device(s) for that user)"
                    )
                }
            }
        }
        if (missing.isNotEmpty()) {
            println(
                "DEBUG [CallMediaKeyExchange] deferring ${missing.size} device(s) without stored keys: " +
                    "${missing.map { "${it.userId}:${it.deviceId}" }}"
            )
            pendingRetryDevices.addAll(missing)
        }
        for ((userId, devices) in ready.groupBy { it.userId }) {
            // Per-device isolation: one device failing olm encryption must not
            // discard its siblings' already-encrypted payloads (a whole-user
            // abort here turned single-broken-peer reports into multi-peer
            // deafness until the next sweep).
            val encryptedByDevice = buildMap {
                for (device in devices) {
                    runCatching { olm.encryptOlm(payload, UserId(userId), device.deviceId).getOrThrow() }
                        .onSuccess { put(device.deviceId, it) }
                        .onFailure {
                            println(
                                "ERROR [CallMediaKeyExchange] olm encryption failed for " +
                                    "$userId:${device.deviceId} (OTK exhaustion or rotated device keys " +
                                    "are the usual suspects): ${it.message}"
                            )
                        }
                }
            }
            val failedDevices = devices.filter { it.deviceId !in encryptedByDevice.keys }
            if (failedDevices.isNotEmpty()) {
                // NOT queued into the fast retry watcher: those devices have
                // stored keys already, so their failure is session-level (OTK
                // exhaustion / rotated identity keys) and re-claiming every
                // 1.5 s would hammer the server. They stay unmarked in the
                // delivery ledger, so the 10 s convergence sweeper re-attempts.
                maybeRefreshDeviceKeys(userId)
            }
            if (encryptedByDevice.isEmpty()) continue
            try {
                client.api.user.sendToDevice(mapOf(UserId(userId) to encryptedByDevice))
                    .onFailure { println("WARN [CallMediaKeyExchange] sendToDevice failed for $userId: ${it.message}") }
                    .onSuccess {
                        // Confirmed delivery: only now may the convergence ledger
                        // consider this device covered for keyIndex — and only for
                        // devices that actually made it into this batch.
                        for (device in devices) {
                            if (device.deviceId !in encryptedByDevice.keys) continue
                            deliveredIndex.markDelivered(
                                CallMediaKeys.participantIdentity(userId, device.deviceId),
                                keyIndex
                            )
                        }
                        println(
                            "DEBUG [CallMediaKeyExchange] key index=$keyIndex sent " +
                                "user=$userId devices=${encryptedByDevice.keys}"
                        )
                    }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WARN [CallMediaKeyExchange] olm encryption failed for $userId: ${e.message}")
            }
        }
    }

    /**
     * After an olm encryption failure, refresh this user's stored device keys
     * at most every DEVICE_KEY_REFRESH_MIN_INTERVAL_MS — handles a mid-call
     * device-key rotation (stale Curve25519 identity key poisons every attempt
     * for the rest of the call otherwise) without hammering /keys/query during
     * an outage.
     */
    private fun maybeRefreshDeviceKeys(userId: String) {
        val now = nowMs()
        synchronized(lastDeviceKeyRefreshMs) {
            val last = lastDeviceKeyRefreshMs[userId] ?: 0L
            if (now - last < DEVICE_KEY_REFRESH_MIN_INTERVAL_MS) return
            lastDeviceKeyRefreshMs[userId] = now
        }
        scope.launch {
            if (stopped) return@launch
            println("DEBUG [CallMediaKeyExchange] refreshing device keys for $userId after encryption failure")
            runCatching { fetchAndStorePeerDeviceKeys(listOf(userId)) }
                .onFailure { println("WARN [CallMediaKeyExchange] post-failure /keys/query for $userId failed: ${it.message}") }
        }
    }

    private companion object {
        const val RETRY_WATCH_INTERVAL_MS = 1_000L
        const val DIAG_ZERO_INBOUND_GRACE_MS = 20_000L
        const val NUDGE_WATCH_INTERVAL_MS = 8_000L
        const val NUDGE_SETTLE_DELAY_MS = 15_000L
        const val NUDGE_GRACE_MS = 45_000L
        const val NUDGE_COOLDOWN_MS = 60_000L
        const val NUDGE_MAX_PER_CALL = 3
        const val REPLY_ON_RECEIVE_MIN_INTERVAL_MS = 1_500L
        const val CONVERGE_INTERVAL_MS = 2_500L
        // Minimum spacing between post-failure /keys/query refreshes per user.
        const val DEVICE_KEY_REFRESH_MIN_INTERVAL_MS = 15_000L
        const val LATECOMER_WATCH_INTERVAL_MS = 2_000L
        // A participant present this long without EVER sending us a key is
        // almost certainly a throttled client that ignored our join diff —
        // healthy clients deliver within a few seconds of seeing new members.
        const val LATECOMER_GRACE_MS = 4_000L
        // Devices only seen in the roster (not currently in the LiveKit room)
        // stay sweep-eligible for this long after their last roster appearance —
        // long enough to rescue a peer whose membership event flickered out,
        // short enough that long-gone devices stop receiving new indexes.
        const val SWEEP_DEVICE_TTL_MS = 600_000L

        // How many times we will re-query one device's olm keys before deciding
        // it is not a live peer. See the retry watcher.
        //
        // 4 queries, 30s apart, is ~2 minutes of a call. Long enough to cover a
        // device that was mid-login or had just rotated its identity keys, short
        // enough that a genuinely dead device stops costing requests inside the
        // first few minutes of any call.
        const val KEY_FETCH_MAX_ATTEMPTS = 4

        // Spacing between those re-queries, per user. Matches the spirit of
        // DEVICE_KEY_REFRESH_MIN_INTERVAL_MS above but tighter, because this path
        // only runs while the device is actively undeliverable and the whole
        // point of the cap is to converge rather than to poll forever.
        const val KEY_FETCH_RETRY_INTERVAL_MS = 30_000L
    }
}

/** Only one call can be active at a time; joining a new call replaces any stale instance. */
object CallMediaKeyExchangeManager {
    @Volatile private var active: CallMediaKeyExchange? = null

    /**
     * Swapped-out instance, kept alive until it has actually stopped.
     *
     * `start()` used to be `stop(); ...; active = exchange`, non-atomically. Two
     * concurrent starts could both pass `stop()` and then race the assignment, so
     * the loser's exchange was never stopped: its watcher coroutines, its
     * `decrypter.subscribe` handle and its `client.api.sync.subscribe` handle all
     * leaked for the process lifetime -- each quietly nudging membership and
     * answering /keys/query for a call that no longer existed.
     *
     * The `retired` list is drained by [stop], which is the only safe place to
     * cancel something that was live a moment ago.
     */
    private val lock = Any()
    private val retired = mutableListOf<CallMediaKeyExchange>()

    fun start(
        client: MatrixClient,
        scope: kotlinx.coroutines.CoroutineScope,
        roomId: String,
        ownUserId: String,
        ownDeviceId: String,
        ownLivekitIdentity: String,
        devicesFlow: Flow<Set<CallDevice>>,
        nudgeMembership: (suspend () -> Unit)? = null
    ): Boolean {
        val exchange = CallMediaKeyExchange(
            client = client,
            scope = scope,
            roomId = roomId,
            ownUserId = ownUserId,
            ownDeviceId = ownDeviceId,
            ownLivekitIdentity = ownLivekitIdentity,
            devicesFlow = devicesFlow,
            nudgeMembership = nudgeMembership
        )
        synchronized(lock) {
            // Retire rather than discard, so a concurrent start's loser is still
            // stopped -- just not synchronously inside start().
            active?.let { retired.add(it) }
            active = exchange
        }
        exchange.start()
        // Drain outside the lock: stop() cancels coroutines and can take a moment,
        // and holding a monitor across that is how you get the next deadlock.
        drainRetired()
        return true
    }

    fun stop() {
        synchronized(lock) {
            active?.let { retired.add(it) }
            active = null
        }
        drainRetired()
    }

    private fun drainRetired() {
        val pending = synchronized(lock) {
            if (retired.isEmpty()) return
            val copy = retired.toList()
            retired.clear()
            copy
        }
        for (exchange in pending) {
            runCatching { exchange.stop() }
                .onFailure { println("WARN [CallMediaKeyExchangeManager] retiring a superseded exchange failed: ${it.message}") }
        }
    }

    /** Stamp of the active exchange's last self-nudge; 0 when no call is live. */
    fun lastSelfNudgeAtMs(): Long = active?.lastSelfNudgeAtMs() ?: 0L

    fun rearmOwnKey() {
        active?.rearmOwnKey()
    }
}
