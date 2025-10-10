package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.model.CycleOverview
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun CyclesItems(
    modifier: Modifier = Modifier,
    cycle: CycleOverview,
) = CardItem(
    title = "趋势概览",
    modifier = modifier.fillMaxWidth()
) { _ ->
    Column(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            cycle.`class`.chunked(2).forEach { item ->
                Row(modifier = Modifier.fillMaxWidth().wrapContentHeight().weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {

                    val cycles = item.map { it.cycle }
                    val values = item.map { it.value }
                    Column(
                        modifier = Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        (0 until item.size).forEach { i ->
                            Text(
                                text = cycles[i],
                                fontSize = 12.sp,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier.wrapContentSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        (0 until item.size).forEach { i ->
                            val value = values[i]
                            val color = when {
                                "上升" in value -> MaterialTheme.colorScheme.primary
                                "下降" in value -> MaterialTheme.colorScheme.error
                                else -> Color.Unspecified
                            }
                            Text(
                                text = value,
                                fontSize = 12.sp,
                                color = color
                            )
                        }
                    }
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().wrapContentSize()) {
            Text(cycle.description, fontSize = 12.sp)
        }
    }
}