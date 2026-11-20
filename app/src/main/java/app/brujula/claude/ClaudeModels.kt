package app.brujula.claude

/** Ids de modelo, una constante por uso. */
object ClaudeModels {
    const val DICTATION = "claude-opus-5-5"
    const val CLASSIFICATION = "claude-haiku-5-5"
    /** Armar un día (D8): Opus 5.5 con esfuerzo MEDIUM. */
    const val PLANNING = "claude-opus-5-5"
    /** Sugerencias de hábitos (D11): Haiku 5.5 con esfuerzo MEDIUM, endpoint no beta, sin respaldo en el servidor. */
    const val SUGGESTIONS = "claude-haiku-5-5"
}
