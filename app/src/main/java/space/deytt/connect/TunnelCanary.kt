package space.deytt.connect

internal object TunnelCanary {
    fun acceptsHttpResponse(actualStatus: Int, expectedStatus: Int?): Boolean =
        actualStatus in 100..599 && (expectedStatus == null || actualStatus == expectedStatus)
}
