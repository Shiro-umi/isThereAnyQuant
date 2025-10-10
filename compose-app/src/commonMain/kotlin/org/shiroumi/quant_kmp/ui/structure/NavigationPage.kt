package org.shiroumi.quant_kmp.ui.structure

import androidx.compose.animation.*
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch
import org.shiroumi.quant_kmp.MultiPlatform
import org.shiroumi.quant_kmp.showToast
import org.shiroumi.quant_kmp.ui.quant_page.QuantPage
import org.shiroumi.quant_kmp.ui.task_page.TaskListPage
import org.shiroumi.quant_kmp.ui.suffix
import org.shiroumi.quant_kmp.ui.task_page.TaskNavHost
import org.shiroumi.quant_kmp.ui.theme.AnalyticsIcon
import org.shiroumi.quant_kmp.ui.theme.HomeIcon
import org.shiroumi.quant_kmp.ui.theme.emptyMutableInteractionSource

@Composable
fun NavigationPage() {

    val navTab by remember { mutableStateOf(NavTab.Companion) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            item(
                selected = false,
                onClick = { navTab.currTab = NavTab.QuantTab },
                icon = {
                    Row {
                        Text("Qu")
                        Text("a", color = Color(0xFFFF0000))
                        Text("nt.")
                        Text("i", color = Color(0xFFFF0000))
                        Text("t")
                    }
                },
                label = {},
                interactionSource = emptyMutableInteractionSource

            )
            item(
                selected = navTab.currTab is NavTab.QuantTab,
                onClick = { navTab.currTab = NavTab.QuantTab },
                icon = {
                    Icon(imageVector = HomeIcon, contentDescription = "home")
                },
                label = {
                    Text("Home")
                }
            )
            item(
                selected = navTab.currTab is NavTab.TaskTab,
                onClick = { navTab.currTab = NavTab.TaskTab },
                icon = {
                    Icon(imageVector = AnalyticsIcon, contentDescription = "quant")
                },
                label = {
                    Text("Quant")
                }
            )
        }
    ) {
        MultiPlatform(
            LocalNavTab provides navTab
        ) {
            val scope = rememberCoroutineScope()
            var code: String by remember { mutableStateOf("") }
            var suffix: String by remember { mutableStateOf("") }
            AnimatedVisibility(
                visible = navTab.currTab is NavTab.QuantTab,
                enter = fadeIn() + slideInHorizontally { -it / 3 },
                exit = fadeOut() + slideOutHorizontally { -it / 3 }
            ) {
                QuantPage(
                    textProvider = { code },
                    onTextChanged = { text ->
                        code = text
                        suffix = code.suffix
                    },
                    suffixProvider = { suffix }
                )
            }

            AnimatedVisibility(
                visible = navTab.currTab is NavTab.TaskTab,
                enter = fadeIn() + slideInHorizontally { it / 3 },
                exit = fadeOut() + slideOutHorizontally { it / 3 }
            ) {
                TaskNavHost()
            }

            LaunchedEffect(Unit) {
                scope.launch { showToast("Welcome.") }
            }
        }
    }

//    DisposableEffect(key1 = Unit) {
//        val job = coroutineScope.launch {
//            connectSocket("301379.SZ") { response ->
//                println(response)
//            }
//        }
//        onDispose {
//            job.cancel()
//        }
//    }
}