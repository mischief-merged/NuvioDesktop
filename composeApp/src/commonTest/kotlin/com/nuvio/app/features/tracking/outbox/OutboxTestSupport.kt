package com.nuvio.app.features.tracking.outbox

internal const val TestStartEpochMs = 1_800_000_000_000L

internal class MemoryOutboxStore(var payload: String? = null) : OutboxPayloadStore {
    val unreadable = mutableListOf<String>()
    var failSaves = false
    var failUnreadableSaves = false

    override fun load(): String? = payload

    override fun save(payload: String) {
        if (failSaves) error("disk full")
        this.payload = payload
    }

    override fun saveUnreadable(payload: String, savedAtEpochMs: Long) {
        if (failUnreadableSaves) error("disk full")
        unreadable += payload
    }
}

internal class FakeOutboxAdapter(
    var activeProfileId: Int = 1,
    var account: String? = "account-a",
) : TrackerOutboxAdapter {
    val sent = mutableListOf<List<OutboxEntry>>()
    val checked = mutableListOf<OutboxEntry>()
    val outcomes = ArrayDeque<OutboxSendOutcome>()
    var recorded: (OutboxEntry) -> Boolean? = { false }
    var beforeAnswer: suspend () -> Unit = {}

    override fun connectedAccountKey(profileId: Int): String? = account.takeIf { profileId == activeProfileId }

    override suspend fun send(batch: List<OutboxEntry>): OutboxSendOutcome {
        sent += batch
        beforeAnswer()
        return outcomes.removeFirstOrNull() ?: OutboxSendOutcome.Delivered
    }

    override suspend fun isRecorded(entry: OutboxEntry): Boolean? {
        checked += entry
        return recorded(entry)
    }
}

internal class OutboxFixture(
    val store: MemoryOutboxStore = MemoryOutboxStore(),
    val adapter: FakeOutboxAdapter = FakeOutboxAdapter(),
    var now: Long = TestStartEpochMs,
) {
    val slept = mutableListOf<Long>()

    fun newOutbox(): TrackerOutbox = TrackerOutbox(
        store = store,
        adapter = adapter,
        nowEpochMs = { now },
        sleep = { ms ->
            slept += ms
            now += ms
        },
        random = { 0.5 },
    )

    val outbox: TrackerOutbox = newOutbox()
}

internal val movieA = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000001")
internal val movieB = OutboxMedia(kind = OutboxMediaKind.MOVIE, imdb = "tt0000002")

internal fun episode(season: Int, number: Int, imdb: String = "tt0000009") =
    OutboxMedia(kind = OutboxMediaKind.EPISODE, imdb = imdb, season = season, number = number)

internal fun watch(
    media: OutboxMedia = movieA,
    watchedAtEpochMs: Long = TestStartEpochMs,
    profileId: Int = 1,
    accountKey: String = "account-a",
    maybeDelivered: Boolean = false,
    notBeforeEpochMs: Long = 0L,
) = OutboxWatch(profileId, accountKey, media, watchedAtEpochMs, maybeDelivered, notBeforeEpochMs)
