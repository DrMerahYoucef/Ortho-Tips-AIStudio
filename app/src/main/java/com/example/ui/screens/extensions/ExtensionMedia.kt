package com.example.ui.screens.extensions

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.data.extensions.media.ExtensionMediaService
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONObject

/**
 * `OrthoTips.media` for one open extension: a [MediaController] on the app's
 * [ExtensionMediaService]. The extension only sees and controls its own item
 * (`mediaId = "<extensionId>/<itemId>"`); for anything else it reads `idle`.
 * Every change, whoever made it, is pushed to the page through [push].
 * Must be used on the main thread (Media3 requirement).
 */
@OptIn(UnstableApi::class) // PlaybackException details and the HTTP status of a failed stream.
internal class ExtensionMedia(
    private val context: Context,
    private val extensionId: String,
    /** The new state as JSON, to hand to `OrthoTips.__mediaChanged`. */
    private val push: (String) -> Unit
) : ExtensionMediaAccess {

    private val app = context.applicationContext
    private val main = ContextCompat.getMainExecutor(app)
    private var future: ListenableFuture<MediaController>? = null
    private var listening = false
    private val prefix = "$extensionId/"

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            (player as? MediaController)?.let { runCatching { push(state(it).toString()) } }
        }
    }

    private fun withController(fail: (Throwable) -> Unit, block: (MediaController) -> Unit) {
        val f = future ?: MediaController.Builder(app, SessionToken(app, ComponentName(app, ExtensionMediaService::class.java)))
            .buildAsync().also { future = it }
        f.addListener({
            try {
                val controller = f.get()
                if (!listening) { controller.addListener(listener); listening = true }
                block(controller)
            } catch (t: Throwable) {
                future = null
                listening = false
                fail(t)
            }
        }, main)
    }

    private fun owned(c: MediaController) = c.currentMediaItem?.mediaId?.startsWith(prefix) == true

    fun state(c: MediaController): JSONObject {
        val item = c.currentMediaItem?.takeIf { owned(c) }
        val status = when {
            item == null -> "idle"
            c.playerError != null -> "error"
            c.isPlaying -> "playing"
            c.playWhenReady -> "buffering"
            else -> "paused"
        }
        return JSONObject()
            .put("status", status)
            .put("error", if (item != null) errorText(c.playerError) else "")
            .put("volume", c.volume.toDouble())
            .put("item", item?.let {
                JSONObject().put("id", it.mediaId.removePrefix(prefix))
                    .put("url", it.localConfiguration?.uri?.toString().orEmpty())
                    .put("title", it.mediaMetadata.title?.toString().orEmpty())
                    .put("artist", it.mediaMetadata.artist?.toString().orEmpty())
                    .put("artworkUrl", it.mediaMetadata.artworkUri?.toString().orEmpty())
            } ?: JSONObject.NULL)
    }

    /**
     * "ERROR_CODE_IO_BAD_HTTP_STATUS (403)": the error name plus the HTTP status
     * when the server answered one, so the page can tell blocked (403) from
     * dead (404). Across the controller's Binder link Media3 only keeps the
     * cause's message ("Response code: 403"), hence the fallback on the text.
     */
    private fun errorText(error: PlaybackException?): String {
        if (error == null) return ""
        val cause = error.cause
        val code = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: cause?.message?.let { RESPONSE_CODE.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        return error.errorCodeName + (code?.let { " ($it)" } ?: "")
    }

    /** `media.play` …; [done] settles the page's promise with the state JSON, or an error message. */
    override fun call(method: String, args: JSONObject, done: (Boolean, String) -> Unit) {
        if (method == "media.play") {
            val url = args.optString("url")
            if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
                done(false, "Invalid stream URL")
                return
            }
        }
        withController({ done(false, it.message ?: "Media error") }) { c ->
            when (method) {
                "media.play" -> {
                    val art = args.optString("artworkUrl").takeIf { it.startsWith("https://", true) }
                    c.setMediaItem(
                        MediaItem.Builder()
                            .setMediaId(prefix + args.optString("id").take(200))
                            .setUri(args.optString("url"))
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(args.optString("title").take(200))
                                    .setArtist(args.optString("artist").take(200))
                                    .setArtworkUri(art?.let(Uri::parse))
                                    .build()
                            )
                            .build()
                    )
                    c.prepare()
                    c.play()
                }
                "media.pause" -> if (owned(c)) c.pause()
                "media.resume" -> if (owned(c)) { if (c.playerError != null) c.prepare(); c.play() }
                "media.stop" -> if (owned(c)) { c.stop(); c.clearMediaItems() }
                "media.setVolume" -> if (owned(c)) c.volume = args.optDouble("volume", 1.0).toFloat().coerceIn(0f, 1f)
                "media.state" -> Unit
                else -> { done(false, "Unknown method '$method'"); return@withController }
            }
            done(true, state(c).toString())
        }
    }

    /** The page goes away: the controller goes, the service keeps playing. */
    override fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        listening = false
    }

    companion object {
        private val RESPONSE_CODE = Regex("Response code: (\\d{3})")

        /**
         * Stops [extensionId]'s stream (deleted, data cleared, or updated
         * without `background`). Safe from any thread.
         */
        fun stopFor(context: Context, extensionId: String) {
            Handler(Looper.getMainLooper()).post {
                runCatching {
                    val media = ExtensionMedia(context, extensionId) {}
                    media.call("media.stop", JSONObject()) { _, _ -> media.release() }
                }.onFailure { Log.w("ExtensionMedia", "Could not stop $extensionId", it) }
            }
        }
    }
}
