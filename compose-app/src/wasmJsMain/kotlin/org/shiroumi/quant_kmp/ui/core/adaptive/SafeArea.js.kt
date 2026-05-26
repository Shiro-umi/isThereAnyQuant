package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLElement
import org.w3c.dom.events.Event

/**
 * Web平台实现：获取安全区域内边距
 * 
 * 通过 CSS env() 函数获取安全区域值
 * 监听 resize 事件实时更新
 */
@Composable
actual fun rememberSafeAreaInsets(): SafeAreaInsets {
    val insetsState = rememberSafeAreaInsetsState()
    return insetsState.value
}

/**
 * 记住安全区域内边距状态
 */
@Composable
private fun rememberSafeAreaInsetsState(): State<SafeAreaInsets> {
    val insetsState = remember {
        mutableStateOf(calculateSafeAreaInsets())
    }
    
    remember {
        val resizeListener: (Event) -> Unit = {
            insetsState.value = calculateSafeAreaInsets()
        }
        window.addEventListener("resize", resizeListener)
        
        object : Any() {
            fun dispose() {
                window.removeEventListener("resize", resizeListener)
            }
        }
    }
    
    return insetsState
}

/**
 * 计算安全区域内边距
 * 
 * 统一使用 CSS env() 值，由 Compose 内部处理内容避让
 */
private fun calculateSafeAreaInsets(): SafeAreaInsets {
    // 统一使用 CSS env() 值
    return try {
        val div = document.createElement("div") as HTMLElement
        div.style.paddingTop = "env(safe-area-inset-top)"
        div.style.paddingRight = "env(safe-area-inset-right)"
        div.style.paddingBottom = "env(safe-area-inset-bottom)"
        div.style.paddingLeft = "env(safe-area-inset-left)"
        document.body?.appendChild(div)

        val style = window.getComputedStyle(div)
        val result = SafeAreaInsets(
            top = parseSafeAreaValue(style.paddingTop),
            right = parseSafeAreaValue(style.paddingRight),
            bottom = parseSafeAreaValue(style.paddingBottom),
            left = parseSafeAreaValue(style.paddingLeft)
        )
        document.body?.removeChild(div)
        
        result
    } catch (e: Exception) {
        val userAgent = window.navigator.userAgent
        val isLikelyIPhone = userAgent.contains("iPhone") ||
            userAgent.contains("iPad") ||
            userAgent.contains("iPod")
        if (isLikelyIPhone) {
            SafeAreaInsets(top = 59f, bottom = 34f)
        } else {
            SafeAreaInsets()
        }
    }
}

/**
 * 解析安全区域值
 * 从 CSS 值（如 "59px"）中提取数字
 */
private fun parseSafeAreaValue(value: String?): Float {
    if (value.isNullOrBlank() || value == "0px") return 0f
    
    return try {
        value.replace("px", "").toFloat()
    } catch (e: Exception) {
        0f
    }
}

/**
 * 通过 JavaScript 直接获取安全区域值
 * 备用方案：如果 CSS 变量方法失败，使用 window 对象的方法
 */
@Suppress("UNUSED")
private fun getSafeAreaInsetsFromJS(): SafeAreaInsets {
    return calculateSafeAreaInsets()
}
