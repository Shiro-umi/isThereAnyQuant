package org.shiroumi.quant_kmp.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.shiroumi.quant_kmp.util.UserInfo

/**
 * 用户菜单组件
 * 显示当前用户信息和登出选项
 *
 * @param user 当前登录用户信息，为 null 时不显示菜单
 * @param onLogout 用户点击登出时的回调
 */
@Composable
fun UserMenu(
    user: UserInfo?,
    onLogout: () -> Unit,
) {
    // 如果没有登录，不显示菜单
    if (user == null) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = "用户菜单",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(28.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = MenuDefaults.shape,
            containerColor = MenuDefaults.containerColor,
        ) {
            // 用户信息标题
            DropdownMenuItem(
                text = {
                    Text(
                        text = user.getDisplayName(),
                        style = MaterialTheme.typography.titleSmall
                    )
                },
                onClick = { },
                leadingIcon = {
                    Icon(Icons.Default.Person, contentDescription = null)
                }
            )

            // 分隔线
            HorizontalDivider()

            // 登出选项
            DropdownMenuItem(
                text = { Text("登出") },
                onClick = {
                    expanded = false
                    onLogout()
                },
                leadingIcon = {
                    Icon(
                        Icons.Default.Logout,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            )
        }
    }
}
