package ui.screen.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * Lightweight Matrix rich-text rendering for message bodies.
 *
 * Two input paths:
 *  - formatted_body (org.matrix.custom.html): a small tag-walking parser covers the
 *    subset Element/other clients actually emit — b/strong, i/em, u, s/del/strike,
 *    code, pre, blockquote, headings, br/hr/p/div/li lists, links and matrix.to
 *    mentions. Unknown tags are stripped, never leaked as raw markup. <mx-reply>
 *    content is dropped (replies render from the Reply content type instead).
 *  - plain body: markdown-lite (**bold**, __underline__, ~~strike~~, `code`,
 *    *italic*, _italic_), bare URLs, plus @mention pills matched against room
 *    members.
 *
 * Pure functions — theme colors are passed in via [RichTextPalette] so no
 * composable context is required here.
 */
internal data class RichTextPalette(
    val text: Color,
    val muted: Color,
    val accent: Color,
    val codeBg: Color,
)

internal fun buildRichMessageText(
    plainBody: String,
    formattedBody: String?,
    members: List<Pair<String, String>>,
    currentUserId: String?,
    palette: RichTextPalette
): AnnotatedString {
    return if (!formattedBody.isNullOrBlank()) {
        parseMatrixHtml(formattedBody, palette)
    } else {
        buildPlainTextRich(plainBody, members, currentUserId, palette)
    }
}

// ─── HTML path ──────────────────────────────────────────────────

private class OpenTag(val name: String)

private val BlockTags = setOf("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "ul", "ol", "table", "tr")

internal fun parseMatrixHtml(html: String, p: RichTextPalette): AnnotatedString {
    val b = AnnotatedString.Builder()
    val stack = ArrayList<OpenTag>()
    var suppress = 0 // inside <mx-reply>: drop everything until it closes
    var i = 0
    val n = html.length

    fun popTo(name: String) {
        // Pop entries above the match AND the match itself (each pushed exactly one value)
        val idx = stack.indexOfLast { it.name == name }
        if (idx < 0) return // stray close tag — ignore, don't touch the stack
        while (stack.size > idx) {
            stack.removeAt(stack.size - 1)
            b.pop(1)
        }
    }

    while (i < n) {
        val c = html[i]
        if (c == '<' && i + 1 < n &&
            (html[i + 1].isLetter() || html[i + 1] == '/' || html[i + 1] == '!')
        ) {
            val gt = html.indexOf('>', i + 1)
            if (gt == -1) {
                appendHtmlEscaped(b, html.substring(i))
                break
            }
            var rawTag = html.substring(i + 1, gt).trim()
            i = gt + 1

            val selfClosing = rawTag.endsWith("/")
            if (selfClosing) rawTag = rawTag.dropLast(1).trim()
            val closing = rawTag.startsWith("/")
            if (closing) rawTag = rawTag.substring(1)
            val name = rawTag.substringBefore(' ').substringBefore('\t').trim().lowercase()
            val attrs = if (rawTag.length > name.length) rawTag.substring(name.length).trim() else ""

            if (suppress > 0) {
                if (!closing && name == "mx-reply") suppress++
                else if (closing && name == "mx-reply") suppress--
                continue
            }
            when {
                closing -> popTo(name)
                name == "mx-reply" -> suppress = 1
                name == "br" -> b.append('\n')
                name == "hr" -> b.append("\n———\n")
                name == "img" -> {} // mxc:// inline images not rendered here
                selfClosing -> {}
                name == "li" -> {
                    if (b.length > 0) b.append('\n')
                    b.append("•  ")
                }
                else -> {
                    if (BlockTags.contains(name) && b.length > 0) b.append('\n')
                    if (name == "a") {
                        pushAnchor(b, stack, attrs, p)
                    } else {
                        b.pushStyle(styleForTag(name, p))
                        stack.add(OpenTag(name))
                    }
                }
            }
        } else {
            val lt = html.indexOf('<', i).let { if (it == -1) n else it }
            if (suppress == 0) appendHtmlEscaped(b, html.substring(i, lt))
            i = lt
        }
    }
    return b.toAnnotatedString()
}

private fun pushAnchor(b: AnnotatedString.Builder, stack: ArrayList<OpenTag>, attrs: String, p: RichTextPalette) {
    val href = attrValue(attrs, "href")
    if (href != null && href.startsWith("https://matrix.to/#/@")) {
        // Mention pill — styled, not clickable (click-to-profile comes later)
        b.pushStyle(
            SpanStyle(
                color = p.accent,
                background = p.accent.copy(alpha = 0.16f),
                fontWeight = FontWeight.SemiBold
            )
        )
        stack.add(OpenTag("a"))
    } else if (href != null) {
        b.pushLink(
            LinkAnnotation.Url(
                href,
                TextLinkStyles(
                    SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline)
                )
            )
        )
        stack.add(OpenTag("a"))
    } else {
        b.pushStyle(SpanStyle(color = p.accent))
        stack.add(OpenTag("a"))
    }
}

private fun styleForTag(name: String, p: RichTextPalette): SpanStyle = when (name) {
    "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
    "i", "em", "cite", "var" -> SpanStyle(fontStyle = FontStyle.Italic)
    "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
    "s", "strike", "del" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
    "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = p.codeBg)
    "pre" -> SpanStyle(fontFamily = FontFamily.Monospace, background = p.codeBg, color = p.text)
    "blockquote" -> SpanStyle(fontStyle = FontStyle.Italic, color = p.muted)
    "h1" -> headingStyle(20.sp)
    "h2" -> headingStyle(18.sp)
    "h3" -> headingStyle(16.sp)
    "h4" -> headingStyle(15.sp)
    "h5" -> headingStyle(14.sp)
    "h6" -> headingStyle(13.sp)
    "small" -> SpanStyle(fontSize = 11.sp)
    "span", "font", "tbody", "thead", "td", "th" -> SpanStyle()
    else -> SpanStyle()
}

private fun headingStyle(size: androidx.compose.ui.unit.TextUnit) =
    SpanStyle(fontWeight = FontWeight.Bold, fontSize = size)

private fun appendHtmlEscaped(b: AnnotatedString.Builder, s: String) {
    var i = 0
    while (i < s.length) {
        val ch = s[i]
        if (ch == '&') {
            val semi = s.indexOf(';', i + 1)
            if (semi >= i + 2 && semi <= i + 10) {
                val dec = decodeEntity(s.substring(i + 1, semi))
                if (dec != null) {
                    b.append(dec)
                    i = semi + 1
                    continue
                }
            }
        }
        b.append(ch)
        i++
    }
}

private fun decodeEntity(ent: String): String? {
    if (ent.isEmpty()) return null
    if (ent[0] == '#') {
        val cp = if (ent.length > 1 && (ent[1] == 'x' || ent[1] == 'X'))
            ent.substring(2).toIntOrNull(16)
        else ent.substring(1).toIntOrNull()
        return cp?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) }
    }
    return when (ent.lowercase()) {
        "amp" -> "&"
        "lt" -> "<"
        "gt" -> ">"
        "quot" -> "\""
        "apos" -> "'"
        "nbsp" -> "\u00A0"
        "hellip" -> "…"
        "mdash" -> "—"
        "ndash" -> "–"
        "copy" -> "©"
        "reg" -> "®"
        "trade" -> "™"
        "laquo" -> "«"
        "raquo" -> "»"
        else -> null
    }
}

private val AttrRegex = Regex("""([a-zA-Z_:][-\w:.]*)\s*=\s*("([^"]*)"|'([^']*)'|[^\s"'>]+)""")

private fun attrValue(attrs: String, name: String): String? =
    AttrRegex.findAll(attrs).firstOrNull { it.groupValues[1].equals(name, ignoreCase = true) }?.let { m ->
        val dq = m.groupValues[3]
        val sq = m.groupValues[4]
        when {
            dq.isNotEmpty() -> dq
            sq.isNotEmpty() -> sq
            else -> m.groupValues[2]
        }
    }

// ─── Plain-body path: markdown-lite + autolink + @mentions ──────

private val PlainRichRegex = Regex(
    "(https?://[^\\s<>]+" +
        "|\\*\\*(.+?)\\*\\*" +
        "|__(.+?)__" +
        "|~~(.+?)~~" +
        "|`([^`\n]+?)`" +
        "|(?<![\\w*])\\*(?![\\s*])([^*\n]+?)(?<=[^\\s*])\\*(?![\\w*])" +
        "|(?<![\\w_])_(?![\\s_])([^_\n]+?)(?<=[^\\s_])_(?![\\w_])" +
        ")"
)

internal fun buildPlainTextRich(
    body: String,
    members: List<Pair<String, String>>,
    currentUserId: String?,
    p: RichTextPalette
): AnnotatedString {
    val myLocalpart = currentUserId?.removePrefix("@")?.substringBefore(':')
    val mentionTokens = HashMap<String, Boolean>() // lowercase token -> targets me
    mentionTokens["room"] = false
    for ((display, id) in members) {
        val lp = id.removePrefix("@").substringBefore(':').lowercase()
        val isMe = myLocalpart != null && lp == myLocalpart
        val dn = display.trim()
        if (dn.isNotEmpty() && !dn.contains(' ')) {
            val key = dn.lowercase()
            mentionTokens[key] = (mentionTokens[key] ?: false) || isMe
        }
        if (lp.isNotBlank()) {
            mentionTokens[lp] = (mentionTokens[lp] ?: false) || isMe
        }
    }

    val b = AnnotatedString.Builder()
    var cursor = 0
    for (m in PlainRichRegex.findAll(body)) {
        appendPlainWithMentions(b, body.substring(cursor, m.range.first), mentionTokens, p)
        val g = m.groupValues
        when {
            g[1].isNotEmpty() -> {
                b.pushLink(
                    LinkAnnotation.Url(
                        g[1],
                        TextLinkStyles(SpanStyle(color = p.accent, textDecoration = TextDecoration.Underline))
                    )
                )
                b.append(g[1])
                b.pop(1)
            }
            g[3].isNotEmpty() -> styled(b, g[3], SpanStyle(fontWeight = FontWeight.Bold), p)
            g[5].isNotEmpty() -> styled(b, g[5], SpanStyle(textDecoration = TextDecoration.Underline), p)
            g[7].isNotEmpty() -> styled(b, g[7], SpanStyle(textDecoration = TextDecoration.LineThrough), p)
            g[9].isNotEmpty() -> styled(b, g[9], SpanStyle(fontFamily = FontFamily.Monospace, background = p.codeBg), p)
            g[11].isNotEmpty() -> styled(b, g[11], SpanStyle(fontStyle = FontStyle.Italic), p)
            g[13].isNotEmpty() -> styled(b, g[13], SpanStyle(fontStyle = FontStyle.Italic), p)
            else -> b.append(m.value)
        }
        cursor = m.range.last + 1
    }
    appendPlainWithMentions(b, body.substring(cursor), mentionTokens, p)
    return b.toAnnotatedString()
}

private fun styled(b: AnnotatedString.Builder, text: String, style: SpanStyle, p: RichTextPalette) {
    b.pushStyle(style)
    b.append(text)
    b.pop(1)
}

private fun mentionStyle(targetsMe: Boolean, p: RichTextPalette) = if (targetsMe) {
    SpanStyle(
        color = Color.White,
        background = p.accent.copy(alpha = 0.45f),
        fontWeight = FontWeight.SemiBold
    )
} else {
    SpanStyle(
        color = p.accent,
        background = p.accent.copy(alpha = 0.16f),
        fontWeight = FontWeight.SemiBold
    )
}

private fun appendPlainWithMentions(
    b: AnnotatedString.Builder,
    text: String,
    tokens: Map<String, Boolean>,
    p: RichTextPalette
) {
    var i = 0
    var runStart = 0
    val len = text.length
    while (i < len) {
        if (text[i] == '@' && (i == 0 || !text[i - 1].isLetterOrDigit())) {
            var j = i + 1
            while (j < len && (text[j].isLetterOrDigit() || text[j] == '_' || text[j] == '-' || text[j] == '.')) j++
            val tok = text.substring(i + 1, j).lowercase()
            val targetsMe = tokens[tok]
            if (tok.isNotEmpty() && targetsMe != null) {
                if (runStart < i) b.append(text.substring(runStart, i))
                b.pushStyle(mentionStyle(targetsMe, p))
                b.append(text.substring(i, j))
                b.pop(1)
                i = j
                runStart = i
                continue
            }
        }
        i++
    }
    if (runStart < len) b.append(text.substring(runStart))
}
