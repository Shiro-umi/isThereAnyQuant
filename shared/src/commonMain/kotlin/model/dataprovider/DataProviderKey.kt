package model.dataprovider

import kotlinx.serialization.Serializable

/**
 * DataProvider 的强类型主键接口。
 *
 * 设计目的：
 * 1. 用领域语义标识一个具体 Provider，而不是使用松散的字符串地址。
 * 2. 让上层调用在编译期知道自己操作的是哪一类 Provider。
 * 3. 同时保留 `id` 供基础设施层使用，例如：
 *    - SnapshotStore 的内部索引
 *    - Registry 的查找键
 *    - 后续订阅投影层的 topic 派生
 *
 * 注意：
 * - `id` 是基础设施键，不代表前端直接暴露协议。
 * - 业务层优先传递具体 Key 类型，而不是裸字符串。
 */
@Serializable
sealed interface DataProviderKey {
    val id: String
}
