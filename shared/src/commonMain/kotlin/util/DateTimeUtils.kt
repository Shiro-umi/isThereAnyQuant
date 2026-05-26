package util

import kotlinx.datetime.*
import kotlinx.datetime.format.char

/**
 * 时间工具类
 * 统一使用 kotlinx-datetime 处理所有时间相关操作
 */
object DateTimeUtils {

    /**
     * 常用日期时间格式化器
     */
    object Formatters {
        /**
         * yyyy-MM-dd HH:mm:ss
         */
        val STANDARD = LocalDateTime.Format {
            year()
            char('-')
            monthNumber()
            char('-')
            dayOfMonth()
            char(' ')
            hour()
            char(':')
            minute()
            char(':')
            second()
        }

        /**
         * yyyy/MM/dd HH:mm
         */
        val SLASH_SHORT = LocalDateTime.Format {
            year()
            char('/')
            monthNumber()
            char('/')
            dayOfMonth()
            char(' ')
            hour()
            char(':')
            minute()
        }

        /**
         * yyyyMMdd
         */
        val COMPACT_DATE = LocalDate.Format {
            year()
            monthNumber()
            dayOfMonth()
        }
    }

    /**
     * 将时间戳（毫秒）格式化为标准格式字符串
     * @param timestamp 时间戳（毫秒）
     * @param timeZone 时区，默认为系统时区
     * @return 格式化后的字符串 (yyyy-MM-dd HH:mm:ss)
     */
    fun formatTimestamp(
        timestamp: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val dateTime = instant.toLocalDateTime(timeZone)
        return dateTime.format(Formatters.STANDARD)
    }

    /**
     * 将时间戳（毫秒）格式化为短格式字符串
     * @param timestamp 时间戳（毫秒）
     * @param timeZone 时区，默认为系统时区
     * @return 格式化后的字符串 (yyyy/MM/dd HH:mm)
     */
    fun formatTimestampShort(
        timestamp: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): String {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        val dateTime = instant.toLocalDateTime(timeZone)
        return dateTime.format(Formatters.SLASH_SHORT)
    }

    /**
     * 将时间戳（毫秒）转换为 LocalDateTime
     * @param timestamp 时间戳（毫秒）
     * @param timeZone 时区，默认为系统时区
     * @return LocalDateTime
     */
    fun timestampToLocalDateTime(
        timestamp: Long,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): LocalDateTime {
        val instant = Instant.fromEpochMilliseconds(timestamp)
        return instant.toLocalDateTime(timeZone)
    }

    /**
     * 将 LocalDateTime 转换为时间戳（毫秒）
     * @param dateTime LocalDateTime
     * @param timeZone 时区，默认为系统时区
     * @return 时间戳（毫秒）
     */
    fun localDateTimeToTimestamp(
        dateTime: LocalDateTime,
        timeZone: TimeZone = TimeZone.currentSystemDefault()
    ): Long {
        val instant = dateTime.toInstant(timeZone)
        return instant.toEpochMilliseconds()
    }

    /**
     * 获取当前时间戳（毫秒）
     */
    fun now(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()

    /**
     * 获取当前日期（紧凑格式 yyyyMMdd）
     * @param timeZone 时区，默认为系统时区
     */
    fun todayCompact(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
        val today = kotlin.time.Clock.System.todayIn(timeZone)
        return today.format(Formatters.COMPACT_DATE)
    }

    /**
     * 获取当前 LocalDateTime
     * @param timeZone 时区，默认为系统时区
     */
    fun nowLocalDateTime(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime {
        return kotlin.time.Clock.System.now().toLocalDateTime(timeZone)
    }
}

/**
 * Long 扩展函数：格式化时间戳为标准格式
 */
fun Long.formatTimestamp(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    return DateTimeUtils.formatTimestamp(this, timeZone)
}

/**
 * Long 扩展函数：格式化时间戳为短格式
 */
fun Long.formatTimestampShort(timeZone: TimeZone = TimeZone.currentSystemDefault()): String {
    return DateTimeUtils.formatTimestampShort(this, timeZone)
}

/**
 * Long 扩展函数：转换为 LocalDateTime
 */
fun Long.toLocalDateTime(timeZone: TimeZone = TimeZone.currentSystemDefault()): LocalDateTime {
    return DateTimeUtils.timestampToLocalDateTime(this, timeZone)
}

/**
 * LocalDateTime 扩展函数：转换为时间戳（毫秒）
 */
fun LocalDateTime.toTimestamp(timeZone: TimeZone = TimeZone.currentSystemDefault()): Long {
    return DateTimeUtils.localDateTimeToTimestamp(this, timeZone)
}
