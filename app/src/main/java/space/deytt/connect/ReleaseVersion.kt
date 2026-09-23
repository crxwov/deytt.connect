package space.deytt.connect

object ReleaseVersion {
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parse(candidate) ?: return false
        val installed = parse(current) ?: return false
        for (index in 0..2) {
            if (next.numbers[index] != installed.numbers[index]) return next.numbers[index] > installed.numbers[index]
        }
        return installed.qualifier != null && next.qualifier == null
    }

    private data class Parsed(val numbers: List<Int>, val qualifier: String?)

    private fun parse(raw: String): Parsed? {
        val normalized = raw.trim().removePrefix("v")
        val pieces = normalized.split('-', limit = 2)
        val numbers = pieces.first().split('.').map { it.toIntOrNull() ?: return null }
        if (numbers.size !in 1..3) return null
        return Parsed(numbers + List(3 - numbers.size) { 0 }, pieces.getOrNull(1))
    }
}
