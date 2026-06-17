package org.shiroumi.quant_kmp.ui.components.qr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.shiroumi.quant_kmp.platform.copyToClipboard

/**
 * 客户端下载二维码对话框。
 *
 * 用纯 Kotlin 自绘 QR 编码器 [QrCode] 把 [url] 编码为模块矩阵，用 Compose Canvas 画黑白格，
 * 中央叠加夸克 logo（主题 primary 色 + 留白挖空，避免遮挡破坏扫码），二维码下方给出可点击
 * 复制的分享链接小字（复制成功后内联浮现「已复制」）。
 *
 * 配色全部取 MaterialTheme token，弹出与浮现统一减速曲线（[LinearOutSlowInEasing]），不闪不弹。
 *
 * 层次：对话框卡片(surfaceContainerHigh) → 标题 → 二维码 Canvas(自带白底，中央叠 logo)
 * → 可复制链接小字。二维码白底由 Canvas 自身的静默区铺满，不再外套一层 Surface。
 *
 * @param url 二维码承载的分享页地址（同时作为下方可复制文本）。
 * @param onDismiss 关闭回调（点击遮罩或返回时触发）。
 */
@Composable
fun QrCodeDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    // QR 矩阵在 url 不变时只编码一次。
    val matrix = remember(url) { QrCode.encode(url) }

    // 减速弹出：透明度 0→1、缩放 0.9→1，统一 LinearOutSlowInEasing，禁弹跳回弹。
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        appear.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 220, easing = LinearOutSlowInEasing),
        )
    }

    // 复制反馈：Dialog 在独立窗口/scrim 中，全局 ToastHost 会被遮住，故在 Dialog 内自管内联反馈。
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = appear.value
                    val scale = 0.9f + 0.1f * appear.value
                    scaleX = scale
                    scaleY = scale
                },
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "扫码下载客户端",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )

                // 二维码 + 中央 logo：Canvas 自身已铺满含静默区的白底，无需再套 Surface。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(MaterialTheme.shapes.large),
                    contentAlignment = Alignment.Center,
                ) {
                    QrCodeCanvas(
                        matrix = matrix,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                    )
                    QuarkLogoBadge(
                        // fractionOfParent 相对整块 Box（含 Canvas 的 20dp padding + 4 模块静默区）取
                        // 0.20 边长；换算到真正承载数据的码区（version3 / 29×29），徽标约占数据区
                        // 25~32% 边长 / 7~10% 面积（屏越小占比越高）。仍在 M 级约 15% 纠错预算内，居中
                        // 只压数据模块、避开三角定位图案，扫码安全——但余量有限：禁止上调超过约 0.22，
                        // 否则数据区遮挡逼近纠错上限会导致部分设备扫不出。
                        modifier = Modifier.fractionOfParent(0.20f),
                    )
                }

                // 可复制链接小字：点击复制到剪贴板，成功后内联浮现「已复制」。
                Text(
                    text = url,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .clickable {
                            scope.launch {
                                if (copyToClipboard(url)) {
                                    copied = true
                                    delay(1600)
                                    copied = false
                                }
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )

                // 「已复制」浮现：减速曲线短时长 fadeIn/fadeOut，不闪不弹；链接始终可见。
                AnimatedVisibility(
                    visible = copied,
                    enter = fadeIn(tween(durationMillis = 160, easing = LinearOutSlowInEasing)),
                    exit = fadeOut(tween(durationMillis = 160, easing = LinearOutSlowInEasing)),
                ) {
                    Text(
                        text = "已复制",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * 二维码中央夸克 logo 徽标。
 *
 * 结构（从下到上）：surface 色圆角方块做「挖空」留白，避免 primary 色 logo 与暗模块混淆破坏
 * 识别；留白块加一圈极细 outline 描边提升精致感；其上居中绘制夸克 logo（主题 primary 色）。
 *
 * logo 用 [remember] 按 primary 缓存，跟随主题切换重建。
 */
@Composable
private fun QuarkLogoBadge(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val logo = remember(primary) { buildQuarkLogo(primary) }

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(percent = 28))
            .background(MaterialTheme.colorScheme.surface)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(percent = 28),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            imageVector = logo,
            contentDescription = "夸克网盘",
            // logo 本体占留白块约 70%，四周留白圈隔开模块。
            modifier = Modifier.fillMaxSize().padding(6.dp),
        )
    }
}

/** 夸克 logo path（viewport 1024×1024，单 path），fill 用主题色而非原 SVG 的固定蓝。 */
private const val QUARK_LOGO_PATH =
    "M512 128c180.608 0 326.72 141.888 340.672 320A221.888 221.888 0 0 1 960 638.72c0 121.088-96.32 221.312-217.792 221.312h-40.064a58.624 58.624 0 0 1-7.232-0.192 36.096 36.096 0 0 1-6.144 0.512H281.792v-0.32C160.32 860.032 64 759.808 64 638.784c0-88.384 51.392-165.696 126.4-200.96C201.664 233.792 370.88 128 512 128zM281.792 534.4c-54.528 0-100.928 45.504-100.928 104.32 0 57.472 44.096 102.208 96.832 104.384l4.096 0.064c2.112 0 4.16 0.128 6.208 0.32h220.352a291.712 291.712 0 0 1-7.616-10.048l-15.872-21.824A2175.552 2175.552 0 0 0 412.288 618.24l-6.4-7.808c-40.96-49.856-78.208-76.16-124.096-76.16zM512 244.8c-84.352 0-183.296 58.816-202.112 174.272 81.792 9.856 139.648 61.568 182.336 112.384l3.968 4.736c32.32 39.36 55.04 68.608 75.392 96.128l12.032 16.32 11.904 16.32a256 256 0 0 0 50.24 50.688c21.44 16.256 41.408 25.408 57.216 27.52h39.232c54.528 0 100.928-45.568 100.928-104.448a104.32 104.32 0 0 0-67.712-98.624l-4.032-1.344a58.432 58.432 0 0 1-35.2-81.28C727.04 337.536 629.12 244.864 512 244.864z"

private fun buildQuarkLogo(color: Color): ImageVector =
    ImageVector.Builder(
        name = "QuarkLogo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 1024f,
        viewportHeight = 1024f,
    ).addPath(
        pathData = addPathNodes(QUARK_LOGO_PATH),
        fill = SolidColor(color),
    ).build()

/**
 * 把 QR 模块矩阵画成黑白格，含 4 模块静默区。
 *
 * 暗模块（matrix[y][x] == true）用 onSurface，亮模块与静默区用 surface，
 * 全部取自 MaterialTheme token，跟随主题切换。
 */
@Composable
private fun QrCodeCanvas(
    matrix: Array<BooleanArray>,
    modifier: Modifier = Modifier,
) {
    val quietZone = 4
    val moduleCount = matrix.size
    val totalModules = moduleCount + quietZone * 2

    val darkColor = MaterialTheme.colorScheme.onSurface
    val lightColor = MaterialTheme.colorScheme.surface

    Canvas(modifier = modifier) {
        val moduleSize = size.minDimension / totalModules
        // 居中：把矩阵摆在画布中心，避免非正方画布出现偏移。
        val originX = (size.width - moduleSize * totalModules) / 2f
        val originY = (size.height - moduleSize * totalModules) / 2f

        // 先铺满亮色背景（含静默区）。
        drawRect(
            color = lightColor,
            topLeft = Offset(originX, originY),
            size = Size(moduleSize * totalModules, moduleSize * totalModules),
        )

        // 再画暗模块。
        for (y in 0 until moduleCount) {
            val row = matrix[y]
            for (x in 0 until moduleCount) {
                if (row[x]) {
                    drawRect(
                        color = darkColor,
                        topLeft = Offset(
                            originX + (x + quietZone) * moduleSize,
                            originY + (y + quietZone) * moduleSize,
                        ),
                        size = Size(moduleSize, moduleSize),
                    )
                }
            }
        }
    }
}

/** 让子组件按父容器边长的指定比例取正方形尺寸（用于把 logo 徽标缩到二维码可视区的一小块）。 */
private fun Modifier.fractionOfParent(fraction: Float): Modifier = layout { measurable, constraints ->
    val side = (constraints.maxWidth * fraction).toInt()
    val placeable = measurable.measure(
        constraints.copy(minWidth = side, maxWidth = side, minHeight = side, maxHeight = side),
    )
    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
}
