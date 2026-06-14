package org.shiroumi.quant_kmp.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import model.agent.AgentModelSelectionMode
import org.shiroumi.quant_kmp.di.HttpClientProvider
import org.shiroumi.quant_kmp.ui.animation.ExpandVerticallyAnimation
import org.shiroumi.quant_kmp.ui.agent.state.AgentContract
import org.shiroumi.quant_kmp.ui.core.adaptive.AdaptivePageContainer
import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.core.viewmodel.LocalAgentViewModel
import org.shiroumi.quant_kmp.ui.theme.AppColorTheme
import org.shiroumi.quant_kmp.ui.theme.LocalAppThemeState
import org.shiroumi.quant_kmp.ui.theme.ThemeBrightnessMode
import org.shiroumi.quant_kmp.ui.theme.ThemeManager
import org.shiroumi.quant_kmp.util.UserInfo

@Composable
fun SettingsRoute(
    user: UserInfo?,
    onLogout: () -> Unit,
    agentConfigViewModel: AgentConfigViewModel = viewModel {
        AgentConfigViewModel(
            AgentConfigRepository(HttpClientProvider.apiClient)
        )
    },
) {
    val themeState = LocalAppThemeState.current
    val agentViewModel = LocalAgentViewModel.current
    val agentConfigState by agentConfigViewModel.state.collectAsState()
    val layoutConfig = rememberAdaptiveLayoutConfig()
    val pageModifier = if (layoutConfig.isCompact) {
        Modifier.padding(horizontal = 16.dp, vertical = 0.dp)
    } else {
        Modifier.padding(24.dp)
    }

    AdaptivePageContainer(modifier = pageModifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            AccountSection(user = user, onLogout = onLogout)
            ThemeSection(
                currentTheme = themeState.preference.theme,
                currentBrightness = themeState.preference.brightness,
                onThemeChange = { ThemeManager.setTheme(it) },
                onBrightnessChange = { ThemeManager.setBrightness(it) },
            )
            AgentModelSection(
                state = agentConfigState,
                onSelectPreset = agentConfigViewModel::selectPreset,
                onUseCustom = agentConfigViewModel::useCustomModel,
                onCustomDisplayNameChange = agentConfigViewModel::updateCustomDisplayName,
                onCustomModelIdChange = agentConfigViewModel::updateCustomModelId,
                onCustomBaseUrlChange = agentConfigViewModel::updateCustomBaseUrl,
                onCustomApiKeyChange = agentConfigViewModel::updateCustomApiKey,
                onSubmitCustom = agentConfigViewModel::submitCustomModel,
                onConfirmSwitch = {
                    agentConfigViewModel.confirmPendingSwitch {
                        agentViewModel.dispatch(AgentContract.Action.NewSession)
                    }
                },
                onCancelSwitch = agentConfigViewModel::cancelPendingSwitch,
                onRetry = agentConfigViewModel::load,
            )
        }
    }
}

@Composable
private fun AccountSection(
    user: UserInfo?,
    onLogout: () -> Unit,
) {
    SettingsCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(56.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = user?.getDisplayName() ?: "未登录",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                user?.let {
                    Text(
                        text = it.username,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        user?.let {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
            )

            Text(
                text = it.roles.joinToString(" · ") { role -> role.name },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Button(
            onClick = onLogout,
            modifier = Modifier.align(Alignment.End)
        ) {
            Icon(imageVector = Icons.Outlined.Logout, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("登出")
        }
    }
}

@Composable
private fun ThemeSection(
    currentTheme: AppColorTheme,
    currentBrightness: ThemeBrightnessMode,
    onThemeChange: (AppColorTheme) -> Unit,
    onBrightnessChange: (ThemeBrightnessMode) -> Unit,
) {
    SettingsCard {
        Text(
            text = "主题",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        Text(
            text = "主题色",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            AppColorTheme.entries.forEach { theme ->
                ThemeColorChip(
                    theme = theme,
                    isSelected = theme == currentTheme,
                    onClick = { onThemeChange(theme) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "外观",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeBrightnessMode.entries.forEach { mode ->
                ThemeBrightnessChip(
                    text = when (mode) {
                        ThemeBrightnessMode.System -> "跟随系统"
                        ThemeBrightnessMode.Light -> "浅色"
                        ThemeBrightnessMode.Dark -> "深色"
                    },
                    selected = mode == currentBrightness,
                    onClick = { onBrightnessChange(mode) }
                )
            }
        }
    }
}

@Composable
private fun AgentModelSection(
    state: AgentConfigUiState,
    onSelectPreset: (String) -> Unit,
    onUseCustom: () -> Unit,
    onCustomDisplayNameChange: (String) -> Unit,
    onCustomModelIdChange: (String) -> Unit,
    onCustomBaseUrlChange: (String) -> Unit,
    onCustomApiKeyChange: (String) -> Unit,
    onSubmitCustom: () -> Unit,
    onConfirmSwitch: () -> Unit,
    onCancelSwitch: () -> Unit,
    onRetry: () -> Unit,
) {
    SettingsCard {
        Text(
            text = "Agent 模型",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )

        state.runtimeModelLabel?.let {
            Text(
                text = "当前配置：$it",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.errorMessage != null) {
            Text(
                text = state.errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
            TextButton(onClick = onRetry, enabled = !state.isLoading) {
                Text("重试")
            }
        }

        Text(
            text = "预设模型",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            state.presets.forEach { preset ->
                ThemeBrightnessChip(
                    text = preset.displayName,
                    selected = state.selectedMode == AgentModelSelectionMode.PRESET &&
                        state.selectedPresetKey == preset.key,
                    logo = preset.logoVector(),
                    onClick = { onSelectPreset(preset.key) }
                )
            }
            ThemeBrightnessChip(
                text = "自定义",
                selected = state.selectedMode == AgentModelSelectionMode.CUSTOM,
                onClick = onUseCustom
            )
        }

        ExpandVerticallyAnimation(visible = state.selectedMode == AgentModelSelectionMode.CUSTOM) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = state.customDisplayName,
                    onValueChange = onCustomDisplayNameChange,
                    label = { Text("显示名") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.customBaseUrl,
                    onValueChange = onCustomBaseUrlChange,
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.customModelId,
                    onValueChange = onCustomModelIdChange,
                    label = { Text("Model ID") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = state.customApiKey,
                    onValueChange = onCustomApiKeyChange,
                    label = {
                        Text(
                            if (state.hasCustomApiKey && state.maskedCustomApiKey != null) {
                                "API Key（已保存 ${state.maskedCustomApiKey}）"
                            } else {
                                "API Key"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Button(
                    onClick = onSubmitCustom,
                    enabled = state.isCustomSubmittable && !state.isSaving,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Icon(imageVector = Icons.Outlined.Check, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("应用自定义模型")
                }
            }
        }

        state.infoMessage?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.pendingRestart) {
                    MaterialTheme.colorScheme.tertiary
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }

        if (state.showSwitchConfirmDialog) {
            AlertDialog(
                onDismissRequest = {
                    if (!state.isSaving) onCancelSwitch()
                },
                title = { Text("切换 Agent 模型") },
                text = {
                    Text(
                        "切换到 ${state.pendingSwitchLabel ?: "新模型"} 会重置当前 Agent 连接，并清空前端对话区后重新创建会话。"
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = onConfirmSwitch,
                        enabled = !state.isSaving
                    ) {
                        Text(if (state.isSaving) "切换中" else "确认切换")
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = onCancelSwitch,
                        enabled = !state.isSaving
                    ) {
                        Text("取消")
                    }
                }
            )
        }
    }
}

@Composable
private fun ThemeBrightnessChip(
    text: String,
    selected: Boolean,
    logo: ImageVector? = null,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        label = {
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        },
        leadingIcon = logo?.let { logoImage ->
            {
                Icon(
                    imageVector = logoImage,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    )
}

@Composable
private fun ThemeColorChip(
    theme: AppColorTheme,
    isSelected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = isSelected,
        onClick = onClick,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            labelColor = MaterialTheme.colorScheme.onSurface,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        enabled = enabled,
        label = {
            Text(
                text = theme.displayName,
                style = MaterialTheme.typography.labelLarge
            )
        },
        leadingIcon = {
            Surface(
                modifier = Modifier.size(16.dp),
                shape = CircleShape,
                color = theme.seed
            ) {
                if (isSelected) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
            }
        }
    )
}

@Composable
private fun SettingsCard(
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = content
        )
    }
}
