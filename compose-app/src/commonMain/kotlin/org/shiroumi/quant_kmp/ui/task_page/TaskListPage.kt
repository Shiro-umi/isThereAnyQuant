package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.shiroumi.quant_kmp.connectSocket
import org.shiroumi.quant_kmp.createHttpClient
import org.shiroumi.quant_kmp.model.CycleOverview
import org.shiroumi.quant_kmp.model.CycleOverviewItem
import org.shiroumi.quant_kmp.model.TaskModel
import org.shiroumi.quant_kmp.showToast

private val json = Json { ignoreUnknownKeys = true }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.TaskListPage(
    scope: AnimatedContentScope,
    onItemSelect: (task: TaskModel) -> Unit
) = Box(modifier = Modifier.fillMaxSize()) {
    val coroutineScope = rememberCoroutineScope { ioDispatcher() }
    var pendingList by remember { mutableStateOf(SnapshotStateList<TaskModel>()) }
    var runningList by remember { mutableStateOf(SnapshotStateList<TaskModel>()) }
    var doneList by remember { mutableStateOf(SnapshotStateList<TaskModel>()) }

    Column(
        modifier = Modifier.width(720.dp)
            .fillMaxHeight()
            .padding(32.dp, 0.dp, 32.dp, 0.dp)
            .align(Alignment.Center)
    ) {

        Box(modifier = Modifier.wrapContentSize().padding(0.dp, 64.dp, 0.dp, 0.dp)) {
            Text(text = "Task List", fontSize = 48.sp)
        }

        LazyColumn(
            contentPadding = PaddingValues(0.dp, 64.dp, 0.dp, 64.dp)
        ) {

            if (runningList.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Running Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(runningList) { item ->
                TaskItem(item, scope, onItemSelect)
            }

            if (pendingList.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Pending Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(pendingList) { item ->
                TaskItem(item, scope, onItemSelect)
            }

            if (doneList.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Done Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(doneList) { item ->
                TaskItem(item, scope, onItemSelect)
            }
        }
    }

    DisposableEffect(Unit) {
        val client = createHttpClient()
        coroutineScope.launch(CoroutineExceptionHandler { _, t ->
            coroutineScope.launch { showToast("Fetch task list failed.") }
            t.printStackTrace()
            client.close()
        }) {
            client.connectSocket { res ->
                val running = SnapshotStateList<TaskModel>()
                val pending = SnapshotStateList<TaskModel>()
                val done = SnapshotStateList<TaskModel>()
                json.decodeFromString<List<TaskModel>>(res).forEach { item ->
                    when (item.status) {
                        "Running" -> running.add(item)
                        "Pending" -> pending.add(item)
                        else -> done.add(item)
                    }
                }
                running.sortByDescending { it.date }
                runningList = running
                pending.sortByDescending { it.date }
                pendingList = pending
                done.sortByDescending { it.date }
                doneList = done
            }
        }
        onDispose {
            client.close()
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.TaskItem(
    task: TaskModel,
    scope: AnimatedContentScope,
    onItemSelect: (task: TaskModel) -> Unit
) {
    val task by rememberUpdatedState(task)
    val interactionSource = remember { MutableInteractionSource() }
    val elevatedCardColor = MaterialTheme.colorScheme.surfaceColorAtElevation(8.dp)
    val surfaceColor = MaterialTheme.colorScheme.surface
    val isHovered by interactionSource.collectIsHoveredAsState()
    val cardPadding by animateDpAsState(targetValue = if (isHovered) 16.dp else 0.dp, label = "cardPadding")
    val cornerRadius by animateDpAsState(targetValue = if (isHovered) 16.dp else 0.dp, label = "cornerRadius")
    val cardElevation by animateDpAsState(targetValue = if (isHovered) 8.dp else 0.dp, label = "cardElevation")
    val cardColor by animateColorAsState(
        targetValue = if (isHovered) elevatedCardColor else surfaceColor,
        label = "cardColor"
    )
    ElevatedCard(
        modifier = Modifier.height(64.dp).fillMaxWidth().sharedBounds(
            sharedContentState = rememberSharedContentState(task.code + task.name + "card"),
            animatedVisibilityScope = scope
        ),
        onClick = { onItemSelect(task) },
        interactionSource = interactionSource,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp, hoveredElevation = cardElevation),
        colors = CardDefaults.elevatedCardColors(
            containerColor = cardColor,
        ),
        shape = RoundedCornerShape(cornerRadius)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(cardPadding, 0.dp, cardPadding, 0.dp)
        ) {
            Row(
                modifier = Modifier.wrapContentSize().align(Alignment.CenterStart),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedCard(
                    modifier = Modifier.wrapContentSize().sharedBounds(
                        sharedContentState = rememberSharedContentState(task.code + task.name),
                        animatedVisibilityScope = scope
                    ),
                ) {
                    Text(
                        text = task.code,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp, 6.dp, 14.dp, 6.dp)
                    )
                }
                Box(modifier = Modifier.fillMaxHeight().padding(12.dp, 0.dp, 0.dp, 0.dp)) {
                    Text(task.name, modifier = Modifier.align(Alignment.CenterStart))
                }

                Box(modifier = Modifier.fillMaxHeight().padding(12.dp, 0.dp, 0.dp, 0.dp)) {
                    Text(task.date, modifier = Modifier.align(Alignment.CenterStart))
                }

                Box(modifier = Modifier.weight(1f))

                if (task.status == "Running") {
                    Box(modifier = Modifier.size(32.dp)) {
                        Ring(
                            modifier = Modifier.align(Alignment.Center),
                            progress = task.progress.percentage,
                            strokeWidth = 2.dp,
                            showNum = false
                        )
                    }
                    Box(modifier = Modifier.fillMaxHeight().padding(0.dp, 0.dp, 12.dp, 0.dp)) {
                        Text(
                            "${task.status} Step ${task.progress.step}/${task.progress.totalStep}",
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxHeight().padding(0.dp, 0.dp, 12.dp, 0.dp)) {
                        Text(task.status, modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}