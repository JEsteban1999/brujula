package app.brujula.reminders

interface ReminderScheduler {
    /** Programa exactamente [reminders] y cancela cualquier otro aviso programado antes. */
    fun replaceAll(reminders: List<Reminder>)

    /** Programa un aviso suelto (por ejemplo, "En 15 min") sin tocar los demas. */
    fun scheduleOne(reminder: Reminder)

    /**
     * Olvida una espera "En 15 min" ([key] con sufijo `:s`): se cancela su alarma y [replaceAll] ya no la vuelve a armar.
     * Para una ocurrencia que un plan quitó o movió.
     */
    fun cancelSnooze(key: String) = Unit
}
