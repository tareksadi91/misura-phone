package com.misura.phone.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.misura.core.agent.Citation
import com.misura.phone.cards.Hanken
import com.misura.phone.cards.Jost
import com.misura.phone.cards.M
import com.misura.phone.cards.Plex
import com.misura.phone.cards.T
import com.misura.phone.cards.s
import com.misura.phone.cards.u
import java.net.URI

/**
 * One source behind an answer. Its number is its place in the provider's own list (Exa already writes "[n]" into the
 * answer by that order), so the tags never renumber; the same address cited twice keeps its first number.
 */
data class Source(val n: Int, val title: String, val url: String, val host: String)

/** What the source sheet shows: one source, or the whole list ("+N"). */
sealed interface SourceSheet {
    data class One(val source: Source) : SourceSheet
    data object All : SourceSheet
}

fun sourcesOf(citations: List<Citation>, line: String): List<Source> {
    val seen = mutableSetOf<String>()
    return citations.withIndex().mapNotNull { (i, c) ->
        if (!c.validFor(line) || !seen.add(c.url)) null else Source(i + 1, c.title, c.url, hostOf(c.url))
    }
}

/** The ASCII host as parsed (punycode stays punycode, subdomains stay), minus a leading "www.". */
fun hostOf(url: String): String = runCatching { URI(url).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotEmpty() } ?: url

const val SHOWN_SOURCES = 3
const val SOURCE_TAGS_TAG = "sourceTags"
private val tagText = TextStyle(fontFamily = Plex, fontSize = 8.s, color = M.text)

/** One line of tags under a sourced answer: "1 site" for the first three sources, then "+N" for the rest. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SourceTags(sources: List<Source>, onOpen: (SourceSheet) -> Unit, modifier: Modifier = Modifier) =
    FlowRow(modifier.testTag(SOURCE_TAGS_TAG), horizontalArrangement = Arrangement.spacedBy(4.u)) {
        for (s in sources.take(SHOWN_SOURCES)) Tag("Source ${s.n}, ${s.host}", { onOpen(SourceSheet.One(s)) }) {
            Text("${s.n}", style = tagText, color = M.orange)
            Text(s.host, style = tagText, maxLines = 1)
        }
        val more = sources.size - SHOWN_SOURCES
        if (more > 0) Tag("Show $more more ${if (more == 1) "source" else "sources"}", { onOpen(SourceSheet.All) }) {
            Text("+$more", style = tagText, color = M.text2)
        }
    }

// ponytail: 48 dp touch target around a 28 dp pill, as the mockup draws it.
@Composable
private fun Tag(label: String, onClick: () -> Unit, content: @Composable () -> Unit) =
    Box(Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick)
        .clearAndSetSemantics { contentDescription = label; role = Role.Button; onClick { onClick(); true } },
        contentAlignment = Alignment.Center) {
        Row(Modifier.height(19.u).background(M.surface, RoundedCornerShape(9.5.u)).padding(horizontal = 7.u),
            horizontalArrangement = Arrangement.spacedBy(4.u), verticalAlignment = Alignment.CenterVertically) { content() }
    }

/** Full title and address only here; "Open" hands the original, validated address to the browser. Back closes it first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceDetails(sheet: SourceSheet, sources: List<Source>, onPick: (Source) -> Unit, onClose: () -> Unit) {
    val uri = LocalUriHandler.current
    ModalBottomSheet(onDismissRequest = onClose, containerColor = M.surface, contentColor = M.text) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = M.gutter).padding(bottom = 16.u)) {
            when (sheet) {
                is SourceSheet.One -> {
                    val s = sheet.source
                    Text("${s.n}", style = tagText, color = M.orange)
                    Text(s.title, style = TextStyle(fontFamily = Jost, fontSize = 17.s, fontWeight = FontWeight(500), color = M.text),
                        modifier = Modifier.padding(top = 3.u, bottom = 3.u))
                    Text(s.url, style = tagText.copy(color = M.text2, textDirection = TextDirection.Ltr))
                    Row(Modifier.padding(top = 11.u), horizontalArrangement = Arrangement.spacedBy(6.u)) {
                        Pill("Close", M.outline, M.text, onClose, Modifier.weight(1f))
                        Pill("Open ↗", M.orange, M.orange, { runCatching { uri.openUri(s.url) }; onClose() }, Modifier.weight(1f))
                    }
                }
                SourceSheet.All -> for (s in sources) Column(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    .clickable(role = Role.Button, onClickLabel = "Details") { onPick(s) }.padding(vertical = 7.u)) {
                    Text("${s.n} · ${s.host}", style = tagText, color = M.orange)
                    Text(s.title, style = T.msg)
                }
            }
        }
    }
}

@Composable
private fun Pill(label: String, edge: Color, ink: Color, onClick: () -> Unit, modifier: Modifier) =
    Box(modifier.heightIn(min = 48.dp).border(1.5.dp, edge, RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Text(label, style = TextStyle(fontFamily = Hanken, fontSize = 9.5.s, fontWeight = FontWeight(500)), color = ink)
    }
