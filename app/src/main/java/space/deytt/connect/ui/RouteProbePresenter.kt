package space.deytt.connect

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import space.deytt.connect.AppLanguage.uiCopy
import space.deytt.connect.DeyttUi.AMBER
import space.deytt.connect.DeyttUi.CORAL
import space.deytt.connect.DeyttUi.MINT
import space.deytt.connect.DeyttUi.SKY
import space.deytt.connect.DeyttUi.TEXT
import java.util.concurrent.Executors

internal class RouteProbePresenter(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
    private val onRouteDiagnosticsIdle: () -> Unit = {},
) {
    val styling = RouteRankStyling(host, copy)
    private val diagnosticExecutor = Executors.newSingleThreadExecutor()
    private val pendingCountries = linkedMapOf<String, CountryProbeState>()
    private var awgMeasurementRunning = false
    private var activeAwgCountry: CountryProbeState? = null
    private var diagnosticForeground = true
    private var awgCompletion: ((Boolean) -> Unit)? = null
    private val routeProbeStates = mutableMapOf<String, CountryProbeState>()
    private var closed = false

    private val updateHandler = Handler(Looper.getMainLooper())
    private val nextCountryCheck = object : Runnable {
        override fun run() {
            if (closed || host.isDestroyed || !diagnosticForeground) return
            if (RouteProbeClient.isRunning(host) || routeProbeStates.isNotEmpty() || awgMeasurementRunning) {
                if (pendingCountries.isNotEmpty()) updateHandler.postDelayed(this, 100L)
                return
            }
            val next = pendingCountries.values.firstOrNull()
            if (next != null) {
                pendingCountries.remove(next.code)
                startCountryProbe(next)
            } else onRouteDiagnosticsIdle()
        }
    }

    fun routeProtocolOrder(protocol: RouteProtocol): Int = when (protocol) {
        RouteProtocol.VLESS -> 0
        RouteProtocol.TROJAN -> 1
        RouteProtocol.HYSTERIA2 -> 2
        RouteProtocol.TUIC -> 3
        else -> 3
    }

    fun protocolMark(protocol: RouteProtocol): String = when (protocol) {
        RouteProtocol.VLESS -> "VL"
        RouteProtocol.TROJAN -> "◇"
        RouteProtocol.HYSTERIA2 -> "H2"
        RouteProtocol.TUIC -> "TU"
        RouteProtocol.LTE -> "LTE"
        RouteProtocol.OPENFLUX -> "DE"
        else -> "·"
    }

    fun shortMark(route: DeyttRoute): String = when (route.countryCode) {
        "NL" -> "NL"
        "DE" -> "DE"
        "RU" -> "RU"
        "FI" -> "FI"
        else -> route.profileName ?: "AWG"
    }

    fun startCountryProbe(country: CountryProbeState) {
        if (country.routes.isEmpty() || closed || !diagnosticForeground) return
        if (routeProbeStates.values.any { it === country } || activeAwgCountry === country) {
            if (country.cancelled) {
                pendingCountries[country.code] = country
                launchNextCountry()
            }
            return
        }
        country.cancelled = false
        if (RouteProbeClient.isRunning(host) || routeProbeStates.isNotEmpty() || awgMeasurementRunning) {
            pendingCountries[country.code] = country
            country.isChecking = false
            country.summary.text = copy("В очереди · дождитесь предыдущей проверки", "Queued · waiting for the current check")
            country.summaryProgress.setLoading(false)
            country.rows.values.forEach {
                it.progress.setLoading(false)
                it.status.text = copy("В очереди…", "Queued…")
            }
            launchNextCountry()
            return
        }
        country.isChecking = true
        country.selectionAtStart = SelectedRouteStore(host).read().id
        country.samples.clear()
        country.pingTraces.clear()
        country.errors.clear()
        country.summary.text = copy("Отклик: —", "Response: —")
        country.summary.contentDescription = country.summary.text
        country.summaryProgress.setLoading(true)
        if (VpnService.prepare(host) != null) {
            country.isChecking = false
            country.summary.text = copy("Разрешите проверку маршрутов в Android", "Allow route checks in Android")
            country.summaryProgress.setLoading(false)
            if (host.requestRouteProbePermission { if (!country.cancelled) startCountryProbe(country) }) return
            country.isChecking = true
            country.summary.text = copy("Отклик: —", "Response: —")
            country.summaryProgress.setLoading(true)
        }
        val config = SubscriptionStore(host).readCurrent() ?: run {
            country.isChecking = false
            country.summary.text = copy("Подписка недоступна · обновите маршруты", "Subscription unavailable · refresh routes")
            country.summaryProgress.setLoading(false)
            return
        }
        val token = TelegramSessionStore.read(host)
        country.speedCheckEnabled = !token.isNullOrBlank()
        val selectedRouteId = SelectedRouteStore(host).read().id
        country.rows.values.forEach { row ->
            val isSelected = selectedRouteId == row.route.id
            row.surface.background = if (isSelected) {
                styling.routeRankBackground(RouteGrade.UNRATED, selected = true)
            } else {
                ColorDrawable(Color.TRANSPARENT)
            }
            if (row.route.engine == TunnelEngine.OPENFLUX) {
                row.progress.setLoading(false)
                row.status.text = copy("Проверка после подключения", "Measure after connecting")
                return@forEach
            }
            if (row.route.engine == TunnelEngine.AMNEZIAWG) {
                row.progress.setLoading(false)
                row.status.text = copy("В очереди…", "Queued…")
                row.status.setTextColor(DeyttUi.MUTED)
                row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                return@forEach
            }
            row.status.text = styling.sampleLabel(RouteProbeSample(row.route.id, null, null))
            row.status.setTextColor(SKY)
            row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
            row.progress.setLoading(true)
            country.pingTraces[row.route.id] = mutableListOf()
        }
        val networkRoutes = country.routes.filter { it.engine == TunnelEngine.LIBBOX }
        if (networkRoutes.isEmpty()) { startCountryAwgProbes(country); return }
        runCatching {
            RouteProbeClient.start(
                host,
                config,
                networkRoutes.map(DeyttRoute::configTag),
                RouteProbePreferences.method(host),
                token,
            )
        }.onSuccess { requestId -> routeProbeStates[requestId] = country }
            .onFailure {
                country.rows.values.forEach { row ->
                    row.progress.setLoading(false)
                    row.status.text = host.uiCopy("ошибка")
                    row.status.setTextColor(CORAL)
                }
                startCountryAwgProbes(country)
            }
    }

    fun onRouteProbeEvent(event: Intent) {
        val requestId = event.getStringExtra(RouteProbeClient.EXTRA_REQUEST_ID).orEmpty()
        val stage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE).orEmpty()
        val complete = event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)
        val tag = event.getStringExtra(RouteProbeClient.EXTRA_ROUTE_TAG).orEmpty()
        val error = event.getStringExtra(RouteProbeClient.EXTRA_ERROR).orEmpty()
        android.util.Log.i("DeyttRouteProbe", "onRouteProbeEvent req=$requestId stage=$stage tag=$tag complete=$complete error=$error")
        val country = routeProbeStates[requestId] ?: run {
            if (complete) launchNextCountry()
            return
        }
        if (event.getStringExtra(RouteProbeClient.EXTRA_STAGE) == "cancelled") {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                routeProbeStates.remove(requestId)
                country.cancelled = true
                country.rows.values.filter { it.route.engine == TunnelEngine.LIBBOX }.forEach { row ->
                    row.progress.setLoading(false)
                    val sample = country.samples[row.route.id]
                    if (sample?.latencyMillis == null && sample?.downloadBytesPerSecond == null) {
                        row.status.text = copy("Проверка отменена · откройте страну снова", "Measurement cancelled · reopen country")
                    }
                }
                launchNextCountry()
            }
            return
        }
        if (country.cancelled) {
            if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
                routeProbeStates.remove(requestId)
                launchNextCountry()
            }
            return
        }
        if (tag.isBlank() && (stage == "accepted" || stage.startsWith("startup_"))) {
            val elapsed = event.getLongExtra(RouteProbeClient.EXTRA_ELAPSED_MILLIS, -1L)
            country.summary.text = copy("Подготавливаем проверку…", "Preparing route check…") +
                elapsed.takeIf { it >= 0L }?.let { " · ${it} мс" }.orEmpty()
            country.summary.contentDescription = country.summary.text
            country.summaryProgress.setLoading(true)
        }
        if (tag.isNotBlank()) {
            val row = country.rows.values.firstOrNull { it.route.configTag == tag }
            if (row != null) {
                val previous = country.samples[row.route.id] ?: RouteProbeSample(row.route.id, null, null)
                val probeStage = event.getStringExtra(RouteProbeClient.EXTRA_STAGE)
                    ?: if (!event.getStringExtra(RouteProbeClient.EXTRA_ERROR).isNullOrBlank()) "failed" else ""
                val pingTrace = country.pingTraces.getOrPut(row.route.id) { mutableListOf() }
                val reportedLatency = event.getLongExtra(RouteProbeClient.EXTRA_MILLISECONDS, -1L)
                    .takeIf(RouteProbeStatistics::isValidLatencyMillis)
                if (probeStage == "latency_sample") reportedLatency?.let(pingTrace::add)
                val latency = if (probeStage == "latency_sample") {
                    RouteProbeStatistics.medianLatencyMillis(pingTrace) ?: previous.latencyMillis
                } else reportedLatency ?: previous.latencyMillis
                val reportedSpeed = event.getLongExtra(RouteProbeClient.EXTRA_BYTES_PER_SECOND, -1L)
                    .takeIf { it > 0L }
                val speed = if (probeStage == "download_progress") previous.downloadBytesPerSecond
                    else reportedSpeed ?: previous.downloadBytesPerSecond
                val sample = RouteProbeSample(row.route.id, latency, speed)
                country.samples[row.route.id] = sample
                val english = AppLanguage.current(host) == AppLanguage.EN
                event.getStringExtra(RouteProbeClient.EXTRA_ERROR)?.takeIf(String::isNotBlank)
                    ?.let { country.errors[row.route.id] = it }
                when (probeStage) {
                    "latency", "latency_sample", "latency_retry", "waiting_download" -> {
                        row.status.text = styling.sampleLabel(sample)
                        row.status.setTextColor(TEXT)
                        row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                        row.progress.setLoading(true)
                    }
                    "download", "download_progress" -> {
                        row.status.text = RouteProbePresentation.speedProgress(
                            latency?.let(styling::formatLatency),
                            english,
                            reportedSpeed,
                        )
                        row.status.setTextColor(SKY)
                        row.progress.setLoading(true)
                    }
                    "complete", "failed" -> {
                        row.progress.setLoading(false)
                        val baseText = styling.sampleLabel(sample)
                        if (speed == null) {
                            val reason = country.errors[row.route.id]?.let {
                                RouteProbePresentation.failureMessage(it, english)
                            } ?: if (!country.speedCheckEnabled) {
                                copy("Войдите через Telegram, чтобы проверить скорость", "Link Telegram to measure speed")
                            } else {
                                copy("Скорость не измерена · нажмите страну для повтора", "Speed unavailable · reopen country to retry")
                            }
                            row.status.text = "$baseText · $reason"
                        } else {
                            row.status.text = baseText
                        }
                        row.status.setTextColor(DeyttUi.MUTED)
                        row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
                        RouteQualityStore.write(host, sample)
                        val selectedRouteId = SelectedRouteStore(host).read().id
                        styling.applyCountryRanking(country, selectedRouteId)
                    }
                }
                event.getStringExtra(RouteProbeClient.EXTRA_ERROR)?.let { error ->
                    if (error.isNotBlank() && probeStage !in setOf("complete", "failed")) {
                        row.status.contentDescription = host.uiCopy(error)
                    } else if (probeStage in setOf("complete", "failed")) {
                        row.status.contentDescription = row.status.text
                    }
                }
                styling.updateCountryLatencySummary(country)
            }
        }
        if (event.getBooleanExtra(RouteProbeClient.EXTRA_COMPLETE, false)) {
            routeProbeStates.remove(requestId)
            val requestError = event.getStringExtra(RouteProbeClient.EXTRA_ERROR)
            if (!requestError.isNullOrBlank()) country.rows.values.forEach { row ->
                if (country.samples[row.route.id]?.downloadBytesPerSecond == null) {
                    row.progress.setLoading(false)
                    row.status.text = styling.sampleLabel(country.samples[row.route.id] ?: RouteProbeSample(row.route.id, null, null)) +
                        " · " + RouteProbePresentation.failureMessage(requestError, AppLanguage.current(host) == AppLanguage.EN)
                }
            }
            styling.updateCountryLatencySummary(country)
            startCountryAwgProbes(country)
        }
    }

    private fun finishCountryProbe(country: CountryProbeState) {
        if (country.cancelled || closed || !diagnosticForeground) { launchNextCountry(); return }
        country.isChecking = false
        country.summaryProgress.setLoading(false)
        val selectedRouteId = SelectedRouteStore(host).read().id
        styling.applyCountryRanking(country, selectedRouteId)
        styling.updateCountryLatencySummary(country)
        launchNextCountry()
    }

    private fun startCountryAwgProbes(country: CountryProbeState) {
        val rows = country.rows.values.filter { it.route.engine == TunnelEngine.AMNEZIAWG }
        android.util.Log.i("DeyttRouteProbe", "startCountryAwgProbes country=${country.code} rows=${rows.size} cancelled=${country.cancelled} closed=$closed diagForeground=$diagnosticForeground prepare=${VpnService.prepare(host) != null}")
        if (rows.isEmpty() || country.cancelled || closed || !diagnosticForeground) { finishCountryProbe(country); return }
        if (VpnService.prepare(host) != null && host.requestRouteProbePermission {
                if (!country.cancelled && !closed) startCountryAwgProbes(country)
            }
        ) return
        awgMeasurementRunning = true
        activeAwgCountry = country
        rows.forEach {
            it.status.text = styling.sampleLabel(RouteProbeSample(it.route.id, null, null))
            it.status.contentDescription = copy("В очереди · временный VPN только для приложения", "Queued · temporary app-only VPN")
            it.progress.setLoading(true)
        }
        host.requestAwgMeasurements(rows.map { it.route }, onComplete = {
            android.util.Log.i("DeyttRouteProbe", "awg onComplete")
            awgMeasurementRunning = false
            activeAwgCountry = null
            rows.forEach { row ->
                row.progress.setLoading(false)
                val pendingStatus = row.status.text.toString() == styling.sampleLabel(RouteProbeSample(row.route.id, null, null))
                if (!country.cancelled && row.route.id !in country.samples && pendingStatus) {
                    row.status.text = copy("Проверка недоступна · нажмите для повтора", "Measurement unavailable · tap to retry")
                }
            }
            finishCountryProbe(country)
        }, onRouteFailure = { route ->
            android.util.Log.w("DeyttRouteProbe", "awg onRouteFailure: ${route.id}")
            country.rows[route.id]?.let { row ->
                row.progress.setLoading(false)
                country.errors[route.id] = "no response"
                row.status.text = copy("Нет ответа · нажмите для повтора", "No response · tap to retry")
                row.status.setTextColor(CORAL)
                val selectedRouteId = SelectedRouteStore(host).read().id
                styling.applyCountryRanking(country, selectedRouteId)
            }
        }, onRouteStarting = { route ->
            android.util.Log.i("DeyttRouteProbe", "awg onRouteStarting: ${route.id}")
            country.rows[route.id]?.let { row ->
                row.status.text = styling.sampleLabel(RouteProbeSample(route.id, null, null))
                row.status.contentDescription = copy("Подключаем AmneziaWG…", "Connecting AmneziaWG…")
                row.status.setTextColor(SKY)
                row.progress.setLoading(true)
            }
        }, measure = { route, finished ->
            android.util.Log.i("DeyttRouteProbe", "awg measure: ${route.id}")
            val row = country.rows.getValue(route.id)
            measureAwgRow(row, country, finished)
        })
    }

    fun cancelCountryProbe(country: CountryProbeState) {
        pendingCountries.remove(country.code)
        country.cancelled = true
        country.isChecking = false
        country.summaryProgress.setLoading(false)
        country.rows.values.forEach {
            it.progress.setLoading(false)
            val sample = country.samples[it.route.id]
            if (sample?.latencyMillis == null && sample?.downloadBytesPerSecond == null) {
                it.status.text = copy("Проверка отменена · откройте страну снова", "Measurement cancelled · reopen country")
            }
        }
        routeProbeStates.entries.firstOrNull { it.value === country }?.key?.let { RouteProbeClient.cancel(host, it) }
        if (activeAwgCountry === country) host.cancelAwgMeasurement()
        launchNextCountry()
    }

    fun launchNextCountry() {
        updateHandler.removeCallbacks(nextCountryCheck)
        updateHandler.post(nextCountryCheck)
    }

    fun startAwgProbe(row: ProbeRouteRow) {
        if (awgMeasurementRunning || RouteProbeClient.isRunning(host) || closed) return
        if (VpnService.prepare(host) != null && host.requestRouteProbePermission { startAwgProbe(row) }) return
        awgMeasurementRunning = true
        row.progress.setLoading(true)
        row.status.text = styling.sampleLabel(RouteProbeSample(row.route.id, null, null))
        row.status.contentDescription = copy("Подключаем для проверки…", "Connecting to measure…")
        host.requestAwgMeasurement(row.route, onComplete = { successful ->
            awgMeasurementRunning = false
            row.progress.setLoading(false)
            if (!successful && RouteQualityStore.read(host, row.route.id) == null &&
                row.status.text.toString() == styling.sampleLabel(RouteProbeSample(row.route.id, null, null))
            ) row.status.text = copy("Проверка недоступна · повторите", "Measurement unavailable · retry")
            launchNextCountry()
        }) { finished -> measureAwgRow(row, null, finished) }
    }

    private fun measureAwgRow(row: ProbeRouteRow, country: CountryProbeState?, finished: (Boolean) -> Unit) {
        val token = TelegramSessionStore.read(host)
        android.util.Log.i("DeyttRouteProbe", "measureAwgRow ${row.route.id} token=${token != null} closed=$closed diagForeground=$diagnosticForeground cancelled=${country?.cancelled}")
        if (closed || !diagnosticForeground || country?.cancelled == true) { finished(false); return }
        awgCompletion = finished
        row.progress.setLoading(true)
        row.status.text = styling.sampleLabel(RouteProbeSample(row.route.id, null, null))
        diagnosticExecutor.execute {
            var measuredLatency: Long? = null
            val result = runCatching {
                val current = {
                    !closed && diagnosticForeground && country?.cancelled != true &&
                        AwgTunnelController.isRunning() && !AwgTunnelController.isStopping() &&
                        SelectedRouteStore(host).read().id == row.route.id
                }
                check(current())
                val pingSamples = mutableListOf<Long>()
                runCatching {
                    RouteProxyProbe.measureThroughSystemVpn(
                        RouteProbePreferences.method(host),
                        timeoutMillis = 2_500,
                        sampleCount = 1,
                        onSample = { _, latency ->
                            if (latency != null && RouteProbeStatistics.isValidLatencyMillis(latency)) {
                                pingSamples += latency
                                val currentLatency = RouteProbeStatistics.medianLatencyMillis(pingSamples)
                                measuredLatency = currentLatency
                                val snapshot = pingSamples.toList()
                                host.runOnUiThread {
                                    if (current()) {
                                        val sample = RouteProbeSample(row.route.id, currentLatency, null)
                                        row.status.text = styling.sampleLabel(sample)
                                        row.progress.setLoading(true)
                                        if (country != null) {
                                            country.samples[row.route.id] = sample
                                            country.pingTraces[row.route.id] = snapshot.toMutableList()
                                            styling.updateCountryLatencySummary(country)
                                        }
                                    }
                                }
                            }
                        },
                    )
                }.onSuccess { measuredLatency = it }
                    .onFailure {
                        android.util.Log.w("DeyttAwgProbe", "Latency probe failed (${it.javaClass.simpleName})")
                    }
                val english = AppLanguage.current(host) == AppLanguage.EN
                val speed = if (token.isNullOrBlank()) null else runCatching {
                    RouteProxyProbe.measureSystemDownload(token, current, sampleMillis = 500, onProgress = { bytesPerSecond ->
                        val latency = measuredLatency
                        host.runOnUiThread {
                            if (current()) {
                                row.status.text = RouteProbePresentation.speedProgress(
                                    latency?.let(styling::formatLatency),
                                    english,
                                    bytesPerSecond,
                                )
                                row.progress.setLoading(true)
                                if (country != null) {
                                    country.samples[row.route.id] = RouteProbeSample(row.route.id, latency, bytesPerSecond)
                                    styling.updateCountryLatencySummary(country)
                                }
                            }
                        }
                    })
                }.getOrNull()
                RouteProbeSample(row.route.id, measuredLatency, speed).also { RouteQualityStore.write(host, it) }
            }.onFailure { error ->
                android.util.Log.w(
                    "DeyttAwgProbe",
                    "Measurement failed (${error.javaClass.simpleName}: ${error.message?.take(72).orEmpty()})",
                )
            }
            host.runOnUiThread {
                row.progress.setLoading(false)
                if (!closed && diagnosticForeground && country?.cancelled != true) {
                    val sample = result.getOrNull() ?: measuredLatency?.let { RouteProbeSample(row.route.id, it, null) }
                    sample?.let {
                        country?.samples?.put(row.route.id, it)
                        RouteQualityStore.write(host, it)
                    }
                    row.status.text = sample?.let(styling::sampleLabel) ?: copy("Не удалось измерить · повторите проверку", "Measurement failed · try again")
                    if (sample != null && sample.downloadBytesPerSecond == null) {
                        val failure = result.exceptionOrNull()?.let {
                            RouteProbePresentation.failure(it, AppLanguage.current(host) == AppLanguage.EN)
                        } ?: copy("скорость недоступна", "speed unavailable")
                        row.status.append(" · $failure")
                    }
                    row.status.setTextColor(when {
                        sample?.downloadBytesPerSecond?.let { it > 0L } == true -> MINT
                        sample != null -> AMBER
                        else -> CORAL
                    })
                    if (country != null) {
                        styling.updateCountryLatencySummary(country)
                        val selectedRouteId = SelectedRouteStore(host).read().id
                        styling.applyCountryRanking(country, selectedRouteId)
                    }
                }
                if (awgCompletion === finished) awgCompletion = null
                finished(result.isSuccess && measuredLatency != null)
            }
        }
    }

    fun startForegroundDiagnostics() {
        diagnosticForeground = true
        if (!RouteProbeClient.isRunning(host) && !awgMeasurementRunning) routeProbeStates.clear()
        launchNextCountry()
    }

    fun stopForegroundDiagnostics() {
        diagnosticForeground = false
        pendingCountries.clear()
        routeProbeStates.values.forEach { it.cancelled = true }
        activeAwgCountry?.let { cancelCountryProbe(it) }
        if (awgMeasurementRunning) host.cancelAwgMeasurement()
        updateHandler.removeCallbacks(nextCountryCheck)
    }

    fun cancelRouteDiagnosticsForSelection() {
        pendingCountries.clear()
        routeProbeStates.values.forEach { it.cancelled = true }
        activeAwgCountry?.cancelled = true
        if (RouteProbeClient.isRunning(host)) RouteProbeClient.cancel(host)
    }

    fun hasActiveRouteDiagnostics(): Boolean = RouteProbeClient.isRunning(host) ||
        routeProbeStates.isNotEmpty() || pendingCountries.isNotEmpty() || awgMeasurementRunning || activeAwgCountry != null

    fun close() {
        closed = true
        awgCompletion?.also { awgCompletion = null }?.invoke(false)
        updateHandler.removeCallbacks(nextCountryCheck)
        diagnosticExecutor.shutdownNow()
        routeProbeStates.values.forEach { it.cancelled = true }
        pendingCountries.clear()
        if (routeProbeStates.isNotEmpty()) RouteProbeClient.cancel(host)
    }
}
