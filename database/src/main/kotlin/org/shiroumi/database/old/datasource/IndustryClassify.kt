//package org.shiroumi.database.old.datasource
//
//import kotlinx.coroutines.CoroutineExceptionHandler
//import kotlinx.coroutines.coroutineScope
//import kotlinx.coroutines.launch
//import org.jetbrains.exposed.v1.jdbc.batchUpsert
//import org.shiroumi.database.old.stockDb
//import org.shiroumi.database.old.table.IndustryClassifyTable
//import org.shiroumi.database.old.transaction
//import org.shiroumi.network.apis.getIndexClassify
//import org.shiroumi.network.apis.tushare
//import utils.logger
//
//suspend fun updateIndustryClassify() = coroutineScope {
//    val logger by logger("IndustryClassify")
//    launch(CoroutineExceptionHandler { _, t ->
//        logger.error("Failed to update industry classification: \${t.message}")
//        t.printStackTrace()
//    }) {
//        logger.info("Start updating Shenwan industry classification...")
//
//        val sources = listOf("SW2014", "SW2021")
//        val levels = listOf("L1", "L2", "L3")
//
//        sources.forEach { src ->
//            levels.forEach { level ->
//                logger.info("Fetching level '$level' for source '$src'...")
//                val industryForm = tushare.getIndexClassify(level = level, src = src).check()
//
//                if (industryForm == null) {
//                    logger.warning("No data returned from API for level '$level', source '$src'.")
//                    return@forEach
//                }
//
//                val industries = industryForm.toColumns()
//                if (industries.isEmpty()) {
//                    logger.info("No new data to update for level '$level', source '$src'.")
//                    return@forEach
//                }
//
//                logger.info("Received \${industries.size} items for level '$level', source '$src'. Saving to database...")
//
//                stockDb.transaction(IndustryClassifyTable, log = false) {
//                    IndustryClassifyTable.batchUpsert(industries) { industry ->
//                        set(IndustryClassifyTable.indexCode, industry provides "index_code")
//                        set(IndustryClassifyTable.industryName, industry provides "industry_name")
//                        set(IndustryClassifyTable.parentCode, industry provides "parent_code")
//                        set(IndustryClassifyTable.level, industry provides "level")
//                        set(IndustryClassifyTable.industryCode, industry provides "industry_code")
//                        set(IndustryClassifyTable.isPub, industry provides "is_pub")
//                        set(IndustryClassifyTable.src, industry provides "src")
//                    }
//                }
//                logger.info("Successfully saved \${industries.size} items for level '$level', source '$src'.")
//            }
//        }
//        logger.info("Finished updating Shenwan industry classification.")
//    }
//}
