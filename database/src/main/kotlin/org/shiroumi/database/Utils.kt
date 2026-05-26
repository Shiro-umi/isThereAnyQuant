package org.shiroumi.database

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
val today = kotlin.time.Clock.System.now()
    .toLocalDateTime(TimeZone.currentSystemDefault())
    .date