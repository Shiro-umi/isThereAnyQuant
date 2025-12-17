package ktor.module.llm.agent.abs

import kotlinx.serialization.json.Json
import org.shiroumi.database.old.functioncalling.getJoinedCandles
import org.shiroumi.network.apis.ChatCompletion
import org.shiroumi.network.apis.LLMApi
import org.shiroumi.network.siliconFlow

abstract class AbsCandleAgent(
    historyProvider: () -> History = { History() }
) : Agent<LLMApi>(siliconFlow(), historyProvider) {

    abstract val suffixMsgs: List<String>

    private val json = Json { prettyPrint = true }

    open suspend fun chat(tsCode: String, msgs: List<String> = listOf()): ChatCompletion {
        val msg = mutableListOf(prompts.usr, getJoinedCandles(tsCode = tsCode)).also { list ->
            list.addAll(msgs)
            list.addAll(suffixMsgs)
        }
        logger.warning("history: ${msg.joinToString("\n")}")
        val res = chat(Role.User provides msg.joinToString("\n"))
        logger.accept(json.encodeToString(res))
        return res
    }
}


//@FunctionCall(description = "收集最新单根k线交易信号")
//suspend fun collectCandleSignal(@Description("股票代码") tsCode: String): String {
//    val candle30 = get30minCandles(tsCode)
//    val engulfingBar = findEngulfingBars(candle30)
//    val pinBar = findPinBarsWithFilters(candle30)
//    val surpriseBar = findSurpriseBars(candle30)
//    return buildJsonObject {
//        put("engulfingBar", "$engulfingBar")
//        put("pinBar", "$pinBar")
//        put("surpriseBar", "$surpriseBar")
//    }.toString()
//}
//
//@FunctionCall(description = "收集k线波段形态")
//suspend fun collectShapes(@Description("股票代码") tsCode: String): String {
//    val candle30 = get30minCandles(tsCode)
//    val doubleTripleExtreme = findDoubleTripleTopBottoms(candle30)
//    val headAndShoulder = findHeadsAndShoulders(candle30)
//    val rect = findRectangles(candle30)
//    val wedge = findWedgePatterns(candle30)
//    return json.encodeToString(
//        buildJsonObject {
//            put("doubleTripleExtreme", json.encodeToString(doubleTripleExtreme))
//            put("headAndShoulder", json.encodeToString(headAndShoulder))
//            put("rect", json.encodeToString(rect))
//            put("wedge", json.encodeToString(wedge))
//        }
//    )
//}

private val json = Json { prettyPrint = true }