package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.shiroumi.quant_kmp.model.AttentionAndRisk
import org.shiroumi.quant_kmp.ui.task_page.base.CardItem

@Composable
fun RiskNotiItem(
    modifier: Modifier = Modifier,
    risk: AttentionAndRisk
) = CardItem(title = risk.keyword, modifier = modifier) { _ ->
    Text(risk.description)
}