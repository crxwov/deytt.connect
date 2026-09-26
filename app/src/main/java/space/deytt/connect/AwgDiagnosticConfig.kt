package space.deytt.connect

/** Limits a temporary diagnostic tunnel to this app; it is still Android's one VPN. */
internal object AwgDiagnosticConfig {
    fun forApplication(config: String, packageName: String): String {
        require(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+").matches(packageName))
        var inInterface = false
        var interfaceCount = 0
        val output = mutableListOf<String>()
        config.lineSequence().forEach { line ->
            val clean = line.substringBefore('#').trim()
            if (clean.startsWith("[") && clean.endsWith("]")) {
                inInterface = clean.equals("[Interface]", ignoreCase = true)
                if (inInterface) {
                    interfaceCount++
                    output += line
                    output += "IncludedApplications = $packageName"
                    return@forEach
                }
            }
            val key = clean.substringBefore('=').trim()
            if (inInterface && (key.equals("IncludedApplications", true) || key.equals("ExcludedApplications", true))) {
                return@forEach
            }
            output += line
        }
        require(interfaceCount == 1) { "Invalid diagnostic interface" }
        return output.joinToString("\n")
    }
}
