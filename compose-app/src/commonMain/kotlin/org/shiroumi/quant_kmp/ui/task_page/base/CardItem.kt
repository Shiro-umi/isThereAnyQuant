package org.shiroumi.quant_kmp.ui.task_page.base

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
fun CardItem(
    title: String,
    modifier: Modifier,
    content: @Composable (() -> Color) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val cardElevation by animateDpAsState(targetValue = if (isHovered) 4.dp else 0.dp, label = "cardElevation")
    val elevatedCardColor = MaterialTheme.colorScheme.surfaceColorAtElevation(cardElevation)

    ElevatedCard(
        modifier = modifier.wrapContentHeight().hoverable(interactionSource),
        elevation = CardDefaults.elevatedCardElevation(cardElevation),
        colors = CardDefaults.elevatedCardColors(elevatedCardColor),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp, 16.dp, 24.dp, 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Text(text = title, color = MaterialTheme.colorScheme.primary)
            content { elevatedCardColor }
        }
    }
}
