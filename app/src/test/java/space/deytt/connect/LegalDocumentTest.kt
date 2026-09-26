package space.deytt.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LegalDocumentTest {
    @Test fun selectsOnlyRequestedDocumentAndStripsExecutableMarkup() {
        val html = "<nav>menu</nav><section id='privacy'><p>Privacy</p><script>bad()</script></section><section id='terms'><p>Terms</p></section>"
        assertEquals("<p>Privacy</p>", LegalDocument.section(html, "privacy"))
        assertEquals("<p>Terms</p>", LegalDocument.section(html, "terms"))
    }
    @Test fun failsClosedOnMissingDocumentInsteadOfShowingWholeSite() {
        assertThrows(IllegalStateException::class.java) { LegalDocument.section("<h1>Not found</h1>", "privacy") }
        assertThrows(IllegalStateException::class.java) { LegalDocument.section("<section id='privacy'>unfinished", "privacy") }
        assertThrows(IllegalArgumentException::class.java) { LegalDocument.section("", "untrusted") }
    }
}
