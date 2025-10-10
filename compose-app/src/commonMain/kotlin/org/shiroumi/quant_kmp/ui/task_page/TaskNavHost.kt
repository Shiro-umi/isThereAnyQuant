package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import org.shiroumi.quant_kmp.model.TaskModel

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun TaskNavHost() = SharedTransitionLayout {

    var task: TaskModel? by remember { mutableStateOf(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = task,
            modifier = Modifier.fillMaxSize()
        ) animate@{ selected ->
            selected?.run {
                StrategyPage(task = selected, scope = this@animate) { task = null }
            } ?: TaskListPage(this@animate) { newTask -> task = newTask }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            task = null
        }
    }
}