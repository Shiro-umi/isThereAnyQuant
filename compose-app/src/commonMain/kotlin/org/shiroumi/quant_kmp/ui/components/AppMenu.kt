package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.platform.isWebPlatform
import org.shiroumi.quant_kmp.ui.components.qr.QrCodeDialog

/**
 * 顶栏功能菜单。当前仅承载「下载客户端」入口，且仅在 Web 端展示：
 * 原生 Android / iOS 端通过 [isWebPlatform] 守卫整体隐藏。
 *
 * 点击菜单项弹出二维码对话框（[QrCodeDialog]），扫码进夸克分享页下载安装包。
 *
 * @param clientDownloadUrl 客户端安装包夸克网盘固定分享页地址（编译期注入）。
 */
@Composable
fun AppMenu(
    clientDownloadUrl: String,
) {
    // 仅 Web 端、且分享链接已注入时才显示。
    if (!isWebPlatform() || clientDownloadUrl.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    var showQrDialog by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "功能菜单",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MaterialTheme.shapes.large
        ) {
            DropdownMenuItem(
                text = { Text("下载客户端") },
                onClick = {
                    expanded = false
                    showQrDialog = true
                },
            )
        }
    }

    if (showQrDialog) {
        QrCodeDialog(
            url = clientDownloadUrl,
            onDismiss = { showQrDialog = false },
        )
    }
}
