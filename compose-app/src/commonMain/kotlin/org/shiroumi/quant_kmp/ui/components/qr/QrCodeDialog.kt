package org.shiroumi.quant_kmp.ui.components.qr

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * 客户端下载二维码对话框。
 *
 * 用纯 Kotlin 自绘 QR 编码器 [QrCode] 把 [url] 编码为模块矩阵，再用 Compose Canvas
 * 画黑白格。包含 4 模块的静默区（quiet zone），配色全部取 MaterialTheme token。
 *
 * 只展示二维码，不展示链接文本。弹出采用减速曲线（[LinearOutSlowInEasing]）。
 *
 * @param url 二维码承载的分享页地址。
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

                // 二维码画布：白底承载，自身为正方形。
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    QrCodeCanvas(
                        matrix = matrix,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(MaterialTheme.shapes.large)
                            .padding(20.dp),
                    )
                }
            }
        }
    }
}

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
