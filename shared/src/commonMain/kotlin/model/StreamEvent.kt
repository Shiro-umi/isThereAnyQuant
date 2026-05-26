package model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class StreamEvent {
    @Serializable
    @SerialName("reasoning")
    data class Reasoning(val content: String) : StreamEvent()

    @Serializable
    @SerialName("content")
    data class Content(val content: String, val stepId: String? = null) : StreamEvent()

    @Serializable
    @SerialName("tool_call")
    data class ToolCall(val toolName: String, val arguments: String) : StreamEvent()

    @Serializable
    @SerialName("tool_result")
    data class ToolResult(val toolName: String, val result: String) : StreamEvent()

    @Serializable
    @SerialName("plan_generated")
    data class PlanGenerated(val plan: Plan) : StreamEvent()

    @Serializable
    @SerialName("plan_step_start")
    data class PlanStepStart(val stepId: String, val stepName: String, val stepDescription: String) : StreamEvent()

    @Serializable
    @SerialName("plan_step_complete")
    data class PlanStepComplete(val stepId: String, val stepName: String, val success: Boolean, val result: String? = null) : StreamEvent()

    @Serializable
    @SerialName("plan_summary_start")
    object PlanSummaryStart : StreamEvent()
}

@Serializable
data class Plan(
    val steps: List<PlanStep>
)

@Serializable
data class PlanStep(
    val id: String,
    val name: String,
    val description: String
)