package org.shiroumi.quant_kmp.ui.theme

import androidx.compose.material3.ColorScheme

/**
 * 把当前生效的 [ColorScheme] 关键 token 同步到平台的"首屏 chrome"——也就是 Compose 渲染开始前由平台自身展示的那一层。
 *
 * - Web (wasmJs)：把 token 集合写入 localStorage 轻量缓存，并更新 `<meta name="theme-color">`。
 *   下次刷新时，`index.html` 的内联脚本读到缓存值，把所有 loading 用色注入 CSS 变量，
 *   让首屏 loading 一开始就命中用户的主题，避免"先暖棕红几秒再切换"的视觉割裂。
 * - 其他平台：no-op（没有等价的首屏 chrome 层）。
 */
expect fun syncLoadingChrome(colorScheme: ColorScheme)
