package utils

import kotlin.reflect.KProperty

const val RED = "\u001B[31m"
const val GREEN = "\u001B[32m"
const val YELLOW = "\u001B[33m"
const val PURPLE = "\u001B[35m"
const val RESET = "\u001B[0m"

interface Logger {

    val className: String

    fun info(content: String) = println("[INFO] [$className] $content")

    fun error(content: String) = println("$RED[ERR] [$className] $content$RESET")

    fun accept(content: String) = println("$GREEN[ACCEPT] [$className] $content$RESET")

    fun warning(content: String) = println("$YELLOW[WARNING] [$className] $content$RESET")

    fun notify(content: String) = println("$PURPLE[NOTIFY] [$className] $content$RESET")
}

abstract class LoggerDelegate {

    abstract val clsName: String

    operator fun getValue(
        thisRef: Any?,
        property: KProperty<*>,
    ): Logger = object : Logger {
        override val className: String = clsName
    }
}

fun logger(className: String) = object : LoggerDelegate() {
    override val clsName: String = className
}


