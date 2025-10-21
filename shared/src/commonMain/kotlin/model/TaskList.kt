package model

import kotlinx.serialization.Serializable

@Serializable
data class TaskList(
    val pendingList: List<Quant> = listOf(),
    val runningList: List<Quant> = listOf(),
    val errorList: List<Quant> = listOf(),
    val doneList: List<Quant> = listOf(),
)