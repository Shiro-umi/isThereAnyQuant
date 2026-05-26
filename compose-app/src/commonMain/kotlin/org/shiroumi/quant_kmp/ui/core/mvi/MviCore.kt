package org.shiroumi.quant_kmp.ui.core.mvi

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * MVI架构核心接口
 * 
 * Compose Multiplatform最佳实践：
 * 1. 单一数据源：所有UI状态通过StateFlow暴露
 * 2. 单向数据流：UI -> Action -> ViewModel -> State -> UI
 * 3. 副作用处理：通过Effect处理导航、Toast等一次性事件
 */

/**
 * UI状态接口标记
 */
interface UiState {
    /**
     * 是否正在加载
     */
    val isLoading: Boolean
    
    /**
     * 错误信息
     */
    val errorMessage: String?
}

/**
 * 基础UI状态实现
 */
abstract class BaseUiState(
    override val isLoading: Boolean = false,
    override val errorMessage: String? = null
) : UiState

/**
 * 用户动作接口标记
 */
interface UiAction

/**
 * 副作用（一次性事件）接口标记
 * 用于导航、显示Toast、打开对话框等
 */
interface UiEffect

/**
 * MVI ViewModel接口
 */
interface MviViewModel<S : UiState, A : UiAction, E : UiEffect> {
    /**
     * UI状态流
     */
    val state: StateFlow<S>
    
    /**
     * 副作用流
     */
    val effect: Flow<E>
    
    /**
     * 处理用户动作
     */
    fun dispatch(action: A)
}

/**
 * 副作用处理器
 * 在UI层收集并处理副作用
 */
interface EffectHandler<E : UiEffect> {
    suspend fun handle(effect: E)
}
