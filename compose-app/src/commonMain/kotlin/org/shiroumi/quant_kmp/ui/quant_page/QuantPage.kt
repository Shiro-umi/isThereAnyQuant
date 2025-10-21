package org.shiroumi.quant_kmp.ui.quant_page

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.ktor.client.request.*
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.*
import kotlinx.coroutines.launch
import model.Quant
import org.shiroumi.quant_kmp.createHttpClient
import org.shiroumi.quant_kmp.json
import org.shiroumi.quant_kmp.showToast
import org.shiroumi.quant_kmp.ui.structure.LocalNavTab
import org.shiroumi.quant_kmp.ui.structure.NavTab
import kotlin.js.json

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
                try {
                    val res = client.get("/tasks/submit?ts_code=$code")
                    if (res.status == HttpStatusCode.BadRequest) {
                        showToast("submit failed.\n ${res.bodyAsText()}")
                        return@launch
                    }
                    val quant = json.decodeFromString<Quant>(res.bodyAsText())
                    showToast("submit succeed.\n ${quant.code}(${quant.name})")
                    navTab.currTab = NavTab.TaskTab
                } catch (e: Exception) {
                    e.printStackTrace()
                } finally {
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
                        if (keyEvent.type == KeyEventType.KeyUp) {
                            onEnterPressed()
                        }
                        return@onKeyEvent true
                    }
                    false
                },
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
