package kz.invisibleshield.core.model

/** Единая шкала серьёзности для звонкового и текстового каналов (ADR-004/ADR-005). */
enum class Level {
    NONE, INFO, WARN, DANGER;

    companion object {
        fun fromString(s: String): Level = when (s) {
            "none" -> NONE
            "info" -> INFO
            "warn" -> WARN
            "danger" -> DANGER
            else -> throw IllegalArgumentException("Неизвестный level: $s")
        }
    }
}
