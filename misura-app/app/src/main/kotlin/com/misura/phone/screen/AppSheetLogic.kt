package com.misura.phone.screen

/** A launcher app, its shortcut, or a future Misura-made entry. No Android types. */
data class SheetEntry(
    val pkg: String,
    val name: String,
    val shortcutId: String? = null,
    val shortcutLabel: String? = null,
    val made: Boolean = false,
) {
    val key: String get() = "$pkg/${shortcutId.orEmpty()}"
    val description: String get() = name + (shortcutLabel?.let { " · $it" } ?: "")
}

data class SheetRows(val pinned: List<SheetEntry>, val allApps: List<SheetEntry>, val filtered: List<SheetEntry>? = null) {
    val entries: List<SheetEntry> get() = filtered ?: (pinned + allApps)
}

private fun wordPrefix(name: String, query: String): Boolean =
    name.lowercase().let { it.startsWith(query) || it.split(' ').any { word -> word.startsWith(query) } }

/** Index zero is nearest the field in the reverse list. Filtering begins at two letters. */
fun sheetRows(apps: List<Pair<String, String>>, pins: List<SheetEntry>, launchCounts: Map<String, Int>, typed: String): SheetRows {
    val names = apps.associate { (name, pkg) -> pkg to name }
    val pinned = pins.distinctBy { it.key }.filter { it.made || it.pkg in names }
        .map { it.copy(name = names[it.pkg] ?: it.name) }
    val pinnedApps = pinned.filter { it.shortcutId == null }.map { it.pkg }.toSet()
    val all = apps.distinctBy { it.second }.filter { it.second !in pinnedApps }
        .sortedWith(compareByDescending<Pair<String, String>> { launchCounts[it.second] ?: 0 }
            .thenBy { it.first.lowercase() }.thenBy { it.second })
        .map { (name, pkg) -> SheetEntry(pkg, name) }
    val query = typed.trim().lowercase()
    val filtered = if (query.length < 2) null else (pinned + all)
        .filter { wordPrefix(it.name, query) || wordPrefix(it.shortcutLabel.orEmpty(), query) }
        .sortedBy { !it.name.lowercase().startsWith(query) && !it.shortcutLabel.orEmpty().lowercase().startsWith(query) }
    return SheetRows(pinned, all, filtered)
}

/** Keep finger travel linear between endpoints, with quarter-speed stretch beyond either end. */
internal fun sheetDragProgress(value: Float): Float = when {
    value < 0f -> value * .25f
    value > 1f -> 1f + (value - 1f) * .25f
    else -> value
}
