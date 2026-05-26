package model.auth

import kotlinx.serialization.Serializable

/**
 * 用户角色枚举
 * 用于权限控制
 */
@Serializable
enum class UserRole {
    /** 管理员 - 拥有所有权限 */
    ADMIN,
    
    /** 交易员 - 可以执行交易操作 */
    TRADER,
    
    /** 分析师 - 可以查看分析结果和策略 */
    ANALYST,
    
    /** 观察者 - 只读权限 */
    VIEWER
}
