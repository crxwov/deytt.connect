package space.deytt.connect

/** Select only a supported canonical document; never display unrelated site content as legal text. */
internal object LegalDocument {
    fun section(html: String, name: String): String {
        require(name == "privacy" || name == "terms")
        val opening = Regex("<section\\b[^>]*\\bid=[\"']$name[\"'][^>]*>", RegexOption.IGNORE_CASE).find(html)
            ?: error("document_missing")
        val end = html.indexOf("</section>", opening.range.last + 1, ignoreCase = true)
        check(end > opening.range.last) { "document_incomplete" }
        return html.substring(opening.range.last + 1, end)
            .replace(Regex("<(script|style)\\b[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
            .replace("\u00ad", "")
    }
}
