package org.shiroumi.network.apis

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import org.shiroumi.config.ConfigHolder
import org.shiroumi.network.ApiClient
import org.shiroumi.network.TushareRateLimiter
import org.shiroumi.network.tushare
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 全局 API 实例
 */
val tushare: TuShareApi by tushare()

/**
 * Tushare API 接口
 */
interface TuShareApi {
    suspend fun query(body: TushareRequest): BaseTushare
}

/**
 * Tushare API 实现
 */
class TuShareApiImpl(private val client: ApiClient) : TuShareApi {
    override suspend fun query(body: TushareRequest): BaseTushare {
        TushareRateLimiter.acquire(body.apiName, body.token)
        return client.post("/", body)
    }
}

/**
 * Tushare 请求体
 */
@Serializable
data class TushareRequest(
    @SerialName("api_name") val apiName: String,
    val token: String,
    val params: Map<String, String> = emptyMap(),
    val fields: List<String> = emptyList()
)

suspend fun TuShareApi.getThsHotStocks(tradeDate: String) = query(
    tushareParams.ofApi("ths_hot")
        .carriesParam(mutableMapOf<String, String>().apply {
            put("trade_date", tradeDate)
            put("market", "热股")
            put("is_new", "N")
        }).toRequest()
)

suspend fun TuShareApi.getStockBasic() = query(
    tushareParams.ofApi("stock_basic")
        .requireFields(
            listOf(
                "ts_code",
                "symbol",
                "name",
                "area",
                "industry",
                "fullname",
                "enname",
                "cnspell",
                "market",
                "exchange",
                "curr_type",
                "list_status",
                "list_date",
                "delist_date",
                "is_hs",
                "act_name",
                "act_ent_type",
            )
        ).toRequest()
)

suspend fun TuShareApi.getAdjFactor(
    tsCode: String? = null,
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) =
    query(
        tushareParams.ofApi("adj_factor").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
                startDate?.let { put("start_date", it) }
                endDate?.let { put("end_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getDailyCandles(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getDailyLimit(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("stk_limit").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getLimitListD(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
    limitType: String? = null,
    exchange: String? = null
) =
    query(
        tushareParams.ofApi("limit_list_d")
            .carriesParam(
                mutableMapOf<String, String>().apply {
                    tsCode?.let { put("ts_code", it) }
                    tradeDate?.let { put("trade_date", it) }
                    startDate?.let { put("start_date", it) }
                    endDate?.let { put("end_date", it) }
                    limitType?.let { put("limit_type", it) }
                    exchange?.let { put("exchange", it) }
                }
            )
            .requireFields(
                listOf(
                    "trade_date",
                    "ts_code",
                    "industry",
                    "name",
                    "close",
                    "pct_chg",
                    "amount",
                    "limit_amount",
                    "float_mv",
                    "total_mv",
                    "turnover_ratio",
                    "fd_amount",
                    "first_time",
                    "last_time",
                    "open_times",
                    "up_stat",
                    "limit_times",
                    "limit"
                )
            )
            .toRequest()
    )


suspend fun TuShareApi.getDailyInfo(tsCode: String? = null, date: String? = null) =
    query(
        tushareParams.ofApi("daily_basic").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                date?.let { put("trade_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getCalendar() = query(tushareParams.ofApi("trade_cal").toRequest())

suspend fun TuShareApi.getIndexDaily(
    tsCode: String? = null,
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("index_daily").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            date?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getIndexBasic(
    tsCode: String? = null,
    market: String? = null,
    publisher: String? = null,
    category: String? = null
) = query(
    tushareParams.ofApi("index_basic").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            market?.let { put("market", it) }
            publisher?.let { put("publisher", it) }
            category?.let { put("category", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getSwIndexClassify(src: String = "SW2021", parentCode: String? = null) =
    query(
        tushareParams.ofApi("index_classify").carriesParam(
            mutableMapOf<String, String>().apply {
                put("src", src)
                parentCode?.let { put("parent_code", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getSwIndexDailyCandle(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) =
    query(
        tushareParams.ofApi("sw_daily").carriesParam(
            mutableMapOf<String, String>().apply {
                tsCode?.let { put("ts_code", it) }
                tradeDate?.let { put("trade_date", it) }
                startDate?.let { put("start_date", it) }
                endDate?.let { put("end_date", it) }
            }
        ).toRequest())

suspend fun TuShareApi.getSwIndexMember(
    l1Code: String? = null,
    l2Code: String? = null,
    l3Code: String? = null,
    tsCode: String? = null
) = query(
    tushareParams.ofApi("index_member_all").carriesParam(
        mutableMapOf<String, String>().apply {
            l1Code?.let { put("l1_code", it) }
            l2Code?.let { put("l2_code", it) }
            l3Code?.let { put("l3_code", it) }
            tsCode?.let { put("ts_code", it) }
        }
    ).toRequest())

suspend fun TuShareApi.getKplConcept(tradeDate: String? = null) = query(
    tushareParams.ofApi("kpl_concept").carriesParam(
        mutableMapOf<String, String>().apply {
            tradeDate?.let { put("trade_date", it) }
        }
    ).toRequest()
)

suspend fun TuShareApi.getKplConceptCons(tsCode: String? = null, tradeDate: String? = null) = query(
    tushareParams.ofApi("kpl_concept_cons").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
        }
    ).toRequest()
)

/**
 * 开盘啦榜单（kpl_list）—— 题材群体维度的事实底座。
 *
 * 提供每日涨停个股的【题材归属 theme】与【连板高度 status】（如「3天2板」），覆盖 2019 起。
 * 研究用途：题材内资金聚集 → 题材运行越久(连板越高)/题材内负反馈越多 → 越易共振杀跌。
 * 注意：默认 tag=涨停（只含涨停股），题材内跌停/炸板负反馈需配合 limit_list_d。
 */
suspend fun TuShareApi.getKplList(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
) = query(
    tushareParams.ofApi("kpl_list").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "name", "trade_date", "theme", "status", "tag", "lu_desc", "turnover_rate")
    ).toRequest()
)

/**
 * 龙虎榜每日汇总（top_list）—— 方向极性维度的事实底座。
 *
 * 上榜事件本身（资金异动）+ 龙虎榜净买入方向（net_amount/net_rate），暴涨前抢筹 vs 暴跌前出逃形态不对称。
 * 覆盖 2005 起（实测 2009 起每日有数据）。营业部席位明细见 top_inst。
 */
suspend fun TuShareApi.getTopList(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
) = query(
    tushareParams.ofApi("top_list").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "trade_date", "ts_code", "name", "close", "pct_change", "turnover_rate",
            "amount", "l_sell", "l_buy", "l_amount", "net_amount", "net_rate",
            "amount_rate", "float_values", "reason"
        )
    ).toRequest()
)

/**
 * 龙虎榜机构/营业部成交明细（top_inst）—— 席位维度的事实底座。
 *
 * exalter 营业部名称：含「拉萨」=散户大本营、含「机构专用」=机构席位（分类在 pytorch 装配层做）。
 * 实测 2011-2012 起每日有数据。
 */
suspend fun TuShareApi.getTopInst(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
) = query(
    tushareParams.ofApi("top_inst").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "trade_date", "ts_code", "exalter", "side",
            "buy", "buy_rate", "sell", "sell_rate", "net_buy", "reason"
        )
    ).toRequest()
)

suspend fun TuShareApi.getAuction(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("stk_auction").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).toRequest()
)

/**
 * 获取股票当日实时分钟行情数据 (rt_min_daily)
 *
 * 一次性获取当天该级别全量K线（含正在形成中的最新一根），适合盘中实时刷新。
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param freq 分钟频度：1min / 5min / 15min / 30min / 60min
 */
suspend fun TuShareApi.getRtMinDaily(
    tsCode: String,
    freq: String
) = query(
    tushareParams.ofApi("rt_min_daily").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            put("freq", freq.uppercase()) // Tushare docs recommend uppercase freq
        }
    ).requireFields(
        listOf("ts_code", "time", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票分钟级历史行情数据 (stk_mins)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param freq 分钟频度：1min / 5min / 15min / 30min / 60min
 * @param startDate 开始时间，格式：2023-08-25 09:00:00
 * @param endDate 结束时间，格式：2023-08-25 19:00:00
 */
suspend fun TuShareApi.getStkMins(
    tsCode: String,
    freq: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("stk_mins").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            put("freq", freq)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_time", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票周K线数据 (weekly)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param startDate 开始日期，格式：YYYYMMDD
 * @param endDate 结束日期，格式：YYYYMMDD
 */
suspend fun TuShareApi.getWeeklyCandles(
    tsCode: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("weekly").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_date", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取股票月K线数据 (monthly)
 *
 * @param tsCode 股票代码，如 600000.SH
 * @param startDate 开始日期，格式：YYYYMMDD
 * @param endDate 结束日期，格式：YYYYMMDD
 */
suspend fun TuShareApi.getMonthlyCandles(
    tsCode: String,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("monthly").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("ts_code", "trade_date", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取实时日线行情 (rt_k)
 *
 * 支持股票代码和通配符，如 6*.SH, 3*.SZ, 688*.SH
 *
 * @param tsCode 股票代码或通配符列表，多个代码用英文逗号分隔
 */
suspend fun TuShareApi.getRtDaily(
    tsCode: String
) = query(
    tushareParams.ofApi("rt_k").carriesParam(
        mutableMapOf<String, String>().apply {
            put("ts_code", tsCode)
        }
    ).requireFields(
        listOf("ts_code", "name", "pre_close", "open", "close", "high", "low", "vol", "amount")
    ).toRequest()
)

/**
 * 获取券商研究报告。
 *
 * network 层只负责把上层筛选条件映射为 Tushare 参数，不负责：
 * - CLI 默认查询窗口
 * - 股票代码归一化
 * - 输出格式或摘要裁剪
 */
suspend fun TuShareApi.getResearchReport(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null,
    reportType: String? = null,
    instCsname: String? = null,
    indName: String? = null
) = query(
    tushareParams.ofApi("research_report").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
            reportType?.let { put("report_type", it) }
            instCsname?.let { put("inst_csname", it) }
            indName?.let { put("ind_name", it) }
        }
    ).requireFields(
        listOf(
            "trade_date",
            "title",
            "abstr",
            "report_type",
            "author",
            "name",
            "ts_code",
            "inst_csname",
            "ind_name",
            "url"
        )
    ).toRequest()
)

// ══════════════════════════════════════════════════════════════════════════
//  宏观—基本面抛压因子（factor topic）数据接口
//  设计文档：private/research-docs/macro-fundamental-pressure-formula.html §6
//  三条线：宏观信用（MAC）/ 资金流（IND/MAC）/ 个股基本面（FUN）
//  日期参数语义分两类：
//   - 宏观月频接口用 start_m/end_m（YYYYMM），季频 GDP 用 start_q/end_q（YYYYQn）
//   - 财务/资金接口用 start_date/end_date（YYYYMMDD，财务类是「公告日」范围）
//  财务类提供 useVip 开关：true → *_vip（整季全市场，需 5000 积分），false → 单股票普通版
// ══════════════════════════════════════════════════════════════════════════

// ───────────────────────── 线 M · 宏观信用 ─────────────────────────

/**
 * 社融增量（月度）(sf_month) —— MAC1 社融/信用脉冲基础量。
 *
 * 积分：2000；单次最大 2000 条，一次可取全部历史。
 * 日期参数为月份 YYYYMM（非 start_date/end_date）。
 *
 * @param month   单月 YYYYMM，支持逗号分隔多个月份
 * @param startM  开始月份 YYYYMM
 * @param endM    结束月份 YYYYMM
 */
suspend fun TuShareApi.getSocialFinanceMonthly(
    month: String? = null,
    startM: String? = null,
    endM: String? = null
) = query(
    tushareParams.ofApi("sf_month").carriesParam(
        mutableMapOf<String, String>().apply {
            month?.let { put("m", it) }
            startM?.let { put("start_m", it) }
            endM?.let { put("end_m", it) }
        }
    ).requireFields(
        listOf("month", "inc_month", "inc_cumval", "stk_endval")
    ).toRequest()
)

/**
 * 采购经理人指数 PMI（cn_pmi）—— MAC3 景气预期基础量。
 *
 * 积分：2000；单次最大 2000，一次可取全部。日期参数为月份 YYYYMM。
 * 输出含制造业/非制造业/综合共 30+ 细分指标，这里只取核心三项。
 *
 * @param month   单月 YYYYMM
 * @param startM  开始月份 YYYYMM
 * @param endM    结束月份 YYYYMM
 */
suspend fun TuShareApi.getPmi(
    month: String? = null,
    startM: String? = null,
    endM: String? = null
) = query(
    tushareParams.ofApi("cn_pmi").carriesParam(
        mutableMapOf<String, String>().apply {
            month?.let { put("m", it) }
            startM?.let { put("start_m", it) }
            endM?.let { put("end_m", it) }
        }
    ).requireFields(
        // pmi010000 制造业PMI / pmi020100 非制造业商务活动 / pmi030000 综合PMI产出
        listOf("month", "pmi010000", "pmi020100", "pmi030000")
    ).toRequest()
)

/**
 * Shibor 利率数据（shibor）—— MAC2 期限利差基础量。
 *
 * 积分：120（本族门槛最低接口）；单次最大 2000 条，按日期分段无总量限制。
 * 日期参数为交易日 YYYYMMDD。期限利差可由 1y − on（或 1y − 3m）派生。
 *
 * @param date       单日 YYYYMMDD
 * @param startDate  开始日 YYYYMMDD
 * @param endDate    结束日 YYYYMMDD
 */
suspend fun TuShareApi.getShibor(
    date: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("shibor").carriesParam(
        mutableMapOf<String, String>().apply {
            date?.let { put("date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("date", "on", "1w", "2w", "1m", "3m", "6m", "9m", "1y")
    ).toRequest()
)

/**
 * 国内生产总值 GDP（cn_gdp）—— 宏观景气辅助量。
 *
 * 积分：600；单次最大 10000。季度参数 YYYYQn（如 2024Q1）。
 *
 * @param quarter  单季 YYYYQn
 * @param startQ   开始季 YYYYQn
 * @param endQ     结束季 YYYYQn
 */
suspend fun TuShareApi.getGdp(
    quarter: String? = null,
    startQ: String? = null,
    endQ: String? = null
) = query(
    tushareParams.ofApi("cn_gdp").carriesParam(
        mutableMapOf<String, String>().apply {
            quarter?.let { put("q", it) }
            startQ?.let { put("start_q", it) }
            endQ?.let { put("end_q", it) }
        }
    ).requireFields(
        listOf("quarter", "gdp", "gdp_yoy")
    ).toRequest()
)

// ───────────────────────── 资金流（北向 / 个股）─────────────────────────

/**
 * 沪深港通资金流向（moneyflow_hsgt）—— MAC4 北向资金偏好基础量。
 *
 * 积分：2000（5000 积分每分钟 500 次）；单次最大 300 条 → 长区间需按日期循环。
 * trade_date 与 start_date/end_date 二选一，均为 YYYYMMDD。
 *
 * @param tradeDate  单日 YYYYMMDD（与 start/end 二选一）
 * @param startDate  开始日 YYYYMMDD
 * @param endDate    结束日 YYYYMMDD
 */
suspend fun TuShareApi.getHsgtMoneyFlow(
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("moneyflow_hsgt").carriesParam(
        mutableMapOf<String, String>().apply {
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf("trade_date", "ggt_ss", "ggt_sz", "hgt", "sgt", "north_money", "south_money")
    ).toRequest()
)

/**
 * 个股资金流向（moneyflow）—— IND4 行业资金净流入（按行业聚合个股）基础量。
 *
 * 积分：2000；单次最大 6000 行；股票或时间参数至少给一个。
 * 大单/特大单净流入是「主力抛压/托底」的直接代理。
 *
 * @param tsCode     股票代码（与时间参数至少给一个）
 * @param tradeDate  单日 YYYYMMDD
 * @param startDate  开始日 YYYYMMDD
 * @param endDate    结束日 YYYYMMDD
 */
suspend fun TuShareApi.getStockMoneyFlow(
    tsCode: String? = null,
    tradeDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi("moneyflow").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            tradeDate?.let { put("trade_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "trade_date",
            "buy_lg_amount", "sell_lg_amount",
            "buy_elg_amount", "sell_elg_amount",
            "net_mf_amount"
        )
    ).toRequest()
)

// ───────────────────────── 线 F · 个股基本面 ─────────────────────────
//  财务接口 useVip=true → *_vip（整季全市场，period 必填，5000 积分）
//                useVip=false → 普通版（单股票，ts_code 必填，2000 积分）
//  ⚠️ ann_date（公告日）是防未来函数的生死线：装配时必须以 ann_date 对齐 t−1，
//     而非 end_date（报告期）。本层只透传，对齐纪律由上层 Transform 负责。

/**
 * 财务指标（fina_indicator / fina_indicator_vip）—— FUN1/FUN2/FUN3/FUN5 主力基础量。
 *
 * 普通版单股票（单次 100 条）；VIP 版整季全市场（period 必填）。参数一致。
 *
 * @param useVip     true → fina_indicator_vip（整季全市场）
 * @param tsCode     股票代码（普通版必填）
 * @param period     报告期 YYYYMMDD（VIP 版必填，如 20231231 年报）
 * @param annDate    公告日 YYYYMMDD
 * @param startDate  公告日开始 YYYYMMDD
 * @param endDate    公告日结束 YYYYMMDD
 */
suspend fun TuShareApi.getFinaIndicator(
    useVip: Boolean = false,
    tsCode: String? = null,
    period: String? = null,
    annDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi(if (useVip) "fina_indicator_vip" else "fina_indicator").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            period?.let { put("period", it) }
            annDate?.let { put("ann_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "ann_date", "end_date",
            "eps", "roe", "roe_dt",
            "netprofit_yoy", "tr_yoy", "or_yoy",   // 净利/营收/营业收入 同比增长
            "ocf_to_sales", "ocfps",               // 经营现金流质量
            "dt_netprofit_yoy"
        )
    ).toRequest()
)

/**
 * 利润表（income / income_vip）—— FUN2 成长性（营收/净利）基础量。
 *
 * @param useVip     true → income_vip（整季全市场，period 必填）
 * @param tsCode     股票代码（普通版必填）
 * @param period     报告期 YYYYMMDD
 * @param annDate    公告日 YYYYMMDD
 * @param startDate  公告日开始 YYYYMMDD
 * @param endDate    公告日结束 YYYYMMDD
 */
suspend fun TuShareApi.getIncome(
    useVip: Boolean = false,
    tsCode: String? = null,
    period: String? = null,
    annDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi(if (useVip) "income_vip" else "income").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            period?.let { put("period", it) }
            annDate?.let { put("ann_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "ann_date", "end_date",
            "total_revenue", "revenue",
            "operate_profit", "total_profit", "n_income"
        )
    ).toRequest()
)

/**
 * 现金流量表（cashflow / cashflow_vip）—— FUN3 经营现金流覆盖基础量。
 *
 * n_cashflow_act 经营活动现金流净额是判断「真红利」（非股息陷阱）的关键。
 *
 * @param useVip     true → cashflow_vip（整季全市场，period 必填）
 * @param tsCode     股票代码（普通版必填）
 * @param period     报告期 YYYYMMDD
 * @param annDate    公告日 YYYYMMDD
 * @param startDate  公告日开始 YYYYMMDD
 * @param endDate    公告日结束 YYYYMMDD
 */
suspend fun TuShareApi.getCashflow(
    useVip: Boolean = false,
    tsCode: String? = null,
    period: String? = null,
    annDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi(if (useVip) "cashflow_vip" else "cashflow").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            period?.let { put("period", it) }
            annDate?.let { put("ann_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "ann_date", "end_date",
            "n_cashflow_act", "n_cashflow_inv_act", "n_cash_flows_fnc_act",
            "free_cashflow"
        )
    ).toRequest()
)

/**
 * 资产负债表（balancesheet / balancesheet_vip）—— FUN 杠杆/质地基础量。
 *
 * 净负债率 = (total_liab) / total_hldr_eqy_inc_min_int 之类由上层派生。
 *
 * @param useVip     true → balancesheet_vip（整季全市场，period 必填）
 * @param tsCode     股票代码（普通版必填）
 * @param period     报告期 YYYYMMDD
 * @param annDate    公告日 YYYYMMDD
 * @param startDate  公告日开始 YYYYMMDD
 * @param endDate    公告日结束 YYYYMMDD
 */
suspend fun TuShareApi.getBalanceSheet(
    useVip: Boolean = false,
    tsCode: String? = null,
    period: String? = null,
    annDate: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi(if (useVip) "balancesheet_vip" else "balancesheet").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            period?.let { put("period", it) }
            annDate?.let { put("ann_date", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "ann_date", "end_date",
            "total_assets", "total_liab", "total_hldr_eqy_inc_min_int"
        )
    ).toRequest()
)

/**
 * 业绩预告（forecast / forecast_vip）—— FUN1 盈利拐点（上修/下修）基础量。
 *
 * type 预告类型 + p_change_min/max 净利变动幅度，是盈利方向最早的领先信号。
 *
 * @param useVip     true → forecast_vip（整季全市场，period 必填）
 * @param tsCode     股票代码（与 annDate 二选一）
 * @param annDate    公告日 YYYYMMDD（与 tsCode 二选一）
 * @param period     报告期 YYYYMMDD
 * @param startDate  公告日开始 YYYYMMDD
 * @param endDate    公告日结束 YYYYMMDD
 */
suspend fun TuShareApi.getForecast(
    useVip: Boolean = false,
    tsCode: String? = null,
    annDate: String? = null,
    period: String? = null,
    startDate: String? = null,
    endDate: String? = null
) = query(
    tushareParams.ofApi(if (useVip) "forecast_vip" else "forecast").carriesParam(
        mutableMapOf<String, String>().apply {
            tsCode?.let { put("ts_code", it) }
            annDate?.let { put("ann_date", it) }
            period?.let { put("period", it) }
            startDate?.let { put("start_date", it) }
            endDate?.let { put("end_date", it) }
        }
    ).requireFields(
        listOf(
            "ts_code", "ann_date", "end_date",
            "type", "p_change_min", "p_change_max",
            "net_profit_min", "net_profit_max"
        )
    ).toRequest()
)

val tushareParams: TushareParams
    get() = TushareParams()

class TushareParams {

    private var def: Def = Def()

    class Def {
        lateinit var apiName: String
        var params: Map<String, String> = emptyMap()
        var fields: List<String> = emptyList()
    }

    fun ofApi(apiName: String): TushareParams {
        def.apiName = apiName
        return this
    }

    fun carriesParam(params: Map<String, String>): TushareParams {
        def.params = params
        return this
    }

    fun requireFields(fields: List<String>): TushareParams {
        def.fields = fields
        return this
    }

    fun toRequest(): TushareRequest {
        val token = ConfigHolder.config.externalApis.tushareToken
        return TushareRequest(
            apiName = def.apiName,
            token = token,
            params = def.params,
            fields = def.fields
        )
    }
}

@Serializable
data class BaseTushare(
    @SerialName("request_id")
    val requestId: String,
    val code: String,
    val msg: String,
    private val data: TushareForm? = null
) {

    suspend fun check() = suspendCancellableCoroutine { c ->
        if (code != "0") {
            c.resumeWithException(Exception("request failed. code: $code, msg: $msg"))
            return@suspendCancellableCoroutine
        }
        c.resume(data)
    }
}

@Serializable
data class TushareForm(
    val fields: List<String>,
    val items: List<List<String?>>
) {
    fun toColumns(sortKey: String? = null): List<Column> {
        val sorted = sortKey?.let { key ->
            var keyIndex = fields.indexOf(key)
            
            // Handle common aliases for sorting
            if (keyIndex == -1 && (key == "trade_time" || key == "time")) {
                keyIndex = fields.indexOf(if (key == "trade_time") "time" else "trade_time")
            }

            if (keyIndex != -1) {
                items.sortedBy { item: List<String?> -> item[keyIndex] }
            } else {
                items
            }
        } ?: items
        return sorted.map { Column(fields = fields, items = it) }
    }
}

data class Column(
    val fields: List<String>,
    val items: List<String?>
) {

    infix fun provides(key: String): String {
        val index = fields.indexOf(key)
        if (index != -1 && index < items.size) {
            return items[index] ?: ""
        }

        // Handle common aliases for data access
        if (key == "trade_time" || key == "time") {
            val altKey = if (key == "trade_time") "time" else "trade_time"
            val altIndex = fields.indexOf(altKey)
            if (altIndex != -1 && altIndex < items.size) {
                return items[altIndex] ?: ""
            }
        }

        return ""
    }
}
