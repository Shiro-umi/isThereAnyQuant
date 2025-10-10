package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.model.Signal
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun SignalCandleItem(modifier: Modifier = Modifier, signal: Signal) = CardItem(
    title = "关键k线信号", modifier = modifier
) { _ ->
    Row(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        horizontalArrangement = Arrangement.End.apply {
        }
    ) {
        Text(signal.description, modifier = Modifier.fillMaxSize().weight(2f))
        Column(
            modifier = Modifier.wrapContentSize().wrapContentHeight().weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Text(
                    signal.signalCandle,
                    fontSize = 32.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
            Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                Text(
                    "信号质量：${signal.candleClassScore}",
                    fontSize = 13.sp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }
        }
    }
}