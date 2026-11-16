package com.example.data.extensions.media

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.example.BuildConfig
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

/**
 * Audio played for extensions holding `background` (`OrthoTips.media`, bridge v5).
 *
 * The single source of truth: the extension page, the media notification, the
 * lock screen and Bluetooth headsets are all controllers of this one player,
 * and every change is pushed back to the page. Media3 posts the notification
 * and keeps the service in the foreground while it plays, so playback goes on
 * with the screen off.
 */
@OptIn(UnstableApi::class) // Media3's HTTP data source and builder options.
class ExtensionMediaService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Many radio servers redirect their old http:// address to https://.
        // HttpURLConnection never follows that by itself, so ExoPlayer would get
        // the 301/302 and fail with ERROR_CODE_IO_BAD_HTTP_STATUS.
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent("OrthoTips/${BuildConfig.VERSION_NAME} (Android) ExoPlayer")
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, http)))
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true          // pauses for calls, lowers for other apps
            )
            .setHandleAudioBecomingNoisy(true)         // headphones unplugged → pause
            .setWakeMode(C.WAKE_MODE_NETWORK)          // screen off: keep CPU and Wi-Fi for the stream
            .build()
        session = MediaSession.Builder(this, player)
            .setSessionActivity(openAppIntent())       // tap on the notification → the app
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: a playing stream keeps playing; otherwise the service stops. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun openAppIntent(): PendingIntent {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: Intent()
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
