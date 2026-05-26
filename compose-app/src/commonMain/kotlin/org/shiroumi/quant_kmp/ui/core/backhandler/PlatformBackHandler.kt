package org.shiroumi.quant_kmp.ui.core.backhandler

import androidx.compose.runtime.Composable

/**
 * 平台特定的返回手势拦截器。
 *
 * - Android：使用系统 BackHandler 拦截返回手势
 * - JS / JVM：无系统返回手势，为空实现
 */
@Composable
expect fun PlatformBackHandler(enabled: Boolean = true, onBack: () -> Unit)
