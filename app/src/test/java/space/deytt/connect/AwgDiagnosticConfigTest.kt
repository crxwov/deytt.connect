package space.deytt.connect

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AwgDiagnosticConfigTest {
    @Test fun restrictsOnlyDiagnosticInterfaceAndPreservesPeersAndObfuscation() {
        val original = """
            [Interface]
            PrivateKey = test-only
            Address = 10.0.0.2/32
            ExcludedApplications = example.other
            IncludedApplications = example.another
            Jc = 5
            H1 = 123
            [Peer]
            PublicKey = test-public
            AllowedIPs = 0.0.0.0/0
            Endpoint = test.invalid:51820
        """.trimIndent()
        val result = AwgDiagnosticConfig.forApplication(original, "space.deytt.connect")
        assertTrue(result.contains("IncludedApplications = space.deytt.connect"))
        assertFalse(result.contains("ExcludedApplications"))
        assertFalse(result.contains("example.another"))
        assertTrue(result.contains("Jc = 5\nH1 = 123"))
        assertTrue(result.substringAfter("[Peer]") == original.substringAfter("[Peer]"))
        assertTrue(original.contains("ExcludedApplications"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsApplicationInjection() {
        AwgDiagnosticConfig.forApplication("[Interface]", "space.deytt.connect\nExcludedApplications = x")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingInterfaceInsteadOfUsingFullDeviceTunnel() {
        AwgDiagnosticConfig.forApplication("[Peer]\nPublicKey = test", "space.deytt.connect")
    }
}
