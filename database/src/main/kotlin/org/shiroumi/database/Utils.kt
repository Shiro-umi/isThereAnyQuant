package org.shiroumi.database

import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

val today: LocalDate
    get() = LocalDate.now()

val LocalDate.str: String
    get() = format(dateFormatter)
