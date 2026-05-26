@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import kotlinx.browser.localStorage
import org.w3c.dom.set
import kotlin.JsFun

/**
 * 与 `index.html` 内联脚本约定的 localStorage key。
 * 值是一个 JSON 对象，字段名与 CSS 变量名（去掉 `--loading-` 前缀）一一对应。
 */
private const val LOADING_CHROME_KEY = "quant.theme.loading-chrome"

@JsFun(
    """(json, bgHex) => {
        var root = document.documentElement;
        try {
            var tokens = JSON.parse(json);
            for (var k in tokens) {
                if (Object.prototype.hasOwnProperty.call(tokens, k)) {
                    root.style.setProperty('--loading-' + k, tokens[k]);
                }
            }
        } catch (e) {}
        // 同步所有 theme-color meta：无 media 的"权威条目" + 暗/亮 media 变体
        // Chrome Android PWA standalone 模式下，状态栏颜色由无 media 的 meta 控制
        var metas = document.querySelectorAll('meta[name=\"theme-color\"]');
        for (var i = 0; i < metas.length; i++) metas[i].setAttribute('content', bgHex);
        // 部分 Chrome 版本在 PWA 模式下不会响应 setAttribute，需要"移除→重插"强制重新计算
        var head = document.head;
        var bare = null;
        for (var j = 0; j < metas.length; j++) {
            if (!metas[j].getAttribute('media')) { bare = metas[j]; break; }
        }
        if (bare && head) {
            var fresh = document.createElement('meta');
            fresh.setAttribute('name', 'theme-color');
            fresh.setAttribute('content', bgHex);
            head.replaceChild(fresh, bare);
        }
        var tile = document.querySelector('meta[name=\"msapplication-TileColor\"]');
        if (tile) tile.setAttribute('content', bgHex);
    }"""
)
private external fun applyLoadingChrome(json: String, bgHex: String)

actual fun syncLoadingChrome(colorScheme: ColorScheme) {
    val tokens = colorScheme.toLoadingTokens()
    val json = tokens.toJson()
    runCatching { localStorage[LOADING_CHROME_KEY] = json }
    runCatching { applyLoadingChrome(json, tokens["bg"]!!) }
}

/** 把 ColorScheme 中 loading 层实际用到的字段抽出为 hex 字符串映射。 */
private fun ColorScheme.toLoadingTokens(): Map<String, String> = linkedMapOf(
    "bg" to background.toCssHex(),
    "on-bg" to onBackground.toCssHex(),
    "on-bg-variant" to onSurfaceVariant.toCssHex(),
    "primary" to primary.toCssHex(),
    "on-primary" to onPrimary.toCssHex(),
    "secondary" to secondary.toCssHex(),
    "tertiary" to tertiary.toCssHex(),
    "surface-container" to surfaceContainer.toCssHex(),
    "surface-container-high" to surfaceContainerHigh.toCssHex(),
    "surface-container-highest" to surfaceContainerHighest.toCssHex(),
    "surface-container-lowest" to surfaceContainerLowest.toCssHex(),
    "outline" to outline.toCssHex(),
    "outline-variant" to outlineVariant.toCssHex(),
    "error" to error.toCssHex(),
)

private fun Map<String, String>.toJson(): String {
    val sb = StringBuilder("{")
    var first = true
    for ((k, v) in this) {
        if (!first) sb.append(',')
        first = false
        sb.append('"').append(k).append("\":\"").append(v).append('"')
    }
    sb.append('}')
    return sb.toString()
}

private fun Color.toCssHex(): String {
    val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255)
    val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    val hex = ((r shl 16) or (g shl 8) or b).toString(16).padStart(6, '0')
    return "#$hex"
}
