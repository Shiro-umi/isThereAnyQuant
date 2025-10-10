package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.model.Strategy
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun StrategyItem(
    title: String,
    modifier: Modifier,
    strategy: Strategy
) = CardItem(title = title, modifier = modifier) { bgProvider ->
    Column(modifier = Modifier.fillMaxWidth().wrapContentHeight(),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(0.dp, 12.dp, 0.dp, 0.dp),
                    colors = CardDefaults.elevatedCardColors(bgProvider())
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text(
                                "${strategy.buy.trigger}元",
                                modifier = Modifier.align(Alignment.Center),
                                fontSize = 23.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text("仓位${strategy.buy.position * 100}%", modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Text(
                    text = "买入",
                    modifier = Modifier.align(Alignment.TopStart).offset(8.dp)
                        .background(bgProvider()).padding(4.dp, 0.dp, 4.dp, 0.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(0.dp, 12.dp, 0.dp, 0.dp),
                    colors = CardDefaults.elevatedCardColors(bgProvider())
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text(
                                "${strategy.takeProfit.trigger}元",
                                modifier = Modifier.align(Alignment.Center),
                                fontSize = 23.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text("期望${strategy.takeProfit.expectProfit}", modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Text(
                    text = "止盈",
                    modifier = Modifier.align(Alignment.TopStart).offset(8.dp)
                        .background(bgProvider()).padding(4.dp, 0.dp, 4.dp, 0.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
            Box(modifier = Modifier.weight(1f)) {
                OutlinedCard(
                    modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(0.dp, 12.dp, 0.dp, 0.dp),
                    colors = CardDefaults.elevatedCardColors(bgProvider())
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text(
                                "${strategy.stopLoss.trigger}元",
                                modifier = Modifier.align(Alignment.Center),
                                fontSize = 23.sp,
                                textAlign = TextAlign.Center
                            )
                        }
                        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight()) {
                            Text("期望${strategy.stopLoss.expectLoss}", modifier = Modifier.align(Alignment.Center))
                        }
                    }
                }
                Text(
                    text = "止损",
                    modifier = Modifier.align(Alignment.TopStart).offset(8.dp)
                        .background(bgProvider()).padding(4.dp, 0.dp, 4.dp, 0.dp),
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 13.sp
                )
            }
        }
        Text("${strategy.buy.reason}${strategy.takeProfit.reason}${strategy.stopLoss.reason}")
    }
}

