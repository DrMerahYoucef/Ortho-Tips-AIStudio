package com.example.data.ai

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.data.crypto.KeystoreManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Gemini failure, with a message the surgeon can act on. */
class GeminiException(val kind: Kind, message: String) : Exception(message) {
    enum class Kind { NO_KEY, KEY, QUOTA, MODEL, NETWORK, SERVER, BLOCKED, FORMAT }
}

/** The answer and the model that produced it. */
data class GeminiResult(val model: String, val text: String)

/**
 * The app's Gemini access (Réglages → Gemini, and the patient assistant).
 *
 * - The API key is encrypted with the app's Android Keystore key before it is
 *   stored; it cannot be read from a backup or on another phone (it then
 *   counts as missing).
 * - The model list is read from Google at most every 12 h; the newest model
 *   of the chosen family is used, and if a model fails the next one is tried.
 */
class GeminiService(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("gemini_settings", Context.MODE_PRIVATE)
    private val keystore by lazy { KeystoreManager() }
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    // ---- settings ---------------------------------------------------------------------

    fun apiKey(): String? = prefs.getString(KEY_API, null)?.let { stored ->
        runCatching { String(keystore.decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8) }.getOrNull()
    }?.takeIf { it.isNotBlank() }

    fun hasKey(): Boolean = apiKey() != null

    fun saveApiKey(key: String) {
        val clean = key.trim()
        require(clean.isNotEmpty()) { "Clé vide" }
        val sealed = Base64.encodeToString(keystore.encrypt(clean.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString(KEY_API, sealed).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API).remove(KEY_MODELS).remove(KEY_MODELS_AT).remove(KEY_LAST_MODEL).apply()
    }

    var preference: String
        get() = prefs.getString(KEY_PREFERENCE, GeminiModels.DEFAULT_PREFERENCE) ?: GeminiModels.DEFAULT_PREFERENCE
        set(value) { prefs.edit().putString(KEY_PREFERENCE, value).apply() }

    fun cachedModels(): List<GeminiModel> = runCatching {
        val a = JSONArray(prefs.getString(KEY_MODELS, "[]"))
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { GeminiModels.parse(it.optString("id"), it.optString("name")) } }
    }.getOrDefault(emptyList())

    fun modelsCheckedAt(): Long = prefs.getLong(KEY_MODELS_AT, 0L)

    /** The model that answered last (shown in settings). */
    fun lastModel(): String? = prefs.getString(KEY_LAST_MODEL, null)

    /** The model that will be tried first. */
    fun currentModel(): String = GeminiModels.candidates(cachedModels(), preference).first()

    // ---- network ----------------------------------------------------------------------------

    /** Reads the model list from Google (cached 12 h unless [force]). */
    suspend fun refreshModels(force: Boolean): List<GeminiModel> = withContext(Dispatchers.IO) {
        val cached = cachedModels()
        if (!force && cached.isNotEmpty() && System.currentTimeMillis() - modelsCheckedAt() < CACHE_MS) return@withContext cached
        val key = apiKey() ?: throw GeminiException(GeminiException.Kind.NO_KEY, "Aucune clé Gemini n’est enregistrée.")
        val found = mutableListOf<GeminiModel>()
        var page: String? = null
        do {
            val url = "$BASE/models?pageSize=1000" + (page?.let { "&pageToken=$it" } ?: "")
            val json = execute(Request.Builder().url(url).header("x-goog-api-key", key).get().build())
            val models = json.optJSONArray("models") ?: JSONArray()
            for (i in 0 until models.length()) {
                val m = models.optJSONObject(i) ?: continue
                val methods = m.optJSONArray("supportedGenerationMethods")
                val generates = methods != null && (0 until methods.length()).any { methods.optString(it) == "generateContent" }
                if (generates) GeminiModels.parse(m.optString("name"), m.optString("displayName"))?.let { found += it }
            }
            page = json.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (page != null)
        val sorted = GeminiModels.sorted(found)
        prefs.edit()
            .putString(KEY_MODELS, JSONArray(sorted.map { JSONObject().put("id", it.id).put("name", it.displayName) }).toString())
            .putLong(KEY_MODELS_AT, System.currentTimeMillis())
            .apply()
        sorted
    }

    /**
     * One structured call: [contents] is the conversation (user/model turns),
     * the answer follows [schema]. Tries the candidate models in order.
     */
    suspend fun generateJson(system: String, contents: JSONArray, schema: JSONObject, temperature: Double = 0.2): GeminiResult =
        withContext(Dispatchers.IO) {
            val key = apiKey() ?: throw GeminiException(GeminiException.Kind.NO_KEY, "Aucune clé Gemini n’est enregistrée.")
            runCatching { refreshModels(force = false) }.onFailure { Log.w(TAG, "Model list not refreshed", it) }
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("responseSchema", schema)
                    .put("temperature", temperature))
                .toString()
            val (model, json) = callModels(key, body, preferred = null)
            GeminiResult(model, extractText(json))
        }

    /**
     * For extensions holding `gemini` (`OrthoTips.gemini.generate`): sends
     * [request] — a generateContent body — with the app's key, to
     * [preferredModel] first when given, then the app's models with the same
     * fallback. The key never reaches the extension. Returns Google's JSON as is.
     */
    suspend fun generateRaw(request: JSONObject, preferredModel: String?): GeminiResult = withContext(Dispatchers.IO) {
        val key = apiKey() ?: throw GeminiException(GeminiException.Kind.NO_KEY, "Aucune clé Gemini n’est configurée dans Réglages → Gemini.")
        runCatching { refreshModels(force = false) }.onFailure { Log.w(TAG, "Model list not refreshed", it) }
        val (model, json) = callModels(key, request.toString(), preferredModel)
        GeminiResult(model, json.toString())
    }

    /** POSTs [body] to the candidate models in order ([preferred] first if given); a missing or busy model hands over to the next. */
    private fun callModels(key: String, body: String, preferred: String?): Pair<String, JSONObject> {
        val order = LinkedHashSet<String>()
        preferred?.trim()?.removePrefix("models/")?.takeIf { it.matches(Regex("[A-Za-z0-9._-]{1,80}")) }?.let { order += it }
        order += GeminiModels.candidates(cachedModels(), preference)
        var last: GeminiException? = null
        for (model in order) {
            try {
                val json = execute(
                    Request.Builder().url("$BASE/models/$model:generateContent").header("x-goog-api-key", key)
                        .post(body.toRequestBody(JSON)).build()
                )
                prefs.edit().putString(KEY_LAST_MODEL, model).apply()
                return model to json
            } catch (e: GeminiException) {
                last = e
                // A missing or busy model: the next one may work. Anything else is final.
                if (e.kind != GeminiException.Kind.MODEL && e.kind != GeminiException.Kind.SERVER && e.kind != GeminiException.Kind.QUOTA) throw e
                Log.w(TAG, "$model failed (${e.kind}), trying the next model")
            }
        }
        throw last ?: GeminiException(GeminiException.Kind.MODEL, "Aucun modèle Gemini disponible.")
    }

    /** Settings → Tester: one tiny call. Returns the model that answered. */
    suspend fun test(): String {
        val schema = JSONObject().put("type", "OBJECT").put("properties", JSONObject().put("ok", JSONObject().put("type", "BOOLEAN")))
            .put("required", JSONArray().put("ok"))
        val contents = JSONArray().put(JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", "Réponds ok = true."))))
        return generateJson("Tu réponds uniquement en JSON.", contents, schema).model
    }

    private fun execute(request: Request): JSONObject {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw GeminiException(GeminiException.Kind.NETWORK, "Pas de connexion à Google. Vérifiez Internet et réessayez.")
        }
        response.use { r ->
            val text = r.body?.string().orEmpty()
            if (r.isSuccessful) return runCatching { JSONObject(text) }.getOrElse {
                throw GeminiException(GeminiException.Kind.FORMAT, "Réponse inattendue de Google.")
            }
            val message = runCatching { JSONObject(text).optJSONObject("error")?.optString("message") }.getOrNull().orEmpty()
            throw when {
                r.code == 400 && message.contains("API key", ignoreCase = true) ->
                    GeminiException(GeminiException.Kind.KEY, "Clé Gemini refusée par Google. Vérifiez-la dans Réglages → Gemini.")
                r.code == 401 || r.code == 403 ->
                    GeminiException(GeminiException.Kind.KEY, "Clé Gemini refusée par Google. Vérifiez-la dans Réglages → Gemini.")
                r.code == 404 -> GeminiException(GeminiException.Kind.MODEL, "Modèle indisponible.")
                r.code == 429 -> GeminiException(GeminiException.Kind.QUOTA, "Limite d’utilisation Gemini atteinte. Réessayez plus tard.")
                r.code >= 500 -> GeminiException(GeminiException.Kind.SERVER, "Google est surchargé. Réessayez dans un instant.")
                else -> GeminiException(GeminiException.Kind.FORMAT, "Requête refusée par Google (${r.code}). ${message.take(200)}")
            }
        }
    }

    private fun extractText(json: JSONObject): String {
        json.optJSONObject("promptFeedback")?.optString("blockReason")?.takeIf { it.isNotBlank() }?.let {
            throw GeminiException(GeminiException.Kind.BLOCKED, "Demande bloquée par Google ($it). Reformulez la question.")
        }
        val candidate = json.optJSONArray("candidates")?.optJSONObject(0)
            ?: throw GeminiException(GeminiException.Kind.FORMAT, "Gemini n’a rien répondu.")
        val reason = candidate.optString("finishReason")
        if (reason == "SAFETY" || reason == "PROHIBITED_CONTENT" || reason == "BLOCKLIST") {
            throw GeminiException(GeminiException.Kind.BLOCKED, "Réponse bloquée par Google. Reformulez la question.")
        }
        val parts = candidate.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
        val text = (0 until parts.length()).joinToString("") { parts.optJSONObject(it)?.optString("text").orEmpty() }
        if (text.isBlank()) throw GeminiException(GeminiException.Kind.FORMAT, "Gemini n’a rien répondu" + (if (reason == "MAX_TOKENS") " (réponse trop longue)." else "."))
        return text
    }

    companion object {
        private const val TAG = "Gemini"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val CACHE_MS = 12L * 60 * 60 * 1000
        private const val KEY_API = "api_key_sealed"
        private const val KEY_PREFERENCE = "model_preference"
        private const val KEY_MODELS = "models"
        private const val KEY_MODELS_AT = "models_at"
        private const val KEY_LAST_MODEL = "last_model"
    }
}
