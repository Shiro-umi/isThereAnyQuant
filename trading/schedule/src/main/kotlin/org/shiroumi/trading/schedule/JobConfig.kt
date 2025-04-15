package org.shiroumi.trading.schedule
import kotlinx.serialization.Serializable

@Serializable
data class JobConfig(
    val jobName: String,
    val cronExpression: String,
    val jobClass: String,
    val enable: Boolean = true,
    val parameters: Map<String, String> = emptyMap()
)
