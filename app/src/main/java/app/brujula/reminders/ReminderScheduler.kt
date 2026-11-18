package app.brujula.reminders

interface ReminderScheduler {
    /** Programa exactamente [reminders] y cancela cualquier otro aviso programado antes. */
    fun replaceAll(reminders: List<Reminder>)

    /** Programa un aviso suelto (por ejemplo, "En 15 min") sin tocar los demas. */
    fun scheduleOne(reminder: Reminder)
}
