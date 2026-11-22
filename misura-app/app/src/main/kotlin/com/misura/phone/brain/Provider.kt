package com.misura.phone.brain

enum class ModelProvider(val label: String, val defaultModel: String) {
    VERTEX("Vertex", "gemini-3.8-flash"), GEMINI("Gemini", "gemini-3.8-flash"),
    OPENAI("OpenAI", "gpt-6.2-astra"), ANTHROPIC("Anthropic", "claude-opus-5-5")
}

enum class SearchProvider(val label: String) { NATIVE("Native"), EXA("Exa"), TAVILY("Tavily"), CONTEXT_DEV("Context.dev") }

/** Ordinary classes intentionally retain identity-only toString and cannot be serialized. */
class CredentialInput(val provider: ModelProvider, val model: String, val apiKey: String,
    val serviceAccount: String, val project: String, val exaKey: String,
    val searchProvider: SearchProvider = if (exaKey.isEmpty()) SearchProvider.NATIVE else SearchProvider.EXA,
    val searchKey: String = "")

class ModelRoute(val provider: ModelProvider, val model: String, val apiKey: String?,
    val handModel: String, val mainThinking: String?, val handThinking: String?,
    val serviceAccount: String?, val fallbackToken: String?, val project: String,
    val searchProvider: SearchProvider, val searchKey: String?,
    val googleCredentialUnreadable: Boolean, val searchCredentialUnreadable: Boolean) {
    val googleAvailable get() = project.isNotBlank() && (!serviceAccount.isNullOrBlank() || !fallbackToken.isNullOrBlank())
}
