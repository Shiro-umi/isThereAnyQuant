package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.model.Area
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun AreaItem(modifier: Modifier, area: Area) = CardItem(
    title = "关键价格区域", modifier = modifier
) { colorProvider ->
    Column(
        modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedCard(colors = CardDefaults.elevatedCardColors(colorProvider())) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp).height(48.dp)) {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().weight(1f)) {
                    Text(text = "高概率区间", fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterStart))
                }
                Column(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().weight(1f),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "上沿：${area.highProbArea.high}", fontSize = 12.sp)
                    Text(text = "下沿：${area.highProbArea.low}", fontSize = 12.sp)
                }
            }
        }
        OutlinedCard(colors = CardDefaults.elevatedCardColors(colorProvider())) {
            Row(modifier = Modifier.fillMaxWidth().padding(8.dp).height(48.dp)) {
                Box(modifier = Modifier.fillMaxWidth().fillMaxHeight().weight(1f)) {
                    Text(text = "支撑压力位", fontSize = 18.sp, modifier = Modifier.align(Alignment.CenterStart))
                }
                Column(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().weight(1f),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "阻力：${area.srArea.r}", fontSize = 12.sp)
                    Text(text = "支撑：${area.srArea.s}", fontSize = 12.sp)
                }
            }
        }

        Text(area.description, fontSize = 12.sp)
    }
}