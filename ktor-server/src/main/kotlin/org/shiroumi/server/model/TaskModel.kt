package org.shiroumi.server.model

import kotlinx.serialization.Serializable

@Serializable
data class TaskModel(
    val id: String,
    val code: String,
    val tradeDate: String,
    val name: String,
    val status: String,
    val progress: Progress
)

// 用于通过 WebSocket 传输的进度数据模型
@Serializable
data class Progress(
    val step: Int = 0,
    val totalStep: Int = 0,
    val description: String = "",
    val percentage: Float = 0f
)

