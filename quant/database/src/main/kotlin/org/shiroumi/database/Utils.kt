package org.shiroumi.database

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDateTime as JLocalDateTime


val LocalDateTime.java: JLocalDateTime
    get() {
        val millis = this.toInstant(UtcOffset.ZERO).toEpochMilliseconds()
        val jInstant = Instant.ofEpochMilli(millis)
        return JLocalDateTime.ofInstant(jInstant, ZoneId.systemDefault())
    }

val String?.jDateTime: JLocalDateTime
    get() {
        this ?: return JLocalDateTime.now()
        val jInstant = Instant.ofEpochMilli(System.currentTimeMillis())
        return JLocalDateTime.ofInstant(jInstant, ZoneId.systemDefault())
    }
