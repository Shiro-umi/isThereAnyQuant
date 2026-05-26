package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 品牌 Logo（ImageVector 实现，颜色完全跟随主题 token）。
 *
 * 两条 path：
 * - 后景三根递增竖线使用 [backColor]（默认主题 primary）
 * - 上行折线箭头使用 [frontColor]（默认 primaryContainer）
 *
 * 改动 viewBox 或路径数据时，请同时同步 `composeResources/drawable/brand_mark.svg`
 * （该资源仅用于浏览器 favicon / Android adaptive icon 等静态场景）。
 */
@Composable
fun BrandLogo(
    modifier: Modifier = Modifier,
    backColor: Color = MaterialTheme.colorScheme.primary,
    frontColor: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    val image = remember(backColor, frontColor) { buildBrandMark(backColor, frontColor) }
    Image(
        imageVector = image,
        contentDescription = "Quant Logo",
        modifier = modifier,
    )
}

/** NavigationRail / TopBar 用的固定尺寸品牌入口。 */
@Composable
fun BrandLogoCompact(
    size: Dp = 40.dp,
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        BrandLogo(modifier = Modifier.size(size * 0.75f))
    }
}

private fun buildBrandMark(backColor: Color, frontColor: Color): ImageVector {
    val viewport = 236f
    return ImageVector.Builder(
        name = "BrandMark",
        defaultWidth = 40.dp,
        defaultHeight = 40.dp,
        viewportWidth = viewport,
        viewportHeight = viewport,
    ).apply {
        // 后景：三根递增竖线（坐标平移 viewBox "138 160 236 236" → 内部 0..236）
        path(
            fill = null,
            stroke = SolidColor(backColor),
            strokeLineWidth = 24f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(52f, 172f); verticalLineToRelative(24f)
            moveTo(104f, 134f); verticalLineToRelative(62f)
            moveTo(156f, 96f); verticalLineToRelative(100f)
        }
        // 前景：折线箭头
        path(
            fill = null,
            stroke = SolidColor(frontColor),
            strokeLineWidth = 24f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathFillType = PathFillType.NonZero,
        ) {
            moveTo(33f, 153f)
            lineTo(90f, 96f)
            lineTo(127f, 115f)
            lineTo(203f, 39f)
            moveTo(203f, 39f); verticalLineTo(105f)
            moveTo(203f, 39f); horizontalLineTo(137f)
        }
    }.build()
}
