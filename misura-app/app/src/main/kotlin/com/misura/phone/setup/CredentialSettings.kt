package com.misura.phone.setup

import android.content.SharedPreferences
import com.misura.phone.brain.GoogleAccess
import com.misura.phone.brain.CredentialInput
import com.misura.phone.brain.ModelProvider
import com.misura.phone.brain.ModelRoute
import com.misura.phone.brain.PrefsToken
import com.misura.phone.brain.SearchProvider
import com.misura.phone.debug.HAND_MODEL_KEY
import com.misura.phone.debug.HAND_THINK_KEY
import com.misura.phone.debug.MAIN_THINK_KEY
import com.misura.phone.brain.serviceAccountPrivateKey
import com.misura.phone.brain.vertexProject
import com.misura.phone.debug.EXA_KEY
import com.misura.phone.debug.SA_KEY
import com.misura.phone.store.StorageCipher
import com.misura.phone.store.privateString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/** Local setup only: keys are never returned to the screen or passed through the agent. */
class CredentialSettings(private val prefs: SharedPreferences, private val cipher: StorageCipher = StorageCipher.shared) {
    private fun raw(key: String) = prefs.getString(key, null)
    private fun secret(key: String) = raw(key)?.let(cipher::decrypt)?.takeIf { it.isNotBlank() }
    // A blank provisioned value clears a service. Unreadable ciphertext still belongs to that service.
    private fun stored(key: String) = raw(key)?.let { cipher.decrypt(it)?.isNotBlank() ?: true } ?: false
    private fun readSecret(key: String) = prefs.privateString(key, cipher)?.takeIf { it.isNotBlank() }
    private fun modelKey(p: ModelProvider) = "model_${p.name}"
    private fun apiKey(p: ModelProvider) = "api_key_${p.name}"
    private fun searchKey(p: SearchProvider) = if (p == SearchProvider.EXA) EXA_KEY else "search_key_${p.name}"
    fun providerSelection(): ModelProvider? {
        val name = raw("model_provider") ?: return ModelProvider.VERTEX
        return ModelProvider.entries.find { it.name == name }
    }

    fun searchSelection(): SearchProvider? {
        val name = raw("search_provider") ?: return if (stored(EXA_KEY)) SearchProvider.EXA else SearchProvider.NATIVE
        return SearchProvider.entries.find { it.name == name }
    }

    fun provider() = requireNotNull(providerSelection()) { "Unsupported model provider." }
    fun searchProvider() = requireNotNull(searchSelection()) { "Unsupported search provider." }
    fun model(provider: ModelProvider) = raw(modelKey(provider)) ?: provider.defaultModel
    fun hasServiceAccount() = stored(SA_KEY)
    fun hasExaKey() = stored(EXA_KEY)
    fun hasApiKey(provider: ModelProvider) = stored(apiKey(provider))
    fun hasSearchKey(provider: SearchProvider) = stored(searchKey(provider))
    private fun fallbackProject(sa: String?) = runCatching { vertexProject(sa, raw("project")) }.getOrDefault("")
    fun project() = synchronized(prefs) { raw(PROJECT)?.takeIf { it.isNotBlank() } ?: fallbackProject(readSecret(SA_KEY)) }

    private var lastGoogle: GoogleAccess? = null
    internal fun googleAccess(): GoogleAccess = synchronized(prefs) {
        val account = readSecret(SA_KEY)
        val fallback = if (account == null && !stored(SA_KEY)) readSecret(PrefsToken.KEY) else null
        val project = raw(PROJECT)?.takeIf { it.isNotBlank() } ?: fallbackProject(account)
        lastGoogle?.takeIf { it.account == account && it.fallback == fallback && it.project == project }
            ?: GoogleAccess(account, fallback, project).also { lastGoogle = it }
    }

    fun route(): ModelRoute = synchronized(prefs) {
        val p = provider()
        val m = model(p)
        val vertex = p == ModelProvider.VERTEX
        val google = googleAccess()
        val sa = google.account
        val unreadableAccount = sa == null && stored(SA_KEY)
        val token = google.fallback
        val project = google.project
        val search = searchProvider()
        val searchSecret = if (search == SearchProvider.NATIVE) null else readSecret(searchKey(search))
        val key = if (vertex) null else requireNotNull(readSecret(apiKey(p))) { "Selected provider key is unavailable." }
        if (vertex) require((sa != null || token != null) && project.isNotBlank()) { "Google credentials or project unavailable." }
        ModelRoute(p, m, key, if (vertex) raw(HAND_MODEL_KEY) ?: m else m,
            if (vertex) raw(MAIN_THINK_KEY) else null, if (vertex) raw(HAND_THINK_KEY) else null,
            sa, token, project, search, searchSecret,
            googleCredentialUnreadable = unreadableAccount || (sa == null && token == null && stored(PrefsToken.KEY)),
            searchCredentialUnreadable = search != SearchProvider.NATIVE && searchSecret == null && stored(searchKey(search)))
    }

    fun configured() = runCatching { route() }.isSuccess

    fun save(serviceAccount: String, project: String, exaKey: String) = synchronized(prefs) {
        val p = provider()
        saveConfiguration(CredentialInput(p, model(p), "", serviceAccount, project, exaKey,
            if (exaKey.isNotEmpty()) SearchProvider.EXA else searchProvider()))
    }

    fun saveProvider(provider: ModelProvider, model: String, apiKey: String) = synchronized(prefs) {
        saveConfiguration(CredentialInput(provider, model, apiKey, "", raw(PROJECT).orEmpty(), "", searchProvider()))
    }

    fun saveConfiguration(input: CredentialInput): Unit = synchronized(prefs) {
        val p = input.provider
        val search = input.searchProvider
        val sa = input.serviceAccount.trim()
        val selectedProject = input.project.trim()
        require(input.model.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Enter a valid model ID." }
        require(input.serviceAccount.length <= 65_536) { "Service-account JSON is too large." }
        if (sa.isNotEmpty()) validate(sa)
        val account = sa.ifEmpty { secret(SA_KEY).orEmpty() }
        if (p == ModelProvider.VERTEX) require(account.isNotEmpty() || (!stored(SA_KEY) && secret(PrefsToken.KEY) != null)) {
            "Paste a service-account JSON key. Saved credentials may be unavailable."
        }
        val effectiveProject = selectedProject.ifEmpty { fallbackProject(account) }
        if (effectiveProject.isNotEmpty() || p == ModelProvider.VERTEX || sa.isNotEmpty())
            require(effectiveProject.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}"))) { "Enter a project ID or number without spaces or slashes." }
        val changes = mutableMapOf<String, String?>("model_provider" to p.name, "search_provider" to search.name,
            modelKey(p) to input.model, PROJECT to selectedProject.ifEmpty { null })
        fun storeKey(name: String, value: String, required: Boolean) {
            require(value.length <= 2048 && value.all { it.code in 33..126 }) { "Keys must be a single line without spaces." }
            if (value.isNotEmpty()) changes[name] = cipher.encrypt(value)
            else if (required) require(secret(name) != null) { "Enter a key for the selected service." }
        }
        if (sa.isNotEmpty()) changes[SA_KEY] = cipher.encrypt(sa)
        if (p != ModelProvider.VERTEX) storeKey(apiKey(p), input.apiKey, true)
        if (input.exaKey.isNotEmpty() && search != SearchProvider.EXA) storeKey(EXA_KEY, input.exaKey, false)
        if (search != SearchProvider.NATIVE) storeKey(searchKey(search),
            if (search == SearchProvider.EXA) input.searchKey.ifEmpty { input.exaKey } else input.searchKey, true)
        val before = changes.keys.associateWith(::raw)
        fun commit(values: Map<String, String?>): Boolean {
            val editor = prefs.edit()
            values.forEach { (key, value) -> if (value == null) editor.remove(key) else editor.putString(key, value) }
            return editor.commit()
        }
        if (!commit(changes)) {
            commit(before)
            error("Could not save credentials. Try again.")
        }
    }

    private fun validate(source: String) {
        val json = runCatching { Json.parseToJsonElement(source).jsonObject }
            .getOrElse { throw IllegalArgumentException("Paste complete service-account JSON.") }
        fun field(key: String) = (json[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
        require(field("type") == "service_account") { "Use a service-account JSON key, not an API key or OAuth client file." }
        require(field("project_id").isNotBlank()) { "Service-account JSON is missing its project ID." }
        require(field("client_email").matches(Regex("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+"))) { "Service-account email is invalid." }
        try { serviceAccountPrivateKey(field("private_key")) }
        catch (_: Exception) { throw IllegalArgumentException("Service-account private key is invalid.") }
    }

    companion object { const val PROJECT = "setup_project" }
}
