package kz.invisibleshield.core.model

/** Порт Verdict из call_detect.py — итоговый результат, показываемый пользователю. */
data class Verdict(
    val level: Level,
    val confidence: Double,
    val reasons: List<String> = emptyList(),
    val advice: String = "",
)
