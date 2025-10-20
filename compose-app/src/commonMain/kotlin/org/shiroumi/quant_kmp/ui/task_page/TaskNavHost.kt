package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import model.Quant

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TaskNavHost() = SharedTransitionLayout {

    var quant: Quant? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = quant,
            modifier = Modifier.fillMaxSize()
        ) animate@{ selected ->
            selected?.run {
                StrategyPage(quant = selected, scope = this@animate) { quant = null }
            } ?: TaskListPage(this@animate) { newTask -> quant = newTask }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            quant = null
        }
    }
}