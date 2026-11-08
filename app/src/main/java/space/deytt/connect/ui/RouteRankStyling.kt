package space.deytt.connect

import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.view.View
import android.widget.TextView
import java.util.Locale
import space.deytt.connect.DeyttUi.CORAL
import space.deytt.connect.DeyttUi.MINT
import space.deytt.connect.DeyttUi.MUTED
import space.deytt.connect.DeyttUi.dp

internal data class ProbeRouteRow(
    val route: DeyttRoute,
    val status: TextView,
    val progress: InlineMetricProgress,
    val surface: View,
)

internal data class CountryProbeState(
    val code: String,
    val routes: List<DeyttRoute>,
    val rows: Map<String, ProbeRouteRow>,
    val summary: TextView,
    val summaryProgress: InlineMetricProgress,
    val samples: MutableMap<String, RouteProbeSample> = mutableMapOf(),
    val pingTraces: MutableMap<String, MutableList<Long>> = mutableMapOf(),
    val errors: MutableMap<String, String> = mutableMapOf(),
    var speedCheckEnabled: Boolean = false,
    var selectionAtStart: String? = null,
    var cancelled: Boolean = false,
    var isChecking: Boolean = false,
)

internal class RouteRankStyling(
    private val host: MainActivity,
    private val copy: (String, String) -> String,
) {
    fun sampleLabel(sample: RouteProbeSample, isBest: Boolean = false, isWorst: Boolean = false): String =
        copy("Отклик: ", "Response: ") + (sample.latencyMillis?.let(::formatLatency) ?: "—") +
            " · " + copy("Скорость: ", "Speed: ") + (sample.downloadBytesPerSecond?.takeIf { it > 0L }?.let(::formatSpeed) ?: "—") +
            when {
                isBest -> copy(" · лучший выбор", " · best choice")
                isWorst -> copy(" · худший отклик", " · slowest response")
                else -> ""
            }

    fun routeRankBackground(grade: RouteGrade, selected: Boolean): Drawable {
        val cornerRadius = host.dp(14).toFloat()
        val insetH = host.dp(6)
        val insetV = host.dp(2)

        val gd = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            this.cornerRadius = cornerRadius
        }

        when {
            selected && grade == RouteGrade.GOOD -> {
                gd.setColor(0x3010B981.toInt())
                gd.setStroke(host.dp(1), 0x9534D399.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            selected && grade == RouteGrade.POOR -> {
                gd.setColor(0x28EF4444.toInt())
                gd.setStroke(host.dp(1), 0x90F87171.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            selected -> {
                gd.setColor(0x283B82F6.toInt())
                gd.setStroke(host.dp(1), 0x8060A5FA.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            grade == RouteGrade.GOOD -> {
                gd.setColor(0x2010B981.toInt())
                gd.setStroke(host.dp(1), 0x6034D399.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            grade == RouteGrade.POOR -> {
                gd.setColor(0x1CEF4444.toInt())
                gd.setStroke(host.dp(1), 0x50F87171.toInt())
                return InsetDrawable(gd, insetH, insetV, insetH, insetV)
            }
            else -> return ColorDrawable(Color.TRANSPARENT)
        }
    }

    fun cachedLatencySummary(samples: List<RouteProbeSample>): String? =
        RouteProbeStatistics.latencySummary(samples.mapNotNull { it.latencyMillis })?.let(::latencySummaryLabel)

    private fun latencySummaryLabel(summary: RouteLatencySummary): String =
        if (AppLanguage.current(host) == AppLanguage.EN) {
            "Response · best ${summary.bestMillis} · avg ${summary.averageMillis} · worst ${summary.worstMillis} ms"
        } else {
            "Отклик · лучший ${summary.bestMillis} · средний ${summary.averageMillis} · худший ${summary.worstMillis} мс"
        }

    fun updateCountryLatencySummary(country: CountryProbeState) {
        val summary = RouteProbeStatistics.latencySummary(country.samples.values.mapNotNull { it.latencyMillis })
        val summaryLabel = summary?.let(::latencySummaryLabel)
        country.summary.text = when {
            country.isChecking && summaryLabel != null -> summaryLabel + copy(" · проверка идёт", " · check in progress")
            country.isChecking -> copy("Отклик: — · проверяем маршруты", "Response: — · checking routes")
            summaryLabel != null -> summaryLabel + copy(" · готово", " · complete")
            country.cancelled -> copy("Проверка отменена · откройте страну снова", "Check cancelled · reopen the country")
            else -> copy("Нет результата · откройте страну для повтора", "No result · reopen the country to retry")
        }
        country.summary.contentDescription = country.summary.text
        country.summaryProgress.setLoading(country.isChecking)
    }

    fun applyCountryRanking(country: CountryProbeState, selectedRouteId: String) {
        val samples = country.samples.values.toList()
        val measured = samples.filter { it.latencyMillis?.let(RouteProbeStatistics::isValidLatencyMillis) == true }

        val bestRouteId: String? = when {
            measured.isEmpty() -> null
            measured.size == 1 -> measured.first().routeId
            else -> {
                RouteProbeScoring.bestRouteId(samples)
                    ?: measured.minByOrNull { it.latencyMillis!! }?.routeId
            }
        }

        val worstRouteId: String? = when {
            measured.size < 2 -> null
            else -> {
                val completeWorst = RouteProbeScoring.worstRouteId(samples)
                if (completeWorst != null && completeWorst != bestRouteId) {
                    completeWorst
                } else {
                    val candidates = measured.filter { it.routeId != bestRouteId }
                    candidates.maxByOrNull { it.latencyMillis!! }?.routeId
                }
            }
        }

        val english = AppLanguage.current(host) == AppLanguage.EN

        country.rows.values.forEach { row ->
            if (row.route.engine == TunnelEngine.OPENFLUX) return@forEach
            if (row.progress.isLoading()) return@forEach

            val sample = country.samples[row.route.id]
            val hasError = country.errors[row.route.id] != null

            if (country.isChecking && sample == null && !hasError) {
                val isSelected = selectedRouteId == row.route.id
                row.surface.background = if (isSelected) {
                    routeRankBackground(RouteGrade.UNRATED, selected = true)
                } else {
                    ColorDrawable(Color.TRANSPARENT)
                }
                return@forEach
            }

            val isBest = row.route.id == bestRouteId
            val isWorst = row.route.id == worstRouteId
            val grade = when {
                isBest -> RouteGrade.GOOD
                isWorst -> RouteGrade.POOR
                sample?.latencyMillis != null -> RouteGrade.MEDIUM
                else -> RouteGrade.UNRATED
            }

            row.surface.background = routeRankBackground(grade, selectedRouteId == row.route.id)

            if (sample != null && sample.latencyMillis != null) {
                val baseLabel = sampleLabel(sample, isBest = isBest, isWorst = isWorst)
                val speedReason = if (sample.downloadBytesPerSecond == null) {
                    country.errors[row.route.id]?.let {
                        RouteProbePresentation.failureMessage(it, english)
                    }
                } else null
                row.status.text = if (speedReason != null) "$baseLabel · $speedReason" else baseLabel
                row.status.setTextColor(gradeColor(grade))
                row.status.setTypeface(null, if (isBest || isWorst) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            } else {
                val reason = country.errors[row.route.id]?.let {
                    RouteProbePresentation.failureMessage(it, english)
                } ?: copy("Нет ответа · нажмите для повтора", "No response · tap to retry")
                row.status.text = "${copy("Отклик: —  ·  Скорость: —", "Response: —  ·  Speed: —")} · $reason"
                row.status.setTextColor(DeyttUi.MUTED)
                row.status.setTypeface(null, android.graphics.Typeface.NORMAL)
            }
            row.status.contentDescription = row.status.text
        }
    }

    fun gradeColor(grade: RouteGrade): Int = when (grade) {
        RouteGrade.GOOD -> DeyttUi.MINT
        RouteGrade.MEDIUM -> DeyttUi.MUTED
        RouteGrade.POOR -> DeyttUi.CORAL
        RouteGrade.UNRATED -> DeyttUi.MUTED
    }

    fun formatLatency(milliseconds: Long): String =
        if (AppLanguage.current(host) == AppLanguage.EN) "$milliseconds\u00A0ms" else "$milliseconds\u00A0мс"

    fun formatSpeed(bytesPerSecond: Long): String =
        RouteProbePresentation.formatSpeed(bytesPerSecond, AppLanguage.current(host) == AppLanguage.EN)
}
