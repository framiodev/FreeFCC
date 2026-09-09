package com.freefcc.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.*

class HeadlessAutoFccService : Service() {
    companion object {
        private const val CHANNEL_ID = "headless_auto_fcc"
        private const val NOTIF_ID = 9013
        fun start(context: Context) {
            val i = Intent(context, HeadlessAutoFccService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                context.startForegroundService(i)
            else context.startService(i)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var loopJob: Job? = null
    private val transport = DumlTransport()
    private var applied = false

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "FreeFCC Auto",
                NotificationManager.IMPORTANCE_MIN
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(ch)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        val n = builder
            .setContentTitle("FreeFCC")
            .setContentText("Auto-FCC arka planda aktif")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true).build()
        startForeground(NOTIF_ID, n)
        if (loopJob == null) loopJob = scope.launch { mainLoop() }
        return START_STICKY
    }

    private suspend fun mainLoop() {
        val fcc = Profiles.load(this, "fcc.json")
        val keep = Profiles.load(this, "fcc_keepalive.json")
        while (true) {
            try {
                val linked = transport.connect() &&
                             transport.probeSerial(500).isNotEmpty()
                if (linked && !applied) {
                    if (HardwareLock.tryBegin()) try {
                        transport.sendFrames(
                            fcc.frames, fcc.rounds,
                            fcc.interFrameDelay, fcc.interRoundDelay,
                            fcc.readWindowMs, fcc.port
                        )
                        applied = true
                    } finally { HardwareLock.end() }
                }
                if (applied) {
                    if (HardwareLock.tryBegin()) try {
                        transport.sendFrames(
                            keep.frames, 1,
                            keep.interFrameDelay, 0,
                            keep.readWindowMs, keep.port
                        )
                    } finally { HardwareLock.end() }
                    if (!linked) applied = false
                }
            } catch (_: Exception) {}
            delay(if (applied) 2000L else 3000L)
        }
    }

    override fun onDestroy() { loopJob?.cancel(); scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
