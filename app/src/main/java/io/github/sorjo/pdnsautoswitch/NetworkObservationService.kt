package io.github.sorjo.pdnsautoswitch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import java.io.IOException

/** Event-driven observation and explicitly enabled, session-scoped Auto. */
class NetworkObservationService : Service() {
    private val repository by lazy { ObservationRepository.get(this) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var monitor: NetworkMonitor
    private var trusted: Set<String>? = null
    private var lastSignature: Signature? = null
    private var receiverRegistered = false
    private var providerObserved = false
    private var failed = false
    private val auto = AutoRuntime.session
    private val dns by lazy { DnsController(AndroidDnsSettings(this)) }
    private var pauseJob: Job? = null
    private val recommendationWindow = RecommendationWindow()
    private var settleJob: Job? = null

    private data class Signature(
        val snapshot: NetworkSnapshot,
        val trusted: Set<String>?,
        val access: LocationAccess,
        val provider: ProviderState,
        val visible: Boolean,
        val interactive: Boolean,
        val writePermission: Boolean,
    )

    private val providerObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) { reconcile("DNS_SETTINGS_CHANGED", auto.state.value.enabled) }
    }
    private val accessReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refresh("LOCATION_OR_SCREEN_CHANGED") }
    }

    override fun onCreate() {
        super.onCreate()
        try {
            check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                "Precise location permission is required before starting observation."
            }
            check(getSystemService(LocationManager::class.java).isLocationEnabled) {
                "Location services must be enabled before starting observation."
            }
            promoteToForeground()
            repository.setRunState(ObservationRunState.RUNNING)
            lifecycle("SERVICE_STARTED")
            monitor = NetworkMonitor(this) { reconcile(it) }
            refresh("SERVICE_SUBSCRIBED")
            if (failed) return
            providerObserved = true
            listOf(DnsController.MODE, DnsController.DEFAULT_MODE, DnsController.PROVIDER).forEach { key ->
                contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, providerObserver)
            }
            registerReceiver(accessReceiver, IntentFilter().apply {
                addAction(LocationManager.MODE_CHANGED_ACTION)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }, Context.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            scope.launch {
                trustedWifiRepository(this@NetworkObservationService).ssids.catch { error ->
                    if (error !is IOException) throw error
                    trusted = null
                    reconcile("TRUST_LIST_UNAVAILABLE")
                }.collect {
                    trusted = it
                    reconcile("TRUST_LIST_CHANGED")
                }
            }
            scope.launch {
                var previous = AutoSession.State()
                auto.state.collect { state ->
                    if (state != previous) {
                        lifecycle(when {
                            !state.enabled -> "AUTO_DISABLED"
                            state.pausedUntilMs != null -> "AUTO_PAUSED;duration_minutes=${auto.pauseMinutes}"
                            previous.pausedUntilMs != null -> "AUTO_RESUMED"
                            else -> "AUTO_ENABLED"
                        })
                        if (!failed) try { promoteToForeground() } catch (error: RuntimeException) { fail(error) }
                        pauseJob?.cancel()
                        if (state.pausedUntilMs != null) {
                            pauseJob = scope.launch {
                                delay((state.pausedUntilMs - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                                auto.resumeIfDue()
                            }
                        } else if (state.enabled) refresh("AUTO_READY")
                        previous = state
                    }
                }
            }
            scope.launch {
                ObservationPresence.foreground.drop(1).collect {
                    // Force fresh, permission-redacted capabilities instead of reusing foreground SSIDs.
                    refresh(if (it) "UI_FOREGROUND" else "UI_BACKGROUND")
                }
            }
        } catch (error: RuntimeException) {
            fail(error)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            auto.disable()
            stopSelf()
        } else if (intent?.action == ACTION_ENABLE_AUTO && !failed) {
            auto.enable(dns)
            if (!auto.state.value.enabled) lifecycle("AUTO_ENABLE_FAILED", auto.state.value.error.toString())
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun refresh(event: String) {
        if (failed || !::monitor.isInitialized) return
        try {
            monitor.refresh()
            reconcile(event)
        } catch (error: RuntimeException) { fail(error) }
    }

    private fun canWriteDns() = checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
        PackageManager.PERMISSION_GRANTED

    private fun providerState(): ProviderState = try {
        if (Settings.Global.getString(contentResolver, "private_dns_specifier").isNullOrBlank()) {
            ProviderState.MISSING
        } else ProviderState.CONFIGURED
    } catch (_: RuntimeException) { ProviderState.UNREADABLE }

    private fun reconcile(event: String, force: Boolean = false) {
        if (failed || !::monitor.isInitialized) return
        if (auto.resumeIfDue()) return // State collector requests a fresh subscription.
        val snapshot = monitor.snapshot()
        val access = monitor.locationAccess()
        val provider = providerState()
        val visible = ObservationPresence.foreground.value
        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val writePermission = canWriteDns()
        val signature = Signature(snapshot, trusted, access, provider, visible, interactive, writePermission)
        if (!force && signature == lastSignature) return // Ignore repeated callbacks without relevant changes.
        lastSignature = signature
        val decision = PolicyEvaluator.evaluate(snapshot, trusted, access, provider)
        val active = snapshot.active
        val wifiTrust = if (active?.kind == NetworkKind.WIFI) {
            when {
                active.ssid == null -> ";ssid=HIDDEN"
                trusted == null -> ";ssid=READABLE;trust=UNKNOWN"
                active.ssid in trusted.orEmpty() -> ";ssid=READABLE;trust=YES"
                else -> ";ssid=READABLE;trust=NO"
            }
        } else ""
        val condition = "${active?.kind?.name ?: "NO_NETWORK"}$wifiTrust" +
            ";wifi_count=${snapshot.physicalWifiCount};validated=${active?.validated == true}" +
            ";portal=${active?.captivePortal == true};location=$access" +
            ";ui=${if (visible) "FOREGROUND" else "BACKGROUND"}" +
            ";screen=${if (interactive) "ON" else "OFF"};event=$event" +
            ";elapsed_ms=${SystemClock.elapsedRealtime()};write_permission=$writePermission"
        repository.record(ObservationEntry.now(
            "$condition;stage=RAW",
            "${decision.mode};desired=${decision.desired};reason=${decision.reason};diagnostic_only",
        ))
        // A one-shot quiet window is not polling. Cancel it on every relevant state change.
        recommendationWindow.update(decision, SystemClock.elapsedRealtime())
        settleJob?.cancel()
        settleJob = scope.launch {
            delay(RecommendationWindow.QUIET_MS)
            val settled = recommendationWindow.takeReady(SystemClock.elapsedRealtime()) ?: return@launch
            repository.record(ObservationEntry.now(
                "$condition;stage=STABLE;settled_elapsed_ms=${SystemClock.elapsedRealtime()}",
                "${settled.mode};desired=${settled.desired};reason=${settled.reason};diagnostic_only",
            ))
            // Recheck access/provider/snapshot at application time, not only at callback time.
            if (monitor.snapshot() != snapshot || monitor.locationAccess() != access ||
                providerState() != provider || trusted != signature.trusted ||
                canWriteDns() != writePermission) {
                reconcile("PRE_APPLY_CHANGED", true)
                return@launch
            }
            val result = auto.apply(settled, dns)
            if (result != null) repository.record(ObservationEntry.now(
                "AUTO_APPLY;desired=${settled.desired};actual=${dns.readState().mode}",
                "result=$result;automatic_action",
            ))
        }
    }

    private fun lifecycle(event: String, recommendation: String = "NONE") {
        repository.record(ObservationEntry.now(
            "$event;ui=${if (ObservationPresence.foreground.value) "FOREGROUND" else "BACKGROUND"}" +
                ";elapsed_ms=${SystemClock.elapsedRealtime()}",
            "$recommendation;diagnostic_only",
        ))
    }

    private fun fail(error: RuntimeException) {
        if (failed) return
        failed = true
        auto.disable()
        lifecycle("SERVICE_ERROR_${error.javaClass.simpleName}", "BLOCKED")
        repository.setRunState(ObservationRunState.STOPPED, failed = true)
        stopSelf()
    }

    private fun promoteToForeground() {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(
            CHANNEL, getString(R.string.observation_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) })
        val open = PendingIntent.getActivity(this, 40, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 41,
            Intent(this, NetworkObservationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_dns_tile)
            .setContentTitle(getString(R.string.observation_notification_title))
            .setContentText(getString(when {
                auto.state.value.pausedUntilMs != null -> R.string.auto_paused
                auto.state.value.enabled -> R.string.auto_notification_body
                else -> R.string.observation_notification_body
            }))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null, getString(R.string.observation_stop), stop).build())
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        auto.disable()
        scope.cancel()
        if (::monitor.isInitialized) monitor.stop()
        if (receiverRegistered) unregisterReceiver(accessReceiver)
        if (providerObserved) contentResolver.unregisterContentObserver(providerObserver)
        repository.setRunState(ObservationRunState.STOPPED, failed)
        lifecycle("SERVICE_STOPPED")
        repository.flushOnStop()
        super.onDestroy()
    }

    companion object {
        const val ACTION_ENABLE_AUTO = "io.github.sorjo.pdnsautoswitch.ENABLE_AUTO"
        const val ACTION_STOP = "io.github.sorjo.pdnsautoswitch.STOP_OBSERVATION"
        private const val CHANNEL = "network_observation"
        private const val NOTIFICATION_ID = 40
    }
}
