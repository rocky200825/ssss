package com.example.phonemic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Records the phone microphone and streams 16-bit mono 16 kHz PCM to the
 * Windows receiver. Runs as a foreground service so it keeps going with the
 * screen off. Reconnects automatically if the hotspot or Wi-Fi drops.
 */
class MicService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.phonemic.STOP"
        const val RATE = 16000
        private const val CHANNEL_ID = "mic_stream"
        private const val NOTIF_ID = 1

        @Volatile var running = false
        @Volatile var status = "Stopped"
    }

    @Volatile private var keepRunning = false
    private var worker: Thread? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (running) return START_STICKY

        val prefs = getSharedPreferences("phonemic", Context.MODE_PRIVATE)
        val host = prefs.getString("host", "") ?: ""
        val port = prefs.getInt("port", 5555)
        val code = prefs.getString("code", "") ?: ""

        createChannel()
        val notif = buildNotification("Starting...")
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "phonemic:stream").apply {
            setReferenceCounted(false)
            acquire()
        }

        running = true
        keepRunning = true
        worker = Thread { streamLoop(host, port, code) }.also { it.start() }
        return START_STICKY
    }

    override fun onDestroy() {
        keepRunning = false
        worker?.join(1000)
        wakeLock?.let { if (it.isHeld) it.release() }
        running = false
        status = "Stopped"
        super.onDestroy()
    }

    private fun streamLoop(host: String, port: Int, code: String) {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            status = "Microphone permission missing"
            stopSelf()
            return
        }

        val minBuf = AudioRecord.getMinBufferSize(
            RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
        )
        recorder.startRecording()
        val buffer = ByteArray(640 * 2) // 40 ms of audio

        var socket: Socket? = null
        var out: OutputStream? = null

        while (keepRunning) {
            if (out == null) {
                try {
                    status = "Connecting to $host:$port..."
                    val s = Socket()
                    s.connect(InetSocketAddress(host, port), 3000)
                    s.tcpNoDelay = true
                    s.soTimeout = 5000
                    val o = s.getOutputStream()
                    o.write("PAIR $code\n".toByteArray())
                    o.flush()
                    val reply = s.getInputStream().bufferedReader().readLine()
                    if (reply != "OK") {
                        s.close()
                        status = "Computer rejected the pairing code"
                        keepRunning = false
                        break
                    }
                    socket = s
                    out = o
                    status = "Streaming to $host"
                    updateNotification(status)
                } catch (e: IOException) {
                    status = "Can't reach computer, retrying..."
                    updateNotification(status)
                    Thread.sleep(2000)
                    continue
                }
            }

            val n = recorder.read(buffer, 0, buffer.size)
            if (n > 0) {
                try {
                    out?.write(buffer, 0, n)
                } catch (e: IOException) {
                    out = null
                    socket?.close()
                    socket = null
                    status = "Connection lost, reconnecting..."
                    updateNotification(status)
                }
            }
        }

        recorder.stop()
        recorder.release()
        socket?.close()
        running = false
        status = "Stopped"
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "Mic streaming", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, MicService::class.java).setAction(ACTION_STOP)
        val stopPending = android.app.PendingIntent.getService(
            this, 0, stopIntent,
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Phone Mic is streaming")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .addAction(0, "Stop", stopPending)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
    }
}
