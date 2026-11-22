package com.misura.core.agent

/** Lower comes first; matching ignores case. Non-matches sort last. */
fun contactRank(displayName: String, name: String): Int {
    val query = name.trim()
    return when {
        query.isEmpty() -> 3
        displayName.equals(query, ignoreCase = true) -> 0
        displayName.startsWith(query, ignoreCase = true) -> 1
        displayName.contains(query, ignoreCase = true) -> 2
        else -> 3
    }
}
