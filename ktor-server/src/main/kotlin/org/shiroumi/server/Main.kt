package org.shiroumi.server

import ScheduledTasks
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import ktor.module.llm.agent.CandleSignalAgent
import ktor.module.llm.agent.HighProbAreaAgent
import ktor.module.llm.agent.OverviewAgent
import logger
import org.openqa.selenium.PrintsPage
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.print.PageMargin
import org.openqa.selenium.print.PrintOptions
import org.shiroumi.database.functioncalling.JoinedCandles
import org.shiroumi.database.functioncalling.getJoinedCandles
import org.shiroumi.network.apis.getThsHotStocks
import org.shiroumi.network.apis.tushare
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.*
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi


private val logger by logger("Processor")

private val json1 = Json { prettyPrint = true }

fun main() {
    runBlocking {
//        val res = DispatcherAgent().chat().choices[0]
        val res1 = OverviewAgent().chat(tsCode = "300046.SZ").choices[0]
        logger.notify(json1.encodeToString(res1))
        logger.accept(res1.message.content)
        val res2 = HighProbAreaAgent().chat(tsCode = "300046.SZ", res1.message.content).choices[0]
        logger.notify(json1.encodeToString(res2))
        logger.accept(res2.message.content)
        val res3 = CandleSignalAgent().chat(tsCode = "300046.SZ", res2.message.content).choices[0]
        logger.notify(json1.encodeToString(res3))
        logger.accept(res3.message.content)
    }
//    runBlocking {
//        updateStockBasic()
//        updateDailyCandles()
//        calculateAdjCandle()
//    }
}

// vm entry
@OptIn(ExperimentalAtomicApi::class)
fun main1(args: Array<String>) {

    // startup
    runBlocking {

//        val arg = args.firstOrNull() ?: run {
//            logger.error("params need. current arg[0] is null")
//            return@runBlocking
//        }
        val arg = "300046.SZ"
//        val arg = "300046.SZ"

        if (arg == "thsHot") {
            processThsHot(20)
            return@runBlocking
        }
        if (arg.isNotBlank() && ((".SZ" in arg) || ".SH" in arg)) {
            processSpecific(arg)
            return@runBlocking
        }
        logger.error("params need. current arg[0] is $arg")
    }
}

val today: String
    get() = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"))

@OptIn(ExperimentalAtomicApi::class)
private suspend fun processThsHot(count: Int) {
    val thsHot = mutableMapOf<String, Pair<String, String>>()
    tushare.getThsHotStocks(tradeDate = today).check()!!.items.forEach {
        thsHot["${it[2]}"] = "${it[3]}" to "${it[4]}"
    }
    val sorted = thsHot.toList().subList(0, count)
    val scheduledTasks = ScheduledTasks<Any>()
    val count = AtomicInt(0)
    val tasks = sorted.map { (tsCode, pair) ->
        suspend {
            processSpecific(tsCode)
            logger.accept("code: $tsCode done. ${count.addAndFetch(1)}/count")
        }
    }
    scheduledTasks.emit("reasoning", *tasks.toTypedArray())
    scheduledTasks.schedule().collect()
}

//val llm: SiliconFlow by lazy { deepseekV3P1 }

private suspend fun processSpecific(tsCode: String, date: String? = null) = coroutineScope {
    val stock = getJoinedCandles(tsCode, limit = 30, date ?: today)
    if (stock.res.isEmpty()) {
        println("no candle list.")
        return@coroutineScope
    }
//    llm.chat(getUserPrompt(""), jsonMode = true, enableThinking = false) { json ->
//        val fj = File("${System.getProperty("user.dir")}/${stock.name}.json")
//        fj.writeText(json)
//        logger.notify("result of $tsCode saved to file \"${stock.tsCode}.json\"")
////        launch {
////            llm.chat(
////                msg = """
////                    根据给出的json数据生成html页面
////                    输入数据：
////                    $json
////                """.trimIndent(), jsonMode = true, enableThinking = false
////            ) { html ->
////                val pdfj = File("${System.getProperty("user.dir")}/${stock.name}.html")
////                pdfj.writeText(Json.parseToJsonElement(html).jsonObject["data"]!!.jsonPrimitive.content)
////                launch { pdfj.exportHtmlToPdf(stock) }
////            }
////        }
//    }
}

private suspend fun File.exportHtmlToPdf(stock: JoinedCandles) {
    val options = ChromeOptions()
    // options.addArguments("--headless");
    // options.addArguments("--start-maximized");
    options.addArguments("--disable-infobars")
    options.addArguments("--disable-extensions")
    var driver: WebDriver? = null
    try {
        driver = ChromeDriver(options)
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(10))
        driver.get("file://${this.absolutePath}")
        delay(1000)
        val printOptions = PrintOptions()
        printOptions.pageMargin = PageMargin(0.0, 0.0, 0.0, 0.0)
        printOptions.background = true
        val printsPage = driver as PrintsPage?
        val pdf = printsPage!!.print(printOptions)
        val decodedBytes = Base64.getDecoder().decode(pdf.content)
        Files.write(
            Paths.get("/Volumes/RAID/storage/public/LLMDecision/${stock.name}(${stock.tsCode}).pdf"),
            decodedBytes
        )
        logger.notify("pdf exported (use DCP)")
    } catch (e: InterruptedException) {
        e.printStackTrace()
    } finally {
        if (driver != null) {
            println("chrome exiting..")
            driver.quit()
            this@exportHtmlToPdf.delete()
        }
    }
}

//private val deepseekR1: SiliconFlow
//    get() = SiliconFlow(
//        prompt = systemPrompt,
//        model = SiliconFlow.SiliconFlowModel.DeepSeekR1
//    )
//
//private val deepseekV3P1: SiliconFlow
//    get() = SiliconFlow(
//        prompt = systemPrompt,
//        model = SiliconFlow.SiliconFlowModel.DeepSeekV3P1
//    )
//
//private val deepseekV3: SiliconFlow
//    get() = SiliconFlow(
//        prompt = systemPrompt,
//        model = SiliconFlow.SiliconFlowModel.DeepSeekV3
//    )


// application entry
suspend fun startApplication() = coroutineScope {
//    EngineMain.org.shiroumi.ktor.main(arrayOf())
//    updateSymbol()
//    updateTradingDate()
//    updateStockCandles()
}

val rootDir: String = System.getProperty("user.dir")
//private val systemPrompt = File("$rootDir/sys_prompt").readLines().joinToString("")
//private val userPrompt = File("$rootDir/usr_prompt").readLines().joinToString("")
//
//private fun getUserPrompt(data: Any) = """
//$userPrompt
//
//$data
//""".trimIndent()


private fun buildJsonPrompt() {
    fun jsonObject(desc: String, value: String) = buildJsonObject {
        put("value", value)
        put("description", desc)
    }

    fun jsonObject(desc: String, builder: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        builder()
        put("description", desc)
    }

    val json = buildJsonObject {
        put("title", jsonObject("文章标题") {
            put("text", jsonObject("文章标题文字", "恒宝股份(002104.SZ)"))
            put("emoji", jsonObject("文章标题emoji", ""))
        })
        put("basic_info", jsonObject("基本信息chapter") {
            put("value", buildJsonArray {
                add(jsonObject("基本信息block") {
                    put("stock_name", jsonObject("股票中文名称", "恒宝股份"))
                    put("stock_code", jsonObject("股票代码", "002104.SZ"))
                    put("pivot_date", jsonObject("分析基准日期", "2025-09-19"))
                    put("curr_price", jsonObject("当前价格", "24.02元"))
                    put("recent_offset", jsonObject("周期内波动率", "高(平均8.7%)"))
                })
            })
            put(
                "basic_info_keynote",
                jsonObject("一句话描述重点关注类型", "分析基于近30天价格数据，重点关注波段结构和关键价格水平。")
            )
        })
        put("important_pa_signal", jsonObject("关键价格行为学信号chapter") {
            put("value", buildJsonArray {
                add(jsonObject("波段位置row") {
                    put("name", jsonObject("名称", "波段位置"))
                    put("state", jsonObject("状态", "第3波后期"))
                    put("strength", jsonObject("强度", "高风险"))
                    put("keynote", jsonObject("关键信息", "已过最佳入场时机"))
                })
                add(jsonObject("趋势结构row") {
                    put("name", jsonObject("名称", "趋势结构"))
                    put("state", jsonObject("状态", "下降趋势"))
                    put("strength", jsonObject("强度", "中等"))
                    put("keynote", jsonObject("关键信息", "从30.50元高点回落"))
                })
                add(jsonObject("关键阻力row") {
                    put("name", jsonObject("名称", "关键阻力"))
                    put("state", jsonObject("状态", "26.30元"))
                    put("strength", jsonObject("强度", "强阻力"))
                    put("keynote", jsonObject("关键信息", "9月18日高点"))
                })
                add(jsonObject("关键支撑row") {
                    put("name", jsonObject("名称", "关键支撑"))
                    put("state", jsonObject("状态", "23.58元"))
                    put("strength", jsonObject("强度", "重要支撑"))
                    put("keynote", jsonObject("关键信息", "9月3日低点"))
                })
                add(jsonObject("IBS水平row") {
                    put("name", jsonObject("名称", "IBS水平"))
                    put("state", jsonObject("状态", "36%"))
                    put("strength", jsonObject("强度", "偏弱"))
                    put("keynote", jsonObject("关键信息", "收盘接近日低点"))
                })
            })
            put("important_pa_signal_keynote", jsonObject("一句话描述当前阶段") {
                put("value", "当前处于下降趋势中的第3波后期，不符合高胜率交易条件。")
                put("emoji", "")
            })
        })
        put("trading_strategy", jsonObject("交易策略chapter") {
            put("value", buildJsonArray {
                add(jsonObject("R值设定sub_chapter") {
                    put("R_value", jsonObject("R值来源", "R值(风险单位): 1.5元 (基于近期平均波幅6.2%计算)"))
                    put(
                        "reason",
                        jsonObject("设置原因", "设定原因：当前市场波动率较高，需要适当放宽止损空间以避免被正常波动扫损。")
                    )
                })
                add(jsonObject("入场策略sub_chapter") {
                    put("value", buildJsonArray {
                        add(jsonObject("无底仓row") {
                            put("status", jsonObject("持仓状态", "无底仓"))
                            put("strategy", jsonObject("策略", "观望"))
                            put("trade_condition", jsonObject("触发条件", "等待突破26.30元或反弹至理想位置"))
                            put("position_percent", jsonObject("仓位", "0%"))
                        })
                        add(jsonObject("有底仓row") {
                            put("status", jsonObject("持仓状态", "有底仓"))
                            put("strategy", jsonObject("策略", "分批减仓"))
                            put("trade_condition", jsonObject("触发条件", "反弹至25.50-26.00元区域"))
                            put("position_percent", jsonObject("仓位", "每次减20-30%"))
                        })
                    })
                })
                add(jsonObject("止盈策略sub_chapter") {
                    put("value", buildJsonArray {
                        add(jsonObject("无底仓row") {
                            put("first_goal", jsonObject("第一目标", "不适用"))
                            put("second_goal", jsonObject("第二目标", "不适用"))
                            put("expect_profit", jsonObject("预期收益", "不适用"))
                        })
                        add(jsonObject("有底仓row") {
                            put("first_goal", jsonObject("第一目标", "25.50元(1R)"))
                            put("second_goal", jsonObject("第二目标", "26.30元(1.8R)"))
                            put("expect_profit", jsonObject("预期收益", "6.2%-9.5%"))
                        })
                    })
                })
                add(jsonObject("止损策略sub_chapter") {
                    put("value", buildJsonArray {
                        add(jsonObject("止损策略1row") {
                            put("give_up_price", jsonObject("止损位置", "收盘跌破23.50元"))
                            put("risk", jsonObject("风险控制", "单笔风险<2%"))
                        })
                    })
                })
                add(jsonObject("盈亏比评估sub_chapter") {
                    put("value", buildJsonArray {
                        add(jsonObject("盘面情况1row") {
                            put("condition", jsonObject("盘面情况", "反弹至阻力位"))
                            put("probability", jsonObject("概率", "45%"))
                            put("expect_profit", jsonObject("预期收益", "1.2R"))
                            put("profit_loss_ratio ", jsonObject("盈亏比", "1.2:1"))
                        })
                        add(jsonObject("盘面情况2row") {
                            put("condition", jsonObject("盘面情况", "继续下跌"))
                            put("probability", jsonObject("概率", "35%"))
                            put("expect_profit", jsonObject("预期收益", "-1R"))
                            put("profit_loss_ratio ", jsonObject("盈亏比", "-"))
                        })

                    })
                })
            })
            put("trading_strategy_keynote", jsonObject("一句话描述交易策略") {
                put("emoji", "🤡")
                put("value", "综合盈亏比: 1.1:1 (偏低，不符合高胜率交易标准)")
            })
        })
        put("risk_notation", jsonObject("风险注意事项chapter") {
            put("value", buildJsonArray {
                add(JsonPrimitive("当前处于第3波后期，失败率超过70%"))
                add(JsonPrimitive("T+1限制增加日内风险，无法及时止损"))
                add(JsonPrimitive("成交量萎缩，缺乏推动上涨的动力"))
                add(JsonPrimitive("关键支撑23.58元尚未被突破，提供短期支撑"))
            })
        })
        put("summarize", jsonObject("总结chapter") {
            put("value", buildJsonArray {
                add(jsonObject("综合胜率row") {
                    put("probability", jsonObject("综合胜率", "45%胜率"))
                })
                add(jsonObject("综合盈亏比row") {
                    put("profit_loss_ratio", jsonObject("综合盈亏比", "1.1:1"))
                })
                add(jsonObject("是否值得交易row") {
                    put("if_worthy", jsonObject("是否值得交易:", "不推荐开新仓"))
                })
                add(jsonObject("评分row") {
                    put("score", jsonObject("综合评分", "2/10"))
                })
                add(jsonObject("综合盈亏比row") {
                    put("profit_loss_ratio", jsonObject("综合盈亏比", "1.1:1"))
                })
            })
        })
        put("action_suggestion", jsonObject("行动建议chapter") {
            put("value", buildJsonArray {
                add(jsonObject("无底仓row") {
                    put("suggestion", jsonObject("行动建议", "观望"))
                    put("todo", jsonObject("后续操作", "等待更好的入场时机或明确突破信号"))
                })
                add(jsonObject("有底仓row") {
                    put("suggestion", jsonObject("行动建议", "逢反弹减仓"))
                    put("todo", jsonObject("后续操作", "在25.50-26.00元区域分批减仓"))
                })
            })
        })
        put("last_talk", jsonObject("tips") {
            put(
                "value",
                "当前股价结构不符合高胜率交易条件，建议等待价格形成新的早期波段结构或明确突破关键阻力位再考虑入场。"
            )
        })
    }
//    logger.warning("$json")
    val f = File("${System.getProperty("user.dir")}/xxx")
    f.writeText("$json")
    return
}

private val mdPrompt: String = """
    你是一个网页生成助手，需要你以下面的html页面为模板生成html页面
    <!DOCTYPE html><html lang="zh-CN"><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width, initial-scale=1.0"><title>山子高科(000981.SZ) - 技术分析报告</title><script src="https://cdn.tailwindcss.com"></script><style>            body {                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, "Noto Sans", sans-serif, "Apple Color Emoji", "Segoe UI Emoji", "Segoe UI Symbol", "Noto Color Emoji";            }</style></head><body class="bg-slate-900 text-slate-300"><div class="container mx-auto max-w-4xl p-4 sm:p-6 lg:p-8"><header class="mb-10 text-center"><h1 class="text-4xl md:text-5xl font-bold text-white mb-2">🤖 山子高科 (000981.SZ)</h1><p class="text-lg text-slate-400">技术分析报告</p></header><section class="mb-10"><h2 class="text-2xl font-semibold text-cyan-400 border-b border-cyan-400/30 pb-2 mb-4">基本信息</h2><div class="bg-slate-800 rounded-lg shadow-lg overflow-hidden"><table class="w-full text-left"><tbody class="divide-y divide-slate-700"><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-slate-400">股票名称</td><td class="px-6 py-4 text-white">山子高科 (000981.SZ)</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-slate-400">分析基准日期</td><td class="px-6 py-4 text-white">2025-09-19</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-slate-400">当前价格</td><td class="px-6 py-4 text-white font-bold text-lg">3.87元</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-slate-400">周期内波动率</td><td class="px-6 py-4 text-amber-400">极高 (平均15.2%)</td></tr></tbody></table></div><p class="mt-4 text-sm text-slate-400 bg-slate-800/50 p-3 rounded-lg"><strong>核心关注点：</strong>分析基于近30天价格数据，重点关注波段结构和关键价格水平。</p></section><section class="mb-10"><h2 class="text-2xl font-semibold text-cyan-400 border-b border-cyan-400/30 pb-2 mb-4">🚀 关键价格行为学信号</h2><div class="bg-cyan-900/20 border border-cyan-400/30 text-cyan-300 p-4 rounded-lg mb-6 shadow-lg"><p class="font-bold">核心判断：</p><p>当前处于突破下降趋势后的第1波上升初期，符合高胜率交易条件。</p></div><div class="bg-slate-800 rounded-lg shadow-lg overflow-x-auto"><table class="w-full text-left min-w-max"><thead class="bg-slate-700/50 text-xs text-slate-400 uppercase"><tr><th class="px-6 py-3">名称</th><th class="px-6 py-3">状态</th><th class="px-6 py-3">强度</th><th class="px-6 py-3">关键信息</th></tr></thead><tbody class="divide-y divide-slate-700"><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">波段位置</td><td class="px-6 py-4">第1波上升初期</td><td class="px-6 py-4 text-emerald-400">高潜力</td><td class="px-6 py-4">突破下降趋势线后首波反弹</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">趋势结构</td><td class="px-6 py-4">反转初期</td><td class="px-6 py-4 text-emerald-400">中等偏强</td><td class="px-6 py-4">从2.01元低点反弹至3.87元</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">关键阻力</td><td class="px-6 py-4 text-red-400">4.36元</td><td class="px-6 py-4 text-red-400">强阻力</td><td class="px-6 py-4">9月19日高点</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">关键支撑</td><td class="px-6 py-4 text-green-400">3.66元</td><td class="px-6 py-4 text-green-400">日内支撑</td><td class="px-6 py-4">9月19日低点</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">IBS水平</td><td class="px-6 py-4">48%</td><td class="px-6 py-4 text-slate-400">中性</td><td class="px-6 py-4">收盘于日波动区间中部</td></tr></tbody></table></div></section><section class="mb-10"><h2 class="text-2xl font-semibold text-cyan-400 border-b border-cyan-400/30 pb-2 mb-4">🎯 交易策略</h2><div class="bg-slate-800 p-4 rounded-lg mb-6 shadow-lg text-center"><p class="text-slate-400">综合盈亏比</p><p class="text-3xl font-bold text-emerald-400">2.7 : 1 (优良)</p><p class="text-xs text-slate-500 mt-1">符合早期波段交易标准</p></div><div class="space-y-8"><div><h3 class="text-lg font-semibold text-white mb-2">1. R值设定 (风险单位)</h3><ul class="list-disc list-inside text-slate-400 space-y-1 pl-2"><li><strong>R值:</strong><span class="text-white">0.21元</span></li><li><strong>计算依据:</strong> 基于近期平均波幅15.2%计算。</li><li><strong>设定原因:</strong> 高波动环境需匹配更大止损空间，取9月19日低点3.66元下方0.5%作为技术止损位。</li></ul></div><div class="grid grid-cols-1 md:grid-cols-2 gap-8"><div><h3 class="text-lg font-semibold text-white mb-2">2. 入场策略</h3><div class="bg-slate-800 rounded-lg shadow-lg overflow-x-auto"><table class="w-full text-left min-w-max"><thead class="bg-slate-700/50 text-xs text-slate-400 uppercase"><tr><th class="px-4 py-2">持仓状态</th><th class="px-4 py-2">策略</th><th class="px-4 py-2">触发条件</th></tr></thead><tbody class="divide-y divide-slate-700"><tr><td class="px-4 py-2 font-medium">无底仓</td><td class="px-4 py-2">突破买入</td><td class="px-4 py-2">收盘价站稳3.90元</td></tr><tr><td class="px-4 py-2 font-medium">有底仓</td><td class="px-4 py-2">持有待涨</td><td class="px-4 py-2">回撤至3.70-3.75元补仓</td></tr></tbody></table></div></div><div><h3 class="text-lg font-semibold text-white mb-2">3. 止盈策略</h3><div class="bg-slate-800 rounded-lg shadow-lg overflow-x-auto"><table class="w-full text-left min-w-max"><thead class="bg-slate-700/50 text-xs text-slate-400 uppercase"><tr><th class="px-4 py-2">持仓状态</th><th class="px-4 py-2">第一目标</th><th class="px-4 py-2">第二目标</th></tr></thead><tbody class="divide-y divide-slate-700"><tr><td class="px-4 py-2 font-medium">无底仓</td><td class="px-4 py-2">4.10元</td><td class="px-4 py-2">4.36元</td></tr><tr><td class="px-4 py-2 font-medium">有底仓</td><td class="px-4 py-2">4.20元</td><td class="px-4 py-2">4.50元</td></tr></tbody></table></div></div></div><div class="grid grid-cols-1 md:grid-cols-2 gap-8"><div><h3 class="text-lg font-semibold text-white mb-2">4. 止损策略</h3><div class="bg-red-900/20 border border-red-500/30 p-4 rounded-lg text-red-300"><p><strong>止损位置:</strong> 收盘价有效跌破<strong class="text-red-400">3.65元</strong></p><p><strong>风险控制:</strong> 确保单笔交易风险低于总资金的<strong>1.5%</strong></p></div></div><div><h3 class="text-lg font-semibold text-white mb-2">5. 盈亏比评估</h3><div class="bg-slate-800 rounded-lg shadow-lg overflow-x-auto"><table class="w-full text-left min-w-max"><thead class="bg-slate-700/50 text-xs text-slate-400 uppercase"><tr><th class="px-4 py-2">盘面情况</th><th class="px-4 py-2">概率</th><th class="px-4 py-2">盈亏比</th></tr></thead><tbody class="divide-y divide-slate-700"><tr><td class="px-4 py-2 font-medium">延续反弹</td><td class="px-4 py-2">68%</td><td class="px-4 py-2 text-emerald-400">3:1</td></tr><tr><td class="px-4 py-2 font-medium">回踩确认</td><td class="px-4 py-2">25%</td><td class="px-4 py-2 text-amber-400">1.5:1</td></tr></tbody></table></div></div></div></div></section><section class="mb-10"><h2 class="text-2xl font-semibold text-amber-400 border-b border-amber-400/30 pb-2 mb-4">⚠️ 风险注意事项</h2><div class="bg-slate-800 p-6 rounded-lg shadow-lg"><ul class="list-disc list-inside space-y-3 text-amber-300"><li>T+1交易制度限制下，需严格防范隔夜跳空风险。</li><li>9月19日成交量异常放大，需警惕短期获利盘回吐压力。</li><li>短期涨幅过大（月内涨幅达92%），存在技术性回调修正的需求。</li><li>前期高点4.36元构成强劲阻力位，首次尝试突破可能会失败。</li></ul></div></section><section class="mb-10"><h2 class="text-2xl font-semibold text-cyan-400 border-b border-cyan-400/30 pb-2 mb-4">📈 总结与评级</h2><div class="grid grid-cols-2 md:grid-cols-4 gap-4 text-center"><div class="bg-slate-800 p-4 rounded-lg shadow-lg"><p class="text-sm text-slate-400">综合胜率</p><p class="text-2xl font-bold text-white">68%</p></div><div class="bg-slate-800 p-4 rounded-lg shadow-lg"><p class="text-sm text-slate-400">综合盈亏比</p><p class="text-2xl font-bold text-white">2.7:1</p></div><div class="bg-slate-800 p-4 rounded-lg shadow-lg"><p class="text-sm text-slate-400">交易建议</p><p class="text-xl font-bold text-amber-400">谨慎参与</p></div><div class="bg-slate-800 p-4 rounded-lg shadow-lg"><p class="text-sm text-slate-400">综合评分</p><p class="text-2xl font-bold text-white">7/10</p></div></div></section><section class="mb-10"><h2 class="text-2xl font-semibold text-cyan-400 border-b border-cyan-400/30 pb-2 mb-4">💡 行动建议</h2><div class="bg-slate-800 rounded-lg shadow-lg overflow-x-auto"><table class="w-full text-left min-w-max"><thead class="bg-slate-700/50 text-xs text-slate-400 uppercase"><tr><th class="px-6 py-3">当前状态</th><th class="px-6 py-3">行动建议</th><th class="px-6 py-3">具体操作</th></tr></thead><tbody class="divide-y divide-slate-700"><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">无底仓</td><td class="px-6 py-4 text-emerald-400 font-semibold">突破买入</td><td class="px-6 py-4">等待收盘价站稳3.90元建立首仓，若有回撤至3.70元附近可考虑加仓。</td></tr><tr class="hover:bg-slate-700/50"><td class="px-6 py-4 font-medium text-white">有底仓</td><td class="px-6 py-4 text-sky-400 font-semibold">持股待涨</td><td class="px-6 py-4">在4.10元附近部分止盈锁定利润，在4.36元关键阻力位附近全部离场。</td></tr></tbody></table></div></section><footer class="text-center pt-8 border-t border-slate-700"><p class="text-slate-400">当前股价处于高波动性的反转初期，符合Al Brooks理论中的早期波段交易原则。操作上需注意T+1制度下的隔夜风险，建议将总仓位控制在50%以内，以应对潜在的剧烈波动。</p></footer></div></body></html>
    要求如下
        使用material design 3的风格
        适配手机屏幕的尺寸
        保证所有文字都能完整显示，这非常重要
        增加边距
        不要使用过重的阴影
        使用暗色主题，不要出现白色或透明色的北京，这非常重要
        不要使用饱和度太高的颜色，这非常重要
        如果最终评分是积极的，标题emoji使用🚀，否则使用🤡，这非常重要
        sub_chapter标题的字号要小于chapter的字号，这非常重要
        不要使用过大的标题字号，这非常重要
        标签中含有row的数据使用列表呈现
    
    使用以下格式返回:
    {
        "data": "html here"
    }

""".trimIndent()