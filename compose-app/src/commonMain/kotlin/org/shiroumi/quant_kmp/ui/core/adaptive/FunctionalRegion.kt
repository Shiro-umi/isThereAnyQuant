package org.shiroumi.quant_kmp.ui.core.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.AdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig

/**
 * 功能区容器：按断点决定「卡片 vs 无卡」，是全前端唯一的功能区拆分容器。
 *
 * - 宽屏（[AdaptiveLayoutConfig.useFunctionalCards] = true）：用 [Surface] 大卡片拆分功能区。
 * - 手机（Compact）：退化为纯内容 + Material3 标准内边距，不画卡片背景，把空间留给内容。
 *
 * 常规功能区禁止再自行写死外层 `Card` / `Surface(surfaceContainerLow)`，统一走这里，
 * 「卡 vs 无卡」决策收敛单点，与 [AdaptiveLayoutConfig.pagePadding] / `headerToContentGap`
 * 收敛散落布局逻辑的设计意图一致。
 *
 * 唯一例外是 K 线详情页的沉浸式图表区与可形变摘要抽屉：它们需要折叠/展开两态的
 * shadowElevation/tonalElevation 对比、透明↔不透明背景切换，以及 sharedBounds 形变，
 * 这些是 [Surface] 的专有能力，纳入本容器会让其臃肿。该页保留自有的 `if (isCompact)`
 * 卡 vs 无卡分支，不收编进 FunctionalRegion。
 *
 * @param cardPadding 卡片态内边距（宽屏）。
 * @param compactPadding 手机态内边距，默认 0；功能区间距/分隔线由父级统一编排。
 */
@Composable
fun FunctionalRegion(
    modifier: Modifier = Modifier,
    config: AdaptiveLayoutConfig = rememberAdaptiveLayoutConfig(),
    shape: Shape = MaterialTheme.shapes.large,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    cardPadding: PaddingValues = PaddingValues(24.dp),
    compactPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    if (config.useFunctionalCards) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
        ) {
            Box(modifier = Modifier.padding(cardPadding)) {
                content()
            }
        }
    } else {
        Box(modifier = modifier.fillMaxWidth().padding(compactPadding)) {
            content()
        }
    }
}
