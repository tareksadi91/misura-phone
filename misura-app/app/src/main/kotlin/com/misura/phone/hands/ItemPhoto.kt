package com.misura.phone.hands

import com.misura.phone.hidden.UiNode

private fun UiNode.words() = listOfNotNull(text?.takeIf { it.isNotBlank() }, desc?.takeIf { it.isNotBlank() && it != text })
private fun IntArray.area() = maxOf(0, this[2] - this[0]) * maxOf(0, this[3] - this[1])
private fun IntArray.holds(o: IntArray) = this[0] <= o[0] && this[1] <= o[1] && this[2] >= o[2] && this[3] >= o[3]
private fun IntArray.overlap(o: IntArray) = intArrayOf(maxOf(this[0], o[0]), maxOf(this[1], o[1]), minOf(this[2], o[2]), minOf(this[3], o[3])).area()
private fun norm(s: String) = s.lowercase().trim().replace(Regex("\\s+"), " ")

/** The title blocks of a thing: clickable nodes with words under them (wide enough to be more than an icon button), else each word node. */
private fun blocks(n: UiNode, minWidth: Int): List<UiNode> {
    fun clicks(x: UiNode): List<UiNode> = if (x.clickable && x.bounds[2] - x.bounds[0] >= minWidth && x.wordCount() > 0) listOf(x) else x.children.flatMap(::clicks)
    fun texts(x: UiNode): List<UiNode> = (if (x.words().isNotEmpty()) listOf(x) else emptyList()) + x.children.flatMap(::texts)
    return clicks(n).ifEmpty { texts(n) }
}
private fun UiNode.wordCount(): Int = words().size + children.sumOf { it.wordCount() }

/** Union rectangle of the words under [n]: the title block's real extent, even when the clickable box also wraps the photo. */
private fun wordsRect(n: UiNode): IntArray? {
    val all = mutableListOf<IntArray>()
    fun walk(x: UiNode) { if (x.words().isNotEmpty()) all += x.bounds; x.children.forEach(::walk) }
    walk(n)
    return all.takeIf { it.isNotEmpty() }?.let { r -> intArrayOf(r.minOf { it[0] }, r.minOf { it[1] }, r.maxOf { it[2] }, r.maxOf { it[3] }) }
}

/** Same test the photo search uses: a childless node with no words, at least 15% of the screen wide and 10% (of the width) tall. */
private fun UiNode.photoLike(w: Int) = children.isEmpty() && words().isEmpty() && !editable && bounds[2] - bounds[0] >= w * 15 / 100 && bounds[3] - bounds[1] >= w * 10 / 100

/**
 * The photo rectangle sits under the app's top bar or touches the bottom edge, or touches a side edge while another photo on the screen
 * is clearly wider (10%+): then this one is probably clipped. A photo that fills the width, or a grid that runs to the edge, is whole (Codex P2).
 */
private fun cut(root: UiNode, p: IntArray, w: Int, h: Int, clip: IntArray? = null): Boolean {
    if (p[1] < h * 8 / 100 || p[3] >= h - 2) return true
    // A list or web page clips what scrolls inside it above and below: a photo that touches or crosses that edge is cut there (live: a tile under a bottom tab bar).
    if (clip != null && (p[1] <= clip[1] + 2 || p[3] >= clip[3] - 2)) return true
    if (p[0] > 2 && p[2] < w - 2) return false
    fun others(n: UiNode): List<UiNode> = (if (n.photoLike(w) && n.bounds !== p && !n.bounds.contentEquals(p)) listOf(n) else emptyList()) + n.children.flatMap(::others)
    val mine = p[2] - p[0]
    return others(root).any { (it.bounds[2] - it.bounds[0]) * 10 >= mine * 11 }
}

/**
 * The path from [root] down to the node that is the element at [at]: the node with exactly its bounds (topmost, i.e. last child, first),
 * else the smallest node that holds it. Boxes are not trusted to hold their children: a web page's wrapper can be 3 px tall over a whole
 * list. A full-screen empty overlay drawn before the page must not win either.
 */
private fun pathTo(root: UiNode, at: IntArray): List<UiNode> {
    var exact: List<UiNode>? = null; var hold: List<UiNode> = listOf(root); var holdArea = Int.MAX_VALUE
    val chain = ArrayList<UiNode>()
    fun walk(n: UiNode) {
        chain += n
        if (n.bounds.contentEquals(at)) exact = chain.toList()
        else if (n.bounds.holds(at) && n.bounds.area() in 1 until holdArea) { hold = chain.toList(); holdArea = n.bounds.area() }
        for (c in n.children.asReversed()) { if (exact != null) break; walk(c) }
        chain.removeAt(chain.size - 1)
    }
    walk(root)
    return exact ?: hold
}

private val CLIPPERS = listOf("WebView", "ListView", "RecyclerView", "ScrollView", "ViewPager", "AbsListView")

private fun clipOf(path: List<UiNode>) = path.lastOrNull { n -> n.cls?.let { c -> CLIPPERS.any { c.contains(it) } } == true }?.bounds

private fun IntArray.centre() = intArrayOf((this[0] + this[2]) / 2, (this[1] + this[3]) / 2)
private fun dist(a: IntArray, b: IntArray) = a.centre().let { p -> b.centre().let { q -> (p[0] - q[0]).toLong() * (p[0] - q[0]) + (p[1] - q[1]).toLong() * (p[1] - q[1]) } }
private fun xOver(a: IntArray, b: IntArray) = minOf(a[2], b[2]) > maxOf(a[0], b[0])
private fun yOver(a: IntArray, b: IntArray) = minOf(a[3], b[3]) > maxOf(a[1], b[1])

/**
 * Is the lone picture [p] of a group of [blocks] the picture of the element at [at], and not a neighbour's? It must share a band with the
 * words (above or below them: same x-range; beside them: same y-range), and no other block of the group may sit closer to it.
 * The blocks stacked in the same band as [at] (beside: its column; above or below: on its side of the picture) are part of the same
 * thing: title, rating and price are separate blocks, and the one the model points at may not be the one nearest the picture.
 * ponytail: a second thing stacked right under a picture-and-title thing looks like one more line of the first; geometry can't tell.
 */
private fun ownsPicture(blocks: List<IntArray>, at: IntArray, p: IntArray): Boolean {
    val beside = !xOver(p, at)
    val below = at.centre()[1] > p.centre()[1]
    val band = blocks.filter { xOver(it, at) && it.overlap(p) == 0 && (beside || (it.centre()[1] > p.centre()[1]) == below) }
    // Above or below the picture the stack must be contiguous: walk outward from the block nearest the picture and stop at the first gap
    // taller than the taller of the two blocks around it. A card further down with no picture of its own is not part of this one.
    // ponytail: beside the picture the whole column counts, no gap rule; two cards side by side with one picture node can still share it.
    val stack = if (beside) band else {
        val out = ArrayList<IntArray>()
        for (x in if (below) band.sortedBy { it[1] } else band.sortedByDescending { it[3] }) {
            val last = out.lastOrNull()
            if (last != null && (if (below) x[1] - last[3] else last[1] - x[3]) > maxOf(x[3] - x[1], last[3] - last[1])) break
            out += x
        }
        out
    }
    if (!beside && band.any { it.holds(at) } && stack.none { it.holds(at) }) return false
    val mine = (stack + listOf(at)).let { r -> intArrayOf(r.minOf { it[0] }, r.minOf { it[1] }, r.maxOf { it[2] }, r.maxOf { it[3] }) }
    if (!xOver(p, mine) && !yOver(p, mine)) return false
    val d = dist(p, mine)
    return blocks.none { b -> !b.holds(at) && b.overlap(p) == 0 && stack.none { it === b } && dist(p, b) < d }
}

/** tile, photo (before the cut-off check), cut off? Null when no tile can be told from its neighbours. */
private fun look(root: UiNode, at: IntArray, w: Int, h: Int): Triple<IntArray, IntArray?, Boolean>? {
    if (!root.bounds.holds(at)) return null
    val path = pathTo(root, at)
    val clip = clipOf(path)
    // The tile: the biggest ancestor that still holds one title block and is not most of the screen.
    val min = w * 15 / 100
    val tile = path.asReversed().takeWhile { blocks(it, min).size <= 1 && it.bounds.area() <= w * h * 60 / 100 }.lastOrNull() ?: return null
    val title = blocks(tile, min).firstOrNull()?.let(::wordsRect) ?: at
    val t = tile.bounds
    fun leaves(n: UiNode): List<UiNode> = if (n.children.isEmpty()) listOf(n) else n.children.flatMap(::leaves)
    val photo = leaves(tile).filter { it.words().isEmpty() && !it.editable && it.bounds[2] - it.bounds[0] >= min && it.bounds[3] - it.bounds[1] >= w * 10 / 100 && it.bounds.overlap(title) * 10 <= it.bounds.area() }
        .maxByOrNull { it.bounds.area() }?.bounds
    if (photo != null) return Triple(t, photo, cut(root, photo, w, h, clip))
    // A tile whose parts are several clickable blocks (title, rating, price, basket) reads as many things above. Then it is the smallest
    // ancestor that holds exactly one picture: the next one up holds the neighbours' pictures too (checked against a real shopping list).
    for (a in path.asReversed().dropWhile { it !== tile }.drop(1)) {
        if (a.bounds.area() > w * h * 60 / 100) break
        val pics = leaves(a).filter { it.photoLike(w) }
        if (pics.size >= 2) break
        if (pics.size == 1) {
            // Two things side by side where only one has a picture node would otherwise both climb to this row and borrow it.
            if (!ownsPicture(blocks(a, min).map { it.bounds }, at, pics[0].bounds)) break
            return Triple(a.bounds, pics[0].bounds, cut(root, pics[0].bounds, w, h, clip))
        }
    }
    // No picture node in the tree (some apps draw them without one): the part of the tile above its words.
    val above = intArrayOf(t[0], t[1], t[2], title[1]).takeIf { it[2] - it[0] >= min && it[3] - it[1] >= min }
    return Triple(t, above, above != null && cut(root, above, w, h, clip))
}

/**
 * The tile of the thing that holds the element at [at] (bounds on a [w]x[h] screen), and its photo. Nothing about any app: only
 * where boxes sit. A photo that may be cut off by the screen counts as none (see [photoCutOff]); null: no tile.
 * ponytail: a tile with two clickable blocks (title + "add to cart") reads as two things and finds no tile; widen blocks() if one turns up.
 */
fun photoOf(root: UiNode, at: IntArray, w: Int, h: Int): Pair<IntArray, IntArray?>? =
    look(root, at, w, h)?.let { (tile, photo, cut) -> tile to photo.takeIf { !cut } }

/** A vertical swipe that moves the content by [dy] px (positive = down): from ([y1]) to ([y2]), both inside the list's visible band, at column [x] (the middle of the list's own width, so a list in a side pane is hit, not the screen centre). */
class Nudge(val dy: Int, val y1: Int, val y2: Int, val x: Int)

/**
 * How to move the list so the cut-off photo of the thing at [at] and its tile sit fully inside the visible part of the list (the same
 * top bar and list edges [cut] uses, plus a margin). If tile and photo together don't fit, the photo alone must. Null: the photo is not
 * cut by a vertical edge, or no vertical move can fix it.
 * ponytail: boxes are cut at the screen, so a photo running off the edge shows only its visible part and the first move is a guess; the caller looks again and nudges once more (two tries, ~1.5 s each).
 * ponytail: a swipe under 150 px could be read as a tap, so it is stretched to 150 and may overshoot a little; the caller looks again and may nudge once more.
 */
fun nudgeFor(root: UiNode, at: IntArray, w: Int, h: Int): Nudge? {
    val (tile, photo, cut) = look(root, at, w, h) ?: return null
    if (!cut || photo == null) return null
    // A web page's wrapper can be a few px tall; a band under a quarter of the screen is not a list.
    val clip = clipOf(pathTo(root, at))?.takeIf { it[3] - it[1] >= h / 4 }
    val top = maxOf(h * 8 / 100, clip?.get(1) ?: 0); val bottom = minOf(h - 2, clip?.get(3) ?: h)
    val m = h * 2 / 100
    fun need(a: Int, b: Int) = when { a < top + m -> top + m - a; b > bottom - m -> bottom - m - b; else -> 0 }
    val both = need(minOf(tile[1], photo[1]), maxOf(tile[3], photo[3]))
    val fits = maxOf(tile[3], photo[3]) - minOf(tile[1], photo[1]) <= bottom - top - 2 * m
    val dy = if (fits) both else need(photo[1], photo[3])
    if (dy == 0 || photo[3] - photo[1] > bottom - top - 2 * m) return null
    val mag = kotlin.math.abs(dy).coerceIn(150, (bottom - top) * 8 / 10)
    val d = if (dy > 0) mag else -mag
    val y1 = (top + bottom) / 2 - d / 2
    val x = clip?.let { (maxOf(0, it[0]) + minOf(w, it[2])) / 2 } ?: (w / 2)
    return Nudge(d, y1, y1 + d, x)
}

/** True when the thing has a photo but the screen cuts it off: the model is told to scroll it fully into view. */
fun photoCutOff(root: UiNode, at: IntArray, w: Int, h: Int) = look(root, at, w, h)?.third == true

/** True when [what] is in the words shown inside [tile] (lowercase, spaces collapsed). A price counts only as a whole token: "20,00 €" is not in "120,00 €" (Codex P2). */
fun onTile(root: UiNode, tile: IntArray, what: String): Boolean {
    val shown = mutableListOf<String>()
    fun walk(n: UiNode) { if (tile.holds(n.bounds)) shown += n.words(); n.children.forEach(::walk) }
    walk(root)
    val want = norm(what); val all = norm(shown.joinToString(" "))
    if (want.isEmpty()) return false
    var from = all.indexOf(want)
    while (from >= 0) {
        val before = all.getOrNull(from - 1); val end = from + want.length; val after = all.getOrNull(end)
        // Not glued to a digit on the left (also not "1.20" or "1,20" with its first part cut), nor to a digit on the right.
        val leftOk = before == null || !(before.isDigit() || (before == '.' || before == ',') && all.getOrNull(from - 2)?.isDigit() == true)
        val rightOk = after == null || !after.isDigit()
        if (leftOk && rightOk) return true
        from = all.indexOf(want, from + 1)
    }
    return false
}
