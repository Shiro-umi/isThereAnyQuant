package org.shiroumi.quant_kmp.ui.quant_page

import androidx.compose.foundation.layout.*
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.request.*
import io.ktor.utils.io.*
import kotlinx.coroutines.launch
import org.shiroumi.quant_kmp.createHttpClient
import org.shiroumi.quant_kmp.ui.structure.LocalNavTab
import org.shiroumi.quant_kmp.ui.structure.NavTab

@Composable
fun QuantPage(
    textProvider: () -> String,
    onTextChanged: (text: String) -> Unit,
    suffixProvider: () -> String
) {
    val coroutineScope = rememberCoroutineScope { ioDispatcher() }
    val navTab = LocalNavTab.current

    val onEnterPressed = remember {
        {
            coroutineScope.launch {
                val code = "${textProvider()}${suffixProvider()}"
                val client = createHttpClient()
                runCatching {
                    client.get("/tasks/submit?ts_code=$code")
                }.onSuccess {
                    client.close()
                    navTab.currTab = NavTab.TaskTab
                }.onFailure {
                    client.close()
                }
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.wrapContentSize().align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(text = "<Quant.it/>", fontSize = 64.sp, modifier = Modifier.padding(0.dp, 0.dp, 0.dp, 16.dp))
            OutlinedTextField(
                value = textProvider(),
                onValueChange = { text: String -> onTextChanged(text) },
                label = { Text(text = "code") },
                suffix = { Text(text = suffixProvider()) },
                singleLine = true,
                modifier = Modifier.onKeyEvent { keyEvent ->
                    // 1. 检查按键是否是回车键
                    if (keyEvent.key == Key.Enter) {
                        // 2. 检查事件类型，避免按下和抬起时重复触发。我们选择在按键抬起时触发。
                        if (keyEvent.type == KeyEventType.KeyUp) {
                            onEnterPressed()
                        }
                        // 3. 返回 true 表示我们已经处理了这个事件，它不会再被其他组件处理（例如插入一个换行符）
                        return@onKeyEvent true
                    }
                    // 对于其他按键，返回 false，让系统正常处理
                    false
                }
            )
            Text(text = "Enter your stock code here.", fontSize = 14.sp, color = MaterialTheme.colorScheme.secondary)
            ElevatedButton(
                onClick = { onEnterPressed() },
                enabled = ".Nothing" !in textProvider(),
                modifier = Modifier.padding(0.dp, 8.dp, 0.dp, 0.dp)
            ) {
                Text("Quant it!")
            }
        }
    }
}
