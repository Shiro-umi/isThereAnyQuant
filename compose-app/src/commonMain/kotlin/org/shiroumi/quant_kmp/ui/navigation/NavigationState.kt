package org.shiroumi.quant_kmp.ui.navigation

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.*
import androidx.savedstate.serialization.SavedStateConfiguration
import kotlinx.serialization.PolymorphicSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import org.shiroumi.quant_kmp.NavDest

/**
 * Navigation 3 多态序列化配置
 */
// ⚠️ 新增 NavDest 子类必须在此注册
private val navSerializersModule = SerializersModule {
    polymorphic(NavKey::class) {
        subclass(NavDest.Candle::class, NavDest.Candle.serializer())
        subclass(NavDest.Sentiment::class, NavDest.Sentiment.serializer())
        subclass(NavDest.PositionTracking::class, NavDest.PositionTracking.serializer())
        subclass(NavDest.AgentResults::class, NavDest.AgentResults.serializer())
        subclass(NavDest.Settings::class, NavDest.Settings.serializer())
    }
}

private val navJson = Json { serializersModule = navSerializersModule }

internal fun encodeNavDest(dest: NavDest): String =
    navJson.encodeToString(PolymorphicSerializer(NavKey::class), dest)

internal fun decodeNavDest(value: String): NavDest =
    navJson.decodeFromString(PolymorphicSerializer(NavKey::class), value) as NavDest

private val navDestSaver = Saver<NavDest, String>(
    save = { encodeNavDest(it) },
    restore = { runCatching { decodeNavDest(it) }.getOrNull() }
)

/**
 * Navigation 3 保存状态配置
 * 使用自定义序列化模块处理 NavKey 多态
 */
private val navConfig: SavedStateConfiguration = SavedStateConfiguration {
    serializersModule = navSerializersModule
}

/**
 * 创建导航状态，在配置更改和进程死亡时持久保存
 */
@Composable
fun rememberNavigationState(
    startRoute: NavDest,
    topLevelRoutes: Set<NavDest>
): NavigationState {
    // 使用 rememberSaveable 保存当前顶级路由
    var topLevelRouteState by rememberSaveable(stateSaver = navDestSaver) { mutableStateOf(startRoute) }

    // 为每个顶级路由创建回退栈
    val backStacks = topLevelRoutes.associateWith { key ->
        rememberNavBackStack(navConfig, key)
    }

    return remember(startRoute, topLevelRoutes) {
        NavigationState(
            startRoute = startRoute,
            topLevelRoute = topLevelRouteState,
            backStacks = backStacks,
            onTopLevelRouteChange = { topLevelRouteState = it }
        )
    }
}

/**
 * 导航状态持有者
 *
 * @param startRoute 起始路由，用户通过此路由退出应用
 * @param topLevelRoute 当前顶级路由
 * @param backStacks 每个顶级路由的回退栈
 * @param onTopLevelRouteChange 顶级路由变化回调
 */
class NavigationState(
    val startRoute: NavDest,
    topLevelRoute: NavDest,
    val backStacks: Map<NavDest, NavBackStack<NavKey>>,
    private val onTopLevelRouteChange: (NavDest) -> Unit
) {
    var topLevelRoute: NavDest
        get() = mutableTopLevelRoute
        internal set(value) {
            mutableTopLevelRoute = value
            onTopLevelRouteChange(value)
        }

    private var mutableTopLevelRoute: NavDest by mutableStateOf(topLevelRoute)

    /**
     * 临时返回消费者（如 supporting pane）。LIFO 顺序消费，先于栈 pop。
     */
    private val transientBackConsumers = mutableStateListOf<TransientBackConsumer>()

    /**
     * 获取正在使用的栈列表
     */
    val stacksInUse: List<NavDest>
        get() = if (topLevelRoute == startRoute) {
            listOf(startRoute)
        } else {
            listOf(startRoute, topLevelRoute)
        }

    /**
     * 获取当前栈
     */
    val currentStack: NavBackStack<NavKey>?
        get() = backStacks[topLevelRoute]

    /**
     * 获取当前路由
     */
    val currentRoute: NavKey?
        get() = currentStack?.lastOrNull()

    /**
     * 当前是否处于二级页面（栈深度 > 1），即应显示返回按钮。
     */
    val canGoBack: Boolean
        get() {
            val stack = currentStack ?: return false
            return stack.lastOrNull() != topLevelRoute
        }

    /**
     * 当前打开的临时返回消费者数量（如 supporting pane 已展开）。
     */
    val transientBackDepth: Int
        get() = transientBackConsumers.count { it.isOpen() }

    /**
     * 当前顶级栈上可被浏览器历史消费的回退深度。
     * 已打开的临时消费者（如 pane）也计入深度，浏览器 Back 会优先关闭它们。
     */
    val browserBackDepth: Int
        get() {
            val stackDepth = (currentStack?.size ?: 1) - 1
            val topLevelDepth = if (topLevelRoute != startRoute) 1 else 0
            return stackDepth.coerceAtLeast(0) + topLevelDepth + transientBackDepth
        }

    internal fun consumeTransientBack(): Boolean {
        for (i in transientBackConsumers.indices.reversed()) {
            val consumer = transientBackConsumers[i]
            if (consumer.isOpen()) {
                consumer.onConsume()
                return true
            }
        }
        return false
    }

    internal fun registerTransientBackConsumer(consumer: TransientBackConsumer) {
        transientBackConsumers.add(consumer)
    }

    internal fun unregisterTransientBackConsumer(consumer: TransientBackConsumer) {
        transientBackConsumers.remove(consumer)
    }
}

/**
 * 临时返回消费者契约。
 *
 * `isOpen` 返回当前是否处于"已打开"状态；`onConsume` 触发关闭。
 */
internal class TransientBackConsumer(
    val isOpen: () -> Boolean,
    val onConsume: () -> Unit,
)

/**
 * 将 NavigationState 转换为 NavEntries
 */
@Composable
fun NavigationState.toEntries(
    entryProvider: (NavKey) -> NavEntry<NavKey>
): SnapshotStateList<NavEntry<NavKey>> {
    val decoratedEntries = backStacks.mapValues { (_, stack) ->
        val decorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<NavKey>(),
        )
        rememberDecoratedNavEntries(
            backStack = stack,
            entryDecorators = decorators,
            entryProvider = entryProvider
        )
    }
    return stacksInUse
        .flatMap { decoratedEntries[it] ?: emptyList() }
        .toMutableStateList()
}
