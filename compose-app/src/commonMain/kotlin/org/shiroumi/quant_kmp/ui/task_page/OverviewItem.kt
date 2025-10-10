package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.shiroumi.quant_kmp.model.BasicInfo
import org.shiroumi.quant_kmp.model.Summarise
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun OverviewItem(
    modifier: Modifier = Modifier,
    basic: BasicInfo,
    summarise: Summarise
) = CardItem(
    title = "核心观点和建议",
    modifier = modifier
) { _ ->
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Ring(progress = summarise.finalScore)
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = summarise.tradingAdvice, fontSize = 40.sp, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = basic.description
            )
        }
    }
}