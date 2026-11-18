package app.brujula.activities

import app.brujula.core.time.TimeSpan

fun findConflict(candidate: TimeSpan, busy: List<Occurrence>, ignoreActivityId: Long? = null): Occurrence? =
    busy.firstOrNull { it.activity.id != ignoreActivityId && it.span.overlaps(candidate) }
