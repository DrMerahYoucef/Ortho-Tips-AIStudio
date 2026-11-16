package com.example.ui.screens.extensions

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.JavascriptInterface
import androidx.activity.result.contract.ActivityResultContract
import com.example.data.extensions.ExtensionDatabaseAccess
import com.example.data.extensions.ExtensionFiles
import com.example.data.extensions.ExtensionManifest
import com.example.data.extensions.ExtensionPermission
import com.example.data.extensions.ExtensionStorage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The object behind `window.OrthoTipsNative`. The page never calls it directly:
 * `assets/extension_host/ortho-bridge.js` wraps it into `window.OrthoTips`.
 *
 * Permissions are enforced here, on every call (extension guide, section 4.4):
 * the JavaScript side checks too, for a quick and clear rejection, but a page
 * that bypasses it still gets nothing it was not granted. Anything not listed
 * in [call] is refused, so a new capability is denied until it is wired here.
 *
 * WebView calls these methods on its own JavaBridge thread; slow work goes to a
 * private background thread, and anything touching the UI is posted to main.
 */
internal class ExtensionBridge(
    private val storage: ExtensionStorage,
    private val granted: Set<ExtensionPermission>,
    private val files: ExtensionFiles?,
    private val contentDir: File?,
    private val database: ExtensionDatabaseAccess,
    private val infoJson: () -> String,
    private val onToast: (String) -> Unit,
    private val onClose: () -> Unit,
    private val onSaveFile: (requestId: String, fileName: String, mimeType: String, bytes: ByteArray?) -> Unit,
    /** Settles a promise from [call]: `value` is a JSON literal, or an error message when `ok` is false. */
    private val onSettle: (requestId: String, ok: Boolean, value: String) -> Unit,
    /** A refused call, shown in the extension's console. */
    private val onDenied: (String) -> Unit,
    /** Bank SMS for this extension; null when it does not hold `sms`. */
    private val sms: ExtensionSmsAccess? = null,
    /** Shows Android's SMS permission dialog; the answer settles [requestId]. */
    private val onRequestSmsAccess: (requestId: String) -> Unit = {},
    /** Reminders of this extension; null when it does not hold `notifications`. */
    private val notifications: ExtensionNotificationAccess? = null,
    /** Shows Android's notification permission dialog (or settings); the answer settles [requestId]. */
    private val onRequestNotificationAccess: (requestId: String) -> Unit = {},
    /** Patient records; null when the extension does not hold `patients`. */
    private val patients: ExtensionPatientAccess? = null,
    /** Audio in the app's media service; null when the extension does not hold `background`. */
    private val media: ExtensionMediaAccess? = null,
    /** Gemini through the app's key; null when the extension does not hold `gemini`. */
    private val gemini: ExtensionGeminiAccess? = null,
    /** Opens Réglages → Gemini (OrthoTips.gemini.openSettings). */
    private val onOpenGeminiSettings: () -> Unit = {}
) {
    private val main = Handler(Looper.getMainLooper())
    private val worker: ExecutorService = Executors.newSingleThreadExecutor()
    /** Gemini calls can take a minute: their own threads, so storage and files never wait. */
    private val slow: ExecutorService = Executors.newCachedThreadPool()

    fun dispose() {
        worker.shutdownNow()
        slow.shutdownNow()
        // The page goes away: its controller goes, the media service keeps playing.
        media?.release()
    }

    @JavascriptInterface
    fun info(): String = infoJson()

    // ---- key/value storage: no permission needed ---------------------------------

    @JavascriptInterface
    fun storageGet(key: String?): String? = key?.let(storage::get)

    @JavascriptInterface
    fun storageSet(key: String?, value: String?): Boolean =
        if (key == null || value == null) false else storage.set(key, value)

    @JavascriptInterface
    fun storageDelete(key: String?) {
        key?.let(storage::delete)
    }

    @JavascriptInterface
    fun storageKeys(prefix: String?): String = JSONArray(storage.keys(prefix.orEmpty())).toString()

    // ---- misc ------------------------------------------------------------------

    @JavascriptInterface
    fun toast(message: String?) {
        val text = message?.take(300)?.takeIf { it.isNotBlank() } ?: return
        main.post { onToast(text) }
    }

    @JavascriptInterface
    fun close() {
        main.post { onClose() }
    }

    /** `OrthoTips.saveFile`: needs `import_export`. Resolves false when refused. */
    @JavascriptInterface
    fun saveFile(requestId: String?, fileName: String?, mimeType: String?, base64: String?) {
        val id = requestId ?: return
        if (!allowed(ExtensionPermission.IMPORT_EXPORT)) {
            main.post { onSaveFile(id, "", "", null) }
            return
        }
        val bytes = runCatching { Base64.decode(base64.orEmpty(), Base64.DEFAULT) }.getOrNull()
        val name = cleanFileName(fileName)
        val mime = mimeType?.trim()?.takeIf { it.contains('/') } ?: "application/octet-stream"
        main.post { onSaveFile(id, name, mime, bytes) }
    }

    // ---- gated, asynchronous capabilities ----------------------------------------

    /**
     * One entry point for every promise-based capability. [method] picks the
     * capability, [argsJson] carries its arguments; the answer arrives through
     * [onSettle] under [requestId].
     */
    @JavascriptInterface
    fun call(requestId: String?, method: String?, argsJson: String?) {
        val id = requestId ?: return
        val name = method.orEmpty()
        val required = REQUIRED[name]
        if (required == null) {
            settle(id, false, "Unknown method '$name'")
            return
        }
        if (!allowed(required)) {
            settle(id, false, "PermissionError: '${required.key}' not granted")
            return
        }
        if (name == "sms.requestAccess") {
            // A system dialog: must be launched from the UI, answered later.
            main.post { onRequestSmsAccess(id) }
            return
        }
        if (name.startsWith("gemini.")) {
            val access = gemini ?: run { settle(id, false, "Gemini is unavailable"); return }
            if (name == "gemini.openSettings") {
                main.post { onOpenGeminiSettings() }
                settle(id, true, "true")
                return
            }
            val args = runCatching { if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson) }
                .getOrElse { settle(id, false, "Invalid arguments"); return }
            slow.execute {
                try {
                    settle(id, true, (if (name == "gemini.status") access.status() else access.generate(args)).toString())
                } catch (e: com.example.data.ai.GeminiException) {
                    // The page gets an Error with name 'GeminiError' and kind 'quota', 'key', 'network'…
                    settle(id, false, "GeminiError(${e.kind.name.lowercase()}): ${e.message}")
                } catch (e: Exception) {
                    settle(id, false, e.message ?: e.javaClass.simpleName)
                }
            }
            return
        }
        if (name.startsWith("media.")) {
            // Media3 controllers live on the main thread and answer asynchronously.
            val access = media ?: run { settle(id, false, "Media is unavailable"); return }
            val args = runCatching { if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson) }
                .getOrElse { settle(id, false, "Invalid arguments"); return }
            main.post { access.call(name, args) { ok, value -> settle(id, ok, value) } }
            return
        }
        if (name == "notifications.requestAccess") {
            // A system dialog or the settings screen: launched from the UI, answered later.
            main.post { onRequestNotificationAccess(id) }
            return
        }
        worker.execute {
            val result = runCatching {
                val args = if (argsJson.isNullOrBlank()) JSONObject() else JSONObject(argsJson)
                dispatch(name, args)
            }
            result.fold(
                onSuccess = { settle(id, true, it) },
                onFailure = { settle(id, false, it.message ?: it.javaClass.simpleName) }
            )
        }
    }

    /** Returns a JSON literal. */
    private fun dispatch(method: String, args: JSONObject): String = when (method) {
        "files.write" -> {
            val bytes = Base64.decode(args.optString("base64"), Base64.DEFAULT)
            requireFiles().write(args.getString("path"), bytes)
            "true"
        }
        "files.read" -> requireFiles().read(args.getString("path"))?.let(::base64Literal) ?: "null"
        "files.list" -> JSONArray(requireFiles().list(args.optString("prefix"))).toString()
        "files.delete" -> requireFiles().delete(args.getString("path")).toString()
        "assets.read" -> readAsset(args.getString("path"))?.let(::base64Literal) ?: "null"
        "database.query" -> database.query(args).toString()
        "sms.status" -> requireSms().status().toString()
        "sms.pending" -> requireSms().pending().toString()
        "sms.history" -> requireSms().history().toString()
        "sms.ack" -> {
            requireSms().ack(args.getLong("upTo"))
            "true"
        }
        "notifications.status" -> requireNotifications().status().toString()
        "notifications.schedule" -> requireNotifications().schedule(args).toString()
        "notifications.cancel" -> requireNotifications().cancel(args.getString("id")).toString()
        "notifications.cancelAll" -> requireNotifications().cancelAll().toString()
        "notifications.list" -> requireNotifications().list().toString()
        "patients.search" -> requirePatients().search(args.optString("query"), args.optInt("limit", 50)).toString()
        "patients.get" -> (requirePatients().get(args.getString("id")) ?: JSONObject.NULL).toString()
        "patients.getByCode" -> (requirePatients().getByCode(args.getString("code")) ?: JSONObject.NULL).toString()
        "patients.create" -> requirePatients().create(args.optJSONObject("fields") ?: JSONObject()).toString()
        "patients.update" -> requirePatients().update(args.getString("id"), args.optJSONObject("fields") ?: JSONObject()).toString()
        "patients.addMedia" -> requirePatients().addMedia(args.getString("patientId"), args).toString()
        else -> throw IllegalArgumentException("Unknown method '$method'")
    }

    private fun readAsset(path: String): ByteArray? {
        val root = contentDir ?: return null
        val clean = path.trim().trimStart('/')
        require(ExtensionManifest.isSafeRelativePath(clean)) { "Invalid path '$path'" }
        return File(root, clean).takeIf { it.isFile }?.readBytes()
    }

    private fun requireNotifications(): ExtensionNotificationAccess =
        notifications ?: throw IllegalStateException("Notifications are unavailable")

    private fun requirePatients(): ExtensionPatientAccess =
        patients ?: throw IllegalStateException("Patient records are unavailable")

    private fun requireFiles(): ExtensionFiles = files ?: throw IllegalStateException("Storage is unavailable")

    private fun requireSms(): ExtensionSmsAccess = sms ?: throw IllegalStateException("SMS is unavailable")

    private fun allowed(permission: ExtensionPermission): Boolean {
        if (permission in granted && permission.implemented) return true
        val reason = if (permission.implemented) "not granted" else "not available yet"
        main.post { onDenied("PermissionError: '${permission.key}' $reason") }
        return false
    }

    private fun settle(id: String, ok: Boolean, value: String) {
        main.post { onSettle(id, ok, value) }
    }

    private fun base64Literal(bytes: ByteArray): String =
        JSONObject.quote(Base64.encodeToString(bytes, Base64.NO_WRAP))

    private fun cleanFileName(name: String?): String =
        name.orEmpty()
            .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
            .trim()
            .take(120)
            .ifEmpty { "fichier" }

    companion object {
        /** Bridge API version exposed as `OrthoTips.bridgeVersion`. */
        const val BRIDGE_VERSION = 6

        /** Method → permission it needs. Anything absent is refused. */
        private val REQUIRED = mapOf(
            "files.write" to ExtensionPermission.STORAGE,
            "files.read" to ExtensionPermission.STORAGE,
            "files.list" to ExtensionPermission.STORAGE,
            "files.delete" to ExtensionPermission.STORAGE,
            "assets.read" to ExtensionPermission.ASSETS,
            "database.query" to ExtensionPermission.DATABASE,
            "gemini.status" to ExtensionPermission.GEMINI,
            "gemini.generate" to ExtensionPermission.GEMINI,
            "gemini.openSettings" to ExtensionPermission.GEMINI,
            "media.play" to ExtensionPermission.BACKGROUND,
            "media.pause" to ExtensionPermission.BACKGROUND,
            "media.resume" to ExtensionPermission.BACKGROUND,
            "media.stop" to ExtensionPermission.BACKGROUND,
            "media.setVolume" to ExtensionPermission.BACKGROUND,
            "media.state" to ExtensionPermission.BACKGROUND,
            "patients.search" to ExtensionPermission.PATIENTS,
            "patients.get" to ExtensionPermission.PATIENTS,
            "patients.getByCode" to ExtensionPermission.PATIENTS,
            "patients.create" to ExtensionPermission.PATIENTS,
            "patients.update" to ExtensionPermission.PATIENTS,
            "patients.addMedia" to ExtensionPermission.PATIENTS,
            "notifications.status" to ExtensionPermission.NOTIFICATIONS,
            "notifications.requestAccess" to ExtensionPermission.NOTIFICATIONS,
            "notifications.schedule" to ExtensionPermission.NOTIFICATIONS,
            "notifications.cancel" to ExtensionPermission.NOTIFICATIONS,
            "notifications.cancelAll" to ExtensionPermission.NOTIFICATIONS,
            "notifications.list" to ExtensionPermission.NOTIFICATIONS,
            "sms.status" to ExtensionPermission.SMS,
            "sms.requestAccess" to ExtensionPermission.SMS,
            "sms.pending" to ExtensionPermission.SMS,
            "sms.ack" to ExtensionPermission.SMS,
            "sms.history" to ExtensionPermission.SMS
        )
    }
}

/**
 * What the bridge needs for `OrthoTips.sms`, bound to one extension so it can
 * only read and acknowledge its own queue.
 */
internal interface ExtensionSmsAccess {
    fun status(): JSONObject
    fun pending(): JSONArray
    fun ack(upTo: Long)
    fun history(): JSONArray
}

/**
 * What the bridge needs for `OrthoTips.notifications`, bound to one extension:
 * it only sees, replaces and cancels its own reminders.
 */
internal interface ExtensionNotificationAccess {
    fun status(): JSONObject
    fun schedule(args: JSONObject): JSONObject
    fun cancel(id: String): Boolean
    fun cancelAll(): Int
    fun list(): JSONArray
}

/** What the bridge needs for `OrthoTips.gemini`: the app's key stays in the app. Blocking calls. */
internal interface ExtensionGeminiAccess {
    /** `{ available, configured, model }` */
    fun status(): JSONObject
    /** `{ model, response }`: Google's answer to `args.body`. Throws GeminiException. */
    fun generate(args: JSONObject): JSONObject
}

/** What the bridge needs for `OrthoTips.media` (permission `background`): answers on the main thread. */
internal interface ExtensionMediaAccess {
    /** [done] gets the state JSON, or an error message. */
    fun call(method: String, args: JSONObject, done: (Boolean, String) -> Unit)
    fun release()
}

/** What the bridge needs for `OrthoTips.patients`: read, create, update, add media — never delete. */
internal interface ExtensionPatientAccess {
    fun search(query: String, limit: Int): JSONArray
    fun get(id: String): JSONObject?
    fun getByCode(code: String): JSONObject?
    fun create(fields: JSONObject): JSONObject
    fun update(id: String, fields: JSONObject): JSONObject
    fun addMedia(patientId: String, args: JSONObject): JSONObject
}

/** "Save as…" with a file name and type chosen at launch time. */
internal class CreateDocumentWithType : ActivityResultContract<CreateDocumentWithType.Input, android.net.Uri?>() {

    data class Input(val fileName: String, val mimeType: String)

    override fun createIntent(context: Context, input: Input): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(input.mimeType)
            .putExtra(Intent.EXTRA_TITLE, input.fileName)

    override fun parseResult(resultCode: Int, intent: Intent?): android.net.Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
