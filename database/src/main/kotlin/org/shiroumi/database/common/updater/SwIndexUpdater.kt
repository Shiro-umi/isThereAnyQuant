package org.shiroumi.database.common.updater

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.jdbc.batchReplace
import org.shiroumi.database.*
import org.shiroumi.database.common.table.SwIndexMemberTable
import org.shiroumi.database.common.table.SwIndexTable
import org.shiroumi.database.sw_index.updater.fetchSwIndexDailyCandle
import org.shiroumi.network.apis.TushareForm
import org.shiroumi.network.apis.getSwIndexClassify
import org.shiroumi.network.apis.getSwIndexMember
import org.shiroumi.network.apis.tushare
import utils.ScheduledTasks
import utils.logger


private val logger by logger("SwIndexUpdater")

suspend fun updateSwIndex() = runCatching {
    logger.info("start update sw index classification..")
    val classify = fetchIndexClassify() ?: throw Exception("failed to fetch sw index classification")
    logger.accept("sw index classification updated successfully!")
    classify.items.forEach { (indexCode, _, level, _) ->
        if (level != "L1") fetchIndexMember("$indexCode") // 按L1更新所有行业（单次2000条限制）
    }
    val scheduledTasks = ScheduledTasks<Unit>(frequency = 200)
    classify.items.forEach { (indexCode, _, _, _) ->
        scheduledTasks.emit(tag = "$indexCode") {
            fetchSwIndexDailyCandle(tsCode = "$indexCode")
        }
    }
    scheduledTasks.schedule().collect { (tag, _) -> logger.info("tag [$tag] done.") }
}.onFailure { t ->
    t.printStackTrace()
}


/**
 *
 * @see <a href="https://tushare.pro/document/2?doc_id=181">申万行业分类</a>
 *
 * 数据字段说明：<table><thead><tr><th>名称</th><th>类型</th><th>默认显示</th><th>描述</th></tr></thead><tbody><tr><td>index_code</td><td>str</td><td>Y</td><td>指数代码</td></tr><tr><td>industry_name</td><td>str</td><td>Y</td><td>行业名称</td></tr><tr><td>parent_code</td><td>str</td><td>Y</td><td>父级代码</td></tr><tr><td>level</td><td>str</td><td>Y</td><td>行业层级</td></tr><tr><td>industry_code</td><td>str</td><td>Y</td><td>行业代码</td></tr><tr><td>is_pub</td><td>str</td><td>Y</td><td>是否发布了指数</td></tr><tr><td>src</td><td>str</td><td>N</td><td>行业分类（SW申万）</td></tr></tbody></table>
 */
private suspend fun fetchIndexClassify(): TushareForm? = withContext(Dispatchers.IO) {
    val res = tushare.getSwIndexClassify().check() ?: return@withContext null
    commonDb.transaction(SwIndexTable, log = false) {
        SwIndexTable.batchReplace(res.items) { (indexCode, industryName, level, industryCode) ->
            this[SwIndexTable.indexCode] = "$indexCode"
            this[SwIndexTable.industryName] = "$industryName"
            this[SwIndexTable.level] = "$level"
            this[SwIndexTable.industryCode] = "$industryCode"
        }
    }
    return@withContext res
}

/**
 * @see <a href="https://tushare.pro/document/2?doc_id=335">申万行业成分构成(分级)</a>
 *
 * <table><thead><tr><th>名称</th><th>类型</th><th>默认显示</th><th>描述</th></tr></thead><tbody><tr><td>l1_code</td><td>str</td><td>Y</td><td>一级行业代码</td></tr><tr><td>l1_name</td><td>str</td><td>Y</td><td>一级行业名称</td></tr><tr><td>l2_code</td><td>str</td><td>Y</td><td>二级行业代码</td></tr><tr><td>l2_name</td><td>str</td><td>Y</td><td>二级行业名称</td></tr><tr><td>l3_code</td><td>str</td><td>Y</td><td>三级行业代码</td></tr><tr><td>l3_name</td><td>str</td><td>Y</td><td>三级行业名称</td></tr><tr><td>ts_code</td><td>str</td><td>Y</td><td>成分股票代码</td></tr><tr><td>name</td><td>str</td><td>Y</td><td>成分股票名称</td></tr><tr><td>in_date</td><td>str</td><td>Y</td><td>纳入日期</td></tr><tr><td>out_date</td><td>str</td><td>Y</td><td>剔除日期</td></tr><tr><td>is_new</td><td>str</td><td>Y</td><td>是否最新Y是N否</td></tr></tbody></table>
 */
private suspend fun fetchIndexMember(indexCode: String) = withContext(Dispatchers.IO) {
    logger.info("fetch sw index member of $indexCode")
    val res = tushare.getSwIndexMember(l1Code = indexCode).check() ?: return@withContext
    commonDb.transaction(SwIndexMemberTable, log = false) {
        SwIndexMemberTable.batchReplace(res.items) { (l1Code, l1Name, l2Code, l2Name, l3Code, l3Name, tsCode, name) ->
            this[SwIndexMemberTable.l1Code] = "$l1Code"
            this[SwIndexMemberTable.l1Name] = "$l1Name"
            this[SwIndexMemberTable.l2Code] = "$l2Code"
            this[SwIndexMemberTable.l2Name] = "$l2Name"
            this[SwIndexMemberTable.l3Code] = "$l3Code"
            this[SwIndexMemberTable.l3Name] = "$l3Name"
            this[SwIndexMemberTable.tsCode] = "$tsCode"
            this[SwIndexMemberTable.name] = "$name"
        }
    }
    logger.accept("fetch sw index member of $indexCode done.")
}

