package space.deytt.connect

internal object TunnelCanary {
    fun acceptsHttpResponse(actualStatus: Int, expectedStatus: Int?): Boolean =
        actualStatus in 200..299 && (expectedStatus == null || actualStatus == expectedStatus)
}
