package livekit

import de.connect2x.trixnity.client.MatrixClient
import de.connect2x.trixnity.client.store.KeySignatureTrustLevel
import de.connect2x.trixnity.client.store.KeyStore
import de.connect2x.trixnity.client.store.StoredDeviceKeys
import de.connect2x.trixnity.crypto.olm.OlmDecrypter
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
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val ring = CallMediaKeys.OwnKeyRing(nowMs)
    private val receivedFilter = CallMediaKeys.OutdatedKeyFilter()
    private val mutex = Mutex()
    private var knownTargets: Set<CallDevice>? = null
    private var unsubscribe: (() -> Unit)? = null
    private var collectJob: Job? = null
    private var retryWatchJob: Job? = null
    private val pendingRetryDevices = java.util.Collections.synchronizedSet(mutableSetOf<CallDevice>())
    private val directQueriedUsers = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    private val keyStore: KeyStore? =
        runCatching { client.di.get<KeyStore>(KeyStore::class) }.getOrNull()
            .also { if (it == null) println("ERROR [CallMediaKeyExchange] KeyStore unavailable in DI") }
    private val olmStore: OlmStore? =
        runCatching { client.di.get<OlmStore>(OlmStore::class) }.getOrNull()
    private val keysRequestedFor = mutableSetOf<String>()
    private var syncProbeUnsubscribe: (() -> Unit)? = null
    private val seenToDeviceTypes = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    @Volatile private var rawOlmToDeviceSeen = 0
    @Volatile private var syncBatchesSeen = 0
    @Volatile private var stopped = false

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
        scope.launch { diagnoseOwnKeyServerState() }
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
                val unqueried = snapshot.map { it.userId }.distinct().filter { directQueriedUsers.add(it) }
                if (unqueried.isNotEmpty()) fetchAndStorePeerDeviceKeys(unqueried)
                val ready = snapshot.filter { dev ->
                    runCatching { olmStore?.getDeviceKeys(UserId(dev.userId)) }
                        .getOrNull()?.containsKey(dev.deviceId) == true
                }
                if (ready.isNotEmpty()) {
                    println(
                        "DEBUG [CallMediaKeyExchange] retry distributing to ${ready.size} " +
                            "newly-ready device(s)"
                    )
                    pendingRetryDevices.removeAll(ready.toSet())
                    distribute(currentPayload(), ready.toSet())
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
            runCatching { store.updateOutdatedKeys { current -> current + missingIds } }
                .onFailure { println("WARN [CallMediaKeyExchange] marking peers outdated failed: ${it.message}") }
                .onSuccess { println("DEBUG [CallMediaKeyExchange] requested device keys for $missing") }
            keysRequestedFor += missing
        }
    }

    private suspend fun fetchAndStorePeerDeviceKeys(users: Collection<String>) {
        val store = keyStore ?: return
        for (user in users) {
            val response = try {
                client.api.key.getKeys(mapOf(UserId(user) to emptySet<String>()), 10_000L).getOrThrow()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                println("WARN [CallMediaKeyExchange] /keys/query failed for $user: ${e.message}")
                null
            } ?: continue
            val signed = response.deviceKeys?.get(UserId(user)).orEmpty()
            if (signed.isEmpty()) {
                println("WARN [CallMediaKeyExchange] /keys/query returned no devices for $user")
                continue
            }
            val devices = signed.mapValues { (_, value) ->
                StoredDeviceKeys(value, KeySignatureTrustLevel.Valid(verified = false))
            }
            runCatching { store.saveDeviceKeys(UserId(user), devices) }
                .onFailure { println("WARN [CallMediaKeyExchange] storing device keys for $user failed: ${it.message}") }
                .onSuccess {
                    println(
                        "DEBUG [CallMediaKeyExchange] stored ${devices.size} device key(s) for $user " +
                            "via /keys/query fallback"
                    )
                }
        }
    }

    private fun subscribeReceive(): (() -> Unit)? {
        val decrypter = runCatching { client.di.get<OlmDecrypter>(OlmDecrypter::class) }.getOrNull()
        if (decrypter == null) {
            println("ERROR [CallMediaKeyExchange] OlmDecrypter unavailable in DI")
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
        val delivered = deliverNativeMediaKey(identity, parsed.key, parsed.keyIndex)
        println(
            "DEBUG [CallMediaKeyExchange] peer key sender=$sender device=$claimedDevice " +
                "index=${parsed.keyIndex} identity=$identity delivered=$delivered"
        )
    }

    private suspend fun onDevices(all: Set<CallDevice>) {
        mutex.withLock {
            if (stopped) return
            val targets = all.filterNot { it.userId == ownUserId && it.deviceId == ownDeviceId }.toSet()
            if (targets.isNotEmpty()) ensurePeerDeviceKeys(targets)
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
                    distribute(currentPayload(), targets)
                }
                return
            }
            val old = previous
            val leavers = old - targets
            val joiners = targets - old
            when (CallMediaKeys.decideMembershipReaction(leavers.size, joiners.size, ring.ageMs())) {
                CallMediaKeys.MembershipReaction.Rotate -> {
                    println(
                        "DEBUG [CallMediaKeyExchange] rotating key: leavers=${leavers.size} " +
                            "joiners=${joiners.size} keyAgeMs=${ring.ageMs()}"
                    )
                    rotateAndDistribute(targets)
                }
                CallMediaKeys.MembershipReaction.ResendCurrent -> {
                    println(
                        "DEBUG [CallMediaKeyExchange] resending current key to joiners=" +
                            "${joiners.size} (keyAgeMs=${ring.ageMs()} within grace)"
                    )
                    distribute(currentPayload(), targets)
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
        distribute(currentPayload(), targets)
        // Element sleeps ~1s after distributing a CHANGED key before using it
        // locally, giving slower peers time to learn it.
        scope.launch {
            delay(CallMediaKeys.USE_KEY_DELAY_MS)
            if (!stopped) {
                deliverNativeMediaKey(ownLivekitIdentity, ring.currentKey, ring.currentIndex)
            }
        }
    }

    private suspend fun distribute(payload: UnknownEventContent, targets: Set<CallDevice>) {
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
            val present = runCatching { olmStore?.getDeviceKeys(UserId(device.userId)) }
                .getOrNull()?.containsKey(device.deviceId) == true
            if (present) ready.add(device) else missing.add(device)
        }
        if (missing.isNotEmpty()) {
            println(
                "DEBUG [CallMediaKeyExchange] deferring ${missing.size} device(s) without stored keys: " +
                    "${missing.map { "${it.userId}:${it.deviceId}" }}"
            )
            pendingRetryDevices.addAll(missing)
        }
        for ((userId, devices) in ready.groupBy { it.userId }) {
            try {
                val encryptedByDevice = buildMap {
                    for (device in devices) {
                        put(device.deviceId, olm.encryptOlm(payload, UserId(userId), device.deviceId).getOrThrow())
                    }
                }
                client.api.user.sendToDevice(mapOf(UserId(userId) to encryptedByDevice))
                    .onFailure { println("WARN [CallMediaKeyExchange] sendToDevice failed for $userId: ${it.message}") }
                    .onSuccess {
                        println(
                            "DEBUG [CallMediaKeyExchange] key index=${ring.currentIndex} sent " +
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

    private companion object {
        const val RETRY_WATCH_INTERVAL_MS = 1_500L
    }
}

/** Only one call can be active at a time; joining a new call replaces any stale instance. */
object CallMediaKeyExchangeManager {
    @Volatile private var active: CallMediaKeyExchange? = null

    fun start(
        client: MatrixClient,
        scope: kotlinx.coroutines.CoroutineScope,
        roomId: String,
        ownUserId: String,
        ownDeviceId: String,
        ownLivekitIdentity: String,
        devicesFlow: Flow<Set<CallDevice>>
    ): Boolean {
        stop()
        val exchange = CallMediaKeyExchange(
            client = client,
            scope = scope,
            roomId = roomId,
            ownUserId = ownUserId,
            ownDeviceId = ownDeviceId,
            ownLivekitIdentity = ownLivekitIdentity,
            devicesFlow = devicesFlow
        )
        active = exchange
        exchange.start()
        return true
    }

    fun stop() {
        active?.stop()
        active = null
    }
}
