@file:OptIn(ExperimentalUuidApi::class)

package org.shiroumi.quant_kmp.ui.task_page

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import io.ktor.utils.io.*
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import model.Quant
import model.Status
import model.TaskList
import model.format
import org.shiroumi.quant_kmp.SocketClient
import org.shiroumi.quant_kmp.json
import org.shiroumi.quant_kmp.showToast
import org.shiroumi.quant_kmp.ui.theme.Search
import org.shiroumi.quant_kmp.ui.theme.emptyMutableInteractionSource
import kotlin.uuid.ExperimentalUuidApi

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun SharedTransitionScope.TaskListPage(
    scope: AnimatedContentScope,
    onItemSelect: (quant: Quant) -> Unit
) = Box(modifier = Modifier.fillMaxSize()) {
    val coroutineScope = rememberCoroutineScope { ioDispatcher() }
    var filter by remember { mutableStateOf("") }
    var showFilter by remember { mutableStateOf(false) }
    var pendingList by remember { mutableStateOf(SnapshotStateList<Quant>()) }
    var runningList by remember { mutableStateOf(SnapshotStateList<Quant>()) }
    var errorList by remember { mutableStateOf(SnapshotStateList<Quant>()) }
    var doneList by remember { mutableStateOf(SnapshotStateList<Quant>()) }

    val filterPending by remember { derivedStateOf { pendingList.filter { filter in it.name || filter in it.code } } }
    val filterRunning by remember { derivedStateOf { runningList.filter { filter in it.name || filter in it.code } } }
    val filterError by remember { derivedStateOf { errorList.filter { filter in it.name || filter in it.code } } }
    val filterDone by remember { derivedStateOf { doneList.filter { filter in it.name || filter in it.code } } }

    Column(
        modifier = Modifier.width(720.dp)
            .fillMaxHeight()
            .padding(32.dp, 0.dp, 32.dp, 0.dp)
            .align(Alignment.Center)
    ) {

        Box(modifier = Modifier.fillMaxWidth().wrapContentHeight().padding(0.dp, 48.dp, 0.dp, 0.dp)) {
            Text(text = "Task List", fontSize = 48.sp)
        }

        LazyColumn(
            contentPadding = PaddingValues(0.dp, 64.dp, 0.dp, 64.dp)
        ) {

            if (filterRunning.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Running Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(filterRunning, { item -> item.uuid }) { item ->
                TaskItem(item, scope, onItemSelect)
            }

            if (filterError.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Error Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }
            items(filterError, { item -> item.uuid }) { item ->
                TaskItem(item, scope, onItemSelect)
            }

            if (filterPending.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Pending Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(filterPending, { item -> item.uuid }) { item ->
                TaskItem(item, scope, onItemSelect)
            }

            if (filterDone.isNotEmpty()) stickyHeader {
                Box(modifier = Modifier.fillMaxWidth().height(64.dp).background(MaterialTheme.colorScheme.surface)) {
                    Text(
                        "Done Tasks",
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.CenterStart)
                    )
                }
            }

            items(filterDone, { item -> item.uuid }) { item ->
                TaskItem(item, scope, onItemSelect)
            }
        }
    }

    AnimatedVisibility(
        visible = pendingList.isEmpty() && runningList.isEmpty() && errorList.isEmpty() && doneList.isEmpty(),
        enter = fadeIn(),
        exit = fadeOut()
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }
    }

    AnimatedVisibility(visible = showFilter, enter = fadeIn(), exit = fadeOut()) {
        Box(
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)
                .clickable(interactionSource = emptyMutableInteractionSource) {
                    filter = ""
                    showFilter = false
                })
    }

    AnimatedContent(
        targetState = showFilter,
        modifier = Modifier.width(640.dp).align(Alignment.BottomCenter),
    ) s@{ show ->
        var tmpFilter by remember { mutableStateOf("") }
        if (show) {
            Box(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = tmpFilter,
                    onValueChange = { t -> tmpFilter = t },
                    label = { Text("过滤任务") },
                    suffix = {
                        IconButton(onClick = {
                            filter = tmpFilter
                            tmpFilter = ""
                            showFilter = false
                        }, modifier = Modifier.align(Alignment.CenterEnd).offset((-2).dp).padding(0.dp)) {
                            Icon(
                                Search,
                                "搜索",
                                modifier = Modifier.padding(0.dp)
                            )
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.width(480.dp).wrapContentHeight().align(Alignment.Center).sharedElement(
                        sharedContentState = rememberSharedContentState("fab"),
                        animatedVisibilityScope = this@s
                    ),
                    shape = RoundedCornerShape(40.dp)
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().align(Alignment.BottomCenter)) {
                if (filter.isBlank()) {
                    FloatingActionButton(
                        onClick = { showFilter = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp).sharedElement(
                            sharedContentState = rememberSharedContentState("fab"),
                            animatedVisibilityScope = this@s
                        ),
                        shape = remember { RoundedCornerShape(32.dp) }
                    ) {
                        Icon(Search, contentDescription = "搜索")
                    }
                } else {
                    ExtendedFloatingActionButton(
                        onClick = { showFilter = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(32.dp).sharedElement(
                            sharedContentState = rememberSharedContentState("fab"),
                            animatedVisibilityScope = this@s
                        ),
                        shape = remember { RoundedCornerShape(32.dp) }
                    ) {
                        Icon(Search, contentDescription = "搜索")
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(filter)
                    }
                }
            }
        }
    }


    DisposableEffect(Unit) {
        val client = SocketClient()
        coroutineScope.launch(CoroutineExceptionHandler { _, t ->
            coroutineScope.launch {
                client.close()
                showToast("Fetch task list failed.")
            }
            t.printStackTrace()
        }) {

            client.open { res ->
                val taskList = json.decodeFromString<TaskList>(res)
                updateListWithDiff(runningList, taskList.runningList)
                updateListWithDiff(pendingList, taskList.pendingList)
                updateListWithDiff(errorList, taskList.errorList)
                updateListWithDiff(doneList, taskList.doneList)
            }
        }
        onDispose {
            coroutineScope.launch { client.close() }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class, ExperimentalSharedTransitionApi::class)
@Composable
private fun SharedTransitionScope.TaskItem(
    quant: Quant,
    scope: AnimatedContentScope,
    onItemSelect: (quant: Quant) -> Unit
) {
    val quant by rememberUpdatedState(quant)
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
        modifier = Modifier.height(64.dp).fillMaxWidth(),
        onClick = click@{
            if (quant.status !is Status.Done) return@click
            onItemSelect(quant)
        },
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
                    modifier = Modifier.wrapContentSize(),
                ) {
                    Text(
                        text = quant.code,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(14.dp, 6.dp, 14.dp, 6.dp)
                    )
                }
                Box(
                    modifier = Modifier.fillMaxHeight().sharedBounds(
                        sharedContentState = rememberSharedContentState(quant.uuid),
                        animatedVisibilityScope = scope
                    ).padding(12.dp, 0.dp, 0.dp, 0.dp)
                ) {
                    Text(quant.name, modifier = Modifier.align(Alignment.CenterStart))
                }

                Box(modifier = Modifier.fillMaxHeight().padding(12.dp, 0.dp, 0.dp, 0.dp)) {
                    Text(quant.triggerTime.format(), modifier = Modifier.align(Alignment.CenterStart), fontSize = 12.sp)
                }

                Box(modifier = Modifier.weight(1f))

                if (quant.status is Status.Running) {
                    Box(modifier = Modifier.size(32.dp)) {
                        Ring(
                            modifier = Modifier.align(Alignment.Center),
                            progress = quant.progress.progress,
                            strokeWidth = 2.dp,
                            showNum = false
                        )
                    }
                    Box(modifier = Modifier.fillMaxHeight().padding(0.dp, 0.dp, 12.dp, 0.dp)) {
                        Text(
                            "${quant.status::class.simpleName} Step ${quant.progress.step}/${quant.progress.totalStep}",
                            modifier = Modifier.align(Alignment.CenterEnd)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxHeight().padding(0.dp, 0.dp, 12.dp, 0.dp)) {
                        Text("${quant.status::class.simpleName}", modifier = Modifier.align(Alignment.CenterEnd))
                    }
                }
            }
        }
    }
}

private fun updateListWithDiff(
    currentList: SnapshotStateList<Quant>,
    newList: List<Quant>
) {
    val newMap = newList.associateBy { it.uuid }
    val oldMap = currentList.associateBy { it.uuid }
    val toRemove = oldMap.keys - newMap.keys
    if (toRemove.isNotEmpty()) {
        currentList.removeAll { it.uuid in toRemove }
    }
    newList.forEachIndexed { index, newTask ->
        val oldTask = oldMap[newTask.uuid]
        oldTask?.let { old ->
            if (old == newTask) return@let
            val oldIndex = currentList.indexOfFirst { it == oldTask }
            if (oldIndex != -1) currentList[oldIndex] = newTask
        } ?: currentList.add(index, newTask)
    }
}