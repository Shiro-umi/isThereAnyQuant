package org.shiroumi.server.dto

import kotlinx.serialization.Serializable

/**
 * 分页信息DTO
 * 用于列表查询响应的分页元数据
 */
@Serializable
data class PaginationInfo(
    val page: Int,
    val pageSize: Int,
    val total: Int,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean
) {
    companion object {
        /**
         * 创建分页信息
         *
         * @param page 当前页码（从1开始）
         * @param pageSize 每页大小
         * @param total 总记录数
         * @return PaginationInfo
         */
        fun create(
            page: Int,
            pageSize: Int,
            total: Int
        ): PaginationInfo {
            val totalPages = if (total == 0) 1 else (total + pageSize - 1) / pageSize
            return PaginationInfo(
                page = page,
                pageSize = pageSize,
                total = total,
                totalPages = totalPages,
                hasNext = page < totalPages,
                hasPrevious = page > 1
            )
        }
    }
}

/**
 * 分页查询基础请求
 * 可作为其他分页请求DTO的基类
 */
@Serializable
open class PaginationRequest(
    open val page: Int = 1,
    open val pageSize: Int = 20
) {
    init {
        require(page >= 1) { "Page must be >= 1" }
        require(pageSize in 1..100) { "PageSize must be between 1 and 100" }
    }

    /**
     * 获取数据库查询用的offset
     */
    fun getOffset(): Int = (page - 1) * pageSize

    /**
     * 获取数据库查询用的limit
     */
    fun getLimit(): Int = pageSize
}
