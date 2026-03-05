package com.nova.companion.biohack.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class NeuroAudioService : Service() {
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var isPlaying = false
    private var audioTrack: AudioTrack? = null
    
    // Config
    private val sampleRate = 44100
    private var leftFreq = 200.0
    private var rightFreq = 206.0
    private var isochronicFreq: Double? = 396.0
    private var baseVolume = 0.5f

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        when (action) {
            "START_FEARLESS" -> startFrequencies(200.0, 206.0, 396.0, "Fearless Mode (6Hz Theta)")
            "START_GOD_MODE" -> startFrequencies(150.0, 190.0, null, "God Mode (40Hz Gamma)")
            "START_INTELLIGENT" -> startFrequencies(250.0, 260.0, null, "Flow State (10Hz Alpha)")
            "START_RECOVERY" -> startFrequencies(100.0, 102.0, null, "Recovery (2Hz Delta)")
            "STOP" -> stopSelf()
        }
        return START_STICKY
    }

    private fun startFrequencies(left: Double, right: Double, iso: Double?, title: String) {
        this.leftFreq = left
        this.rightFreq = right
        this.isochronicFreq = iso
        
        startForeground(999, createNotification(title))
        
        if (!isPlaying) {
            isPlaying = true
            startSynthesis()
        }
    }

    private fun startSynthesis() {
        serviceScope.launch {
            val bufferSize = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        // Intentionally NOT requesting AudioFocus so it mixes with Spotify/YouTube
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bufferSize)
                .build()

            audioTrack?.play()

            val audioData = ShortArray(bufferSize)
            var time = 0.0
            val dt = 1.0 / sampleRate

            while (isPlaying && isActive) {
                for (i in 0 until bufferSize step 2) {
                    var l = sin(2 * PI * leftFreq * time)
                    var r = sin(2 * PI * rightFreq * time)

                    if (isochronicFreq != null) {
                        val beatFreq = Math.abs(rightFreq - leftFreq)
                        val isoPulse = sin(2 * PI * isochronicFreq!! * time)
                        val pulse = 0.5 * (1 + cos(2 * PI * beatFreq * time))
                        val isoPulsed = isoPulse * pulse
                        
                        l = l * 0.6 + isoPulsed * 0.4
                        r = r * 0.6 + isoPulsed * 0.4
                    }

                    audioData[i] = (l * 32767 * baseVolume).toInt().toShort()
                    audioData[i + 1] = (r * 32767 * baseVolume).toInt().toShort()
                    
                    time += dt
                }
                audioTrack?.write(audioData, 0, bufferSize)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.release()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            "neuro_audio",
            "Nova Brainwave Entrainment",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    private fun createNotification(title: String) = NotificationCompat.Builder(this, "neuro_audio")
        .setContentTitle("Nova: $title")
        .setContentText("Neural frequency entrainment active")
        .setSmallIcon(android.R.drawable.ic_media_play) // Fallback icon
        .setOngoing(true)
        .build()
}
