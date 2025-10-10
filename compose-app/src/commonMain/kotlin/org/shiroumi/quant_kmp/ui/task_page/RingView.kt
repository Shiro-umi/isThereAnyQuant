package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.shiroumi.quant_kmp.roundToString


@Composable
fun Ring(
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 16.dp,
    progress: Float,
    showNum: Boolean = true
) = Box(modifier = modifier.wrapContentSize()) {

    val progressFloat = progress / 10f

    val bgColor = MaterialTheme.colorScheme.primaryContainer
    val primary = MaterialTheme.colorScheme.primary
    var trigger by remember { mutableStateOf(false) }
    val currProgress by animateFloatAsState(
        targetValue = if (!trigger) 0f else progressFloat,
        animationSpec = tween(
            durationMillis = 1500,
            easing = CubicBezierEasing(.4f, .1f, .2f, 1f)
        )
    )
    val sweepAngle = 360 * currProgress

    Canvas(modifier = Modifier.size(180.dp).padding(8.dp)) {
        val strokeWidthPx = strokeWidth.toPx()
        drawArc(
            color = bgColor,
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidthPx)
        )
        drawArc(
            color = primary,
            startAngle = -90f,
            sweepAngle = sweepAngle,
            useCenter = false,
            style = Stroke(width = strokeWidthPx, cap = StrokeCap.Round) // 圆角端点，效果更好
        )

    }
    if (showNum) Text(
        text = (progressFloat * 10f).roundToString(),
        fontSize = 40.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.align(Alignment.Center)
    )

    LaunchedEffect(Unit) {
        delay(300)
        trigger = true
    }
}