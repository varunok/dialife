package com.lumenarcanus.dialife.live

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.lumenarcanus.dialife.data.AlarmEvent
import com.lumenarcanus.dialife.data.AlarmView
import com.lumenarcanus.dialife.data.ApiError
import com.lumenarcanus.dialife.data.ApiException
import com.lumenarcanus.dialife.data.Credentials
import com.lumenarcanus.dialife.data.DiaLifeApi
import com.lumenarcanus.dialife.data.History
import com.lumenarcanus.dialife.data.MeterReport
import com.lumenarcanus.dialife.data.Question
import com.lumenarcanus.dialife.data.Series
import com.lumenarcanus.dialife.data.StateResponse
import com.lumenarcanus.dialife.data.Uploader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import com.lumenarcanus.dialife.data.Treatment
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

enum class Link { Connecting, Live, Offline }

data class Live(
    val state: StateResponse? = null,
    val history: History? = null,
    val link: Link = Link.Connecting,
    val lastLiveAt: Long = 0, // elapsedRealtime of the last moment the stream was open
    val unauthorized: Boolean = false,
    val meter: MeterReport? = null, // sensor vs glucometer; refetched when checks or sensors change
)

/**
 * The one live connection to the server, shared by the screen and the
 * foreground service. GET /api/stream (SSE) pushes every reading; on any
 * break we reconnect with Last-Event-ID and the server resends what we missed.
 */
class LiveRepository(context: Context, private val api: DiaLifeApi = DiaLifeApi()) {
    private val app = context.applicationContext
    private val cache = LiveCache(app.filesDir)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val queue = TreatmentQueue(app.filesDir)
    private val flushing = Mutex()

    private val _pending = MutableStateFlow(queue.all())
    /** Food/insulin changes made here that the server has not confirmed yet. */
    val pending: StateFlow<List<PendingOp>> = _pending.asStateFlow()

    private val _live = MutableStateFlow(Live())
    val live: StateFlow<Live> = _live.asStateFlow()

    /** Alarm episode events as they arrive, plus "snapshot" after every (re)connect. */
    private val _alarms = MutableSharedFlow<AlarmSignal>(extraBufferCapacity = 32)
    val alarms: SharedFlow<AlarmSignal> = _alarms.asSharedFlow()

    /** This phone's device id: who "I" am in handled_by_id / coming_by_id. */
    @Volatile var myId: Long = 0
        private set
    @Volatile var credentials: Credentials? = null
        private set

    private var job: Job? = null
    private var creds: Credentials? = null
    @Volatile private var lastEventId: String? = null

    @Volatile private var source: EventSource? = null

    private val network = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = reconnectNow()

        // the socket would only notice after the 70 s read timeout: say "offline" now
        override fun onLost(network: Network) {
            source?.cancel()
        }
    }

    @Synchronized
    fun start(c: Credentials) {
        if (job?.isActive == true && creds == c) return
        job?.cancel()
        creds = c
        credentials = c
        myId = c.deviceId
        val cached = cache.load(c.server, c.deviceName)
        lastEventId = cached?.lastEventId
        _live.value = Live(cached?.state, cached?.history)
        job = scope.launch { run(c) }
        flush()
        runCatching { app.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(network) }
    }

    @Synchronized
    fun stop(forget: Boolean = false) {
        job?.cancel()
        job = null
        creds = null
        credentials = null
        runCatching { app.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(network) }
        if (forget) {
            cache.clear()
            queue.clear()
            _pending.value = emptyList()
            _live.value = Live()
        }
    }

    /** Skip the back-off: the network came back or the watchdog lost patience. */
    fun reconnectNow() {
        wake.trySend(Unit)
    }

    private suspend fun run(c: Credentials) {
        if (myId == 0L) api.me(c.server, c.token).onSuccess { myId = it.id }
        var backoff = MIN_BACKOFF_MS
        while (true) {
            val opened = stream(c)
            if (_live.value.unauthorized) return
            if (opened) backoff = MIN_BACKOFF_MS
            _live.update { it.copy(link = Link.Offline) }
            withTimeoutOrNull(backoff) { wake.receive() }
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            _live.update { if (it.link == Link.Offline) it.copy(link = Link.Connecting) else it }
        }
    }

    /** One SSE connection, until it breaks. True if it was open at some point. */
    private suspend fun stream(c: Credentials): Boolean = suspendCancellableCoroutine { cont ->
        var opened = false
        val req = Request.Builder()
            .url(c.server + "/api/stream")
            .header("Authorization", "Bearer ${c.token}")
            .apply { lastEventId?.let { header("Last-Event-ID", it) } }
            .build()
        val source = EventSources.createFactory(sseClient).newEventSource(req, object : EventSourceListener() {
            override fun onOpen(eventSource: EventSource, response: Response) {
                opened = true
                _live.update { it.copy(link = Link.Live, lastLiveAt = SystemClock.elapsedRealtime()) }
                flush() // back online: send what waited
            }

            override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                try {
                    handle(c, type, data)
                    if (id != null) lastEventId = id
                    _live.update { it.copy(lastLiveAt = SystemClock.elapsedRealtime()) }
                    persist(c)
                } catch (e: Exception) {
                    Log.w(TAG, "bad event $type", e)
                }
            }

            override fun onClosed(eventSource: EventSource) {
                if (opened) _live.update { it.copy(lastLiveAt = SystemClock.elapsedRealtime()) } // live until this moment
                if (cont.isActive) cont.resume(opened)
            }

            override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                if (opened) _live.update { it.copy(lastLiveAt = SystemClock.elapsedRealtime()) }
                if (response?.code == 401 || response?.code == 403) {
                    _live.update { it.copy(unauthorized = true, link = Link.Offline) }
                }
                if (cont.isActive) cont.resume(opened)
            }
        })
        this.source = source
        cont.invokeOnCancellation { source.cancel() }
    }

    private fun handle(c: Credentials, type: String?, data: String) {
        when (type) {
            "state", "bg" -> {
                val st = DiaLifeApi.json.decodeFromString<StateResponse>(data)
                _live.update { cur ->
                    // "bg" carries no display_units: keep what "state" said
                    val merged = st.copy(displayUnits = st.displayUnits ?: cur.state?.displayUnits)
                    cur.copy(state = merged, history = cur.history?.withReading(merged))
                }
                // a fresh connection may have missed anything: reload the day, re-check the alarms
                if (type == "state") {
                    refreshHistory(c)
                    _alarms.tryEmit(AlarmSignal("snapshot", null))
                }
            }
            "history", "treatment" -> refreshHistory(c)
            "alarm", "handled", "checkin", "checkin_ok", "help", "coming", "clear" -> {
                val ev = DiaLifeApi.json.decodeFromString<AlarmEvent>(data)
                _live.update { cur -> cur.copy(state = cur.state?.let { it.copy(alarms = it.alarms.upsert(ev.alarm)) }) }
                _alarms.tryEmit(AlarmSignal(type, ev))
            }
            "question" -> {
                val q = DiaLifeApi.json.decodeFromString<Question>(data)
                _live.update { cur -> cur.copy(state = cur.state?.let { it.copy(questions = it.questions.upsertQuestion(q)) }) }
                _questions.tryEmit(q)
            }
            "intent" -> {
                val ev = DiaLifeApi.json.decodeFromString<com.lumenarcanus.dialife.data.IntentEvent>(data)
                _live.update { cur -> cur.copy(state = cur.state?.copy(intents = ev.intents)) }
            }
            "uploader" -> {
                val u = DiaLifeApi.json.decodeFromString<Uploader>(data)
                _live.update { cur -> cur.copy(state = cur.state?.copy(uploader = u)) }
            }
        }
    }

    /** A question as it changes, reminders included: what the notification follows. */
    private val _questions = MutableSharedFlow<Question>(extraBufferCapacity = 16)
    val questions: SharedFlow<Question> = _questions.asSharedFlow()

    suspend fun answerQuestion(id: Long, answer: String, treatmentId: String? = null): Result<Unit> {
        val c = creds ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.answerQuestion(c, id, answer, treatmentId)
    }

    suspend fun dismissQuestion(id: Long): Result<Unit> {
        val c = creds ?: return Result.failure(ApiException(ApiError.Unauthorized))
        return api.dismissQuestion(c, id).onSuccess {
            _live.update { cur -> cur.copy(state = cur.state?.let { s -> s.copy(questions = s.questions.filter { it.id != id }) }) }
        }
    }

    /** The insulin form opened (or its type changed) here; best effort, the server forgets it in 10 min anyway. */
    fun insulinIntent(insulin: String) {
        val c = creds ?: return
        scope.launch { api.insulinIntent(c, insulin) }
    }

    fun insulinIntentEnd() {
        val c = creds ?: return
        scope.launch { api.insulinIntentEnd(c) }
    }

    fun saveTreatment(t: Treatment) {
        _pending.value = queue.put(PendingOp(t.id, treatment = t.copy(pending = false)))
        flush()
    }

    fun deleteTreatment(id: String) {
        _pending.value = queue.put(PendingOp(id, delete = true))
        flush()
    }

    /** Sends queued changes in order; stops at the first one the network refuses and waits for the next chance. */
    fun flush() {
        val c = creds ?: return
        scope.launch {
            if (!flushing.tryLock()) return@launch
            try {
                var sent = false
                for (op in queue.all()) {
                    val r = if (op.delete) api.deleteTreatment(c, op.id)
                    else api.upsertTreatment(c, op.treatment ?: continue, c.deviceName)
                    val err = (r.exceptionOrNull() as? ApiException)?.error
                    when {
                        r.isSuccess -> sent = true
                        err is ApiError.Server && err.code in 400..499 -> Log.w(TAG, "treatment ${op.id} refused: ${err.code}") // never accepted: drop it
                        else -> break // offline or server trouble: keep it for later
                    }
                    _pending.value = queue.done(op)
                }
                if (sent) refreshHistory(c)
            } finally {
                flushing.unlock()
            }
        }
    }

    /** Debug builds only (FakeEventReceiver): feed an event as if it came over the stream. */
    fun debugEvent(type: String, data: String) {
        val c = creds ?: return
        handle(c, type, data)
    }

    private fun refreshHistory(c: Credentials) {
        scope.launch {
            val now = System.currentTimeMillis()
            api.history(c, now - HISTORY_MS, now)
                .onSuccess { h ->
                    _live.update { it.copy(history = h) }
                    persist(c)
                    refreshMeter(c, h)
                }
                .onFailure { e ->
                    if ((e as? ApiException)?.error == ApiError.Unauthorized) {
                        _live.update { it.copy(unauthorized = true) }
                    }
                }
        }
    }

    private var meterKey: List<Long>? = null

    /** The comparison only changes with a new check or a new sensor: not on every reading. */
    private fun refreshMeter(c: Credentials, h: History) {
        val key = h.mbg.t + h.treatments.filter { it.glucose != null || it.eventType == "Sensor Start" }.map { it.t }
        if (key == meterKey && _live.value.meter != null) return
        scope.launch {
            api.meter(c).onSuccess { m -> meterKey = key; _live.update { it.copy(meter = m) } }
        }
    }

    private fun persist(c: Credentials) {
        val l = _live.value
        runCatching { cache.save(LiveSnapshot(c.server, c.deviceName, l.state, l.history, lastEventId)) }
    }

    companion object {
        private const val TAG = "DiaLifeLive"
        const val HISTORY_MS = 24 * 3_600_000L
        private const val MIN_BACKOFF_MS = 5_000L // the server's "retry:"
        private const val MAX_BACKOFF_MS = 60_000L

        // pings come every 25 s; silence past this means the connection is dead
        private val sseClient: OkHttpClient = DiaLifeApi.defaultClient.newBuilder()
            .readTimeout(70, TimeUnit.SECONDS)
            .pingInterval(30, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

data class AlarmSignal(val type: String, val event: AlarmEvent?)

/** The live episodes after one changed: a cleared one leaves the list. */
internal fun List<AlarmView>.upsert(a: AlarmView): List<AlarmView> {
    val rest = filter { it.id != a.id }
    return if (a.state == "cleared") rest else (rest + a).sortedBy { it.startedAt }
}

/** Adds the newest reading to the chart and drops what fell out of the day. */
internal fun History.withReading(st: StateResponse): History {
    val bg = st.bg ?: return this
    if (sgv.t.isNotEmpty() && bg.mills <= sgv.t.last()) return this
    val from = st.now - LiveRepository.HISTORY_MS
    val keep = sgv.t.indexOfFirst { it >= from }.let { if (it < 0) sgv.t.size else it }
    return copy(
        from = from,
        to = st.now,
        sgv = Series(sgv.t.drop(keep) + bg.mills, sgv.v.drop(keep) + bg.sgv),
    )
}

/** Open questions, suspect marks and a double dose's watch stay; answered and cleared ones leave. */
fun List<Question>.upsertQuestion(q: Question): List<Question> {
    val rest = filter { it.id != q.id }
    return if (q.state == "open" || q.state == "unknown" || q.watch) (rest + q).sortedBy { it.from } else rest
}
