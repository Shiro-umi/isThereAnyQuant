package org.shiroumi.quant_kmp.feature.auth

import androidx.compose.animation.*
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import org.shiroumi.quant_kmp.ui.theme.AnalyticsIcon
import org.shiroumi.quant_kmp.ui.theme.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import model.auth.AuthValidator
import model.auth.PasswordStrength

import org.shiroumi.quant_kmp.ui.core.adaptive.m3.rememberAdaptiveLayoutConfig
import org.shiroumi.quant_kmp.ui.components.BrandLogo
import org.shiroumi.quant_kmp.ui.core.viewmodel.LocalAuthViewModel
import org.shiroumi.quant_kmp.ui.theme.quantColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── 局部常量（无对应 Material 3 token 的细节配色，全部从 colorScheme 派生）──

private enum class AuthMode { Login, Register }

// ── 入口 ────────────────────────────────────────────────────────────
@Composable
fun LoginScreen(
    onLoginSuccess: () -> Unit = {},
    onNavigateToRegister: () -> Unit = {},
) {
    val viewModel = LocalAuthViewModel.current
    val state by viewModel.state.collectAsState()
    val layoutConfig = rememberAdaptiveLayoutConfig()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val showSnackbar: (String) -> Unit = { msg ->
        coroutineScope.launch {
            snackbarHostState.showSnackbar(msg)
        }
    }

    // Effect 驱动导航，避免与 state 观察产生双重触发
    LaunchedEffect(Unit) {
        viewModel.effect.collectLatest { effect ->
            when (effect) {
                is AuthContract.Effect.NavigateToMain -> onLoginSuccess()
                is AuthContract.Effect.ShowError -> Unit
            }
        }
    }

    LaunchedEffect(state.authStatus, state.errorMessage) {
        val message = state.errorMessage
        if (state.authStatus == AuthContract.AuthStatus.Error && !message.isNullOrBlank()) {
            snackbarHostState.showSnackbar(message)
            viewModel.dispatch(AuthContract.Action.ClearError)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Compact 单栏；Medium 及以上启用左右分栏（与 Skill 第 5 节 Login 页规范一致）
        if (layoutConfig.useTwoPane) {
            ExpandedAuthLayout(
                authState = state,
                isLoading = state.isLoading,
                onLogin = { u, p -> viewModel.dispatch(AuthContract.Action.Login(u, p)) },
                onRegister = { u, p, n -> viewModel.dispatch(AuthContract.Action.Register(u, p, n)) },
                onShowSnackbar = showSnackbar
            )
        } else {
            CompactAuthLayout(
                authState = state,
                isLoading = state.isLoading,
                onLogin = { u, p -> viewModel.dispatch(AuthContract.Action.Login(u, p)) },
                onRegister = { u, p, n -> viewModel.dispatch(AuthContract.Action.Register(u, p, n)) },
                onShowSnackbar = showSnackbar
            )
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
        )
    }
}

// ── 小屏布局 ─────────────────────────────────────────────────────────
@Composable
private fun CompactAuthLayout(
    authState: AuthContract.State,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String?) -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.ime),
        contentAlignment = Alignment.Center
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    pageBrush(
                        width = w,
                        height = h,
                        bgStart = colorScheme.surfaceContainerLow,
                        bgMid = colorScheme.surfaceContainerLowest,
                        bgEnd = colorScheme.background,
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            AuthFormPanel(
                authState = authState,
                isLoading = isLoading,
                onLogin = onLogin,
                onRegister = onRegister,
                onShowSnackbar = onShowSnackbar,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
            )
        }
    }
}

// ── 大屏布局 ─────────────────────────────────────────────────────────
@Composable
private fun ExpandedAuthLayout(
    authState: AuthContract.State,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String?) -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val layoutConfig = rememberAdaptiveLayoutConfig()
    // 容器尺寸按断点伸缩；表单区始终保证 >= ~360dp 可用宽度
    val containerMaxWidth: Dp = when {
        layoutConfig.isXLarge -> 1120.dp
        layoutConfig.isLarge -> 960.dp
        layoutConfig.isExpanded -> 880.dp
        else -> 760.dp // Medium
    }
    val containerHeight: Dp = when {
        layoutConfig.isXLarge || layoutConfig.isLarge -> 620.dp
        layoutConfig.isExpanded -> 580.dp
        else -> 540.dp
    }
    val brandPanelWidth: Dp = when {
        layoutConfig.isXLarge -> 480.dp
        layoutConfig.isLarge -> 440.dp
        layoutConfig.isExpanded -> 360.dp
        else -> 300.dp // Medium：缩窄左侧品牌区，给表单留出足够空间
    }
    val formHorizontalPadding: Dp = when {
        layoutConfig.isXLarge || layoutConfig.isLarge -> 56.dp
        layoutConfig.isExpanded -> 40.dp
        else -> 28.dp
    }
    val formVerticalPadding: Dp = if (layoutConfig.isMedium) 28.dp else 40.dp
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    pageBrush(
                        width = w,
                        height = h,
                        bgStart = colorScheme.surfaceContainerLow,
                        bgMid = colorScheme.surfaceContainerLowest,
                        bgEnd = colorScheme.background,
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            // 左上角光晕（inversePrimary 是 primaryDark 的 Light 对应色，作为品牌点缀）
            Box(
                modifier = Modifier
                    .size(700.dp)
                    .offset(x = (-80).dp, y = (-180).dp)
                    .background(Brush.radialGradient(listOf(colorScheme.inversePrimary.copy(alpha = 0.19f), Color.Transparent)))
                    .align(Alignment.TopStart)
            )
            // 右下角光晕
            Box(
                modifier = Modifier
                    .size(500.dp)
                    .offset(x = 100.dp, y = 80.dp)
                    .background(Brush.radialGradient(listOf(colorScheme.primary.copy(alpha = 0.13f), Color.Transparent)))
                    .align(Alignment.BottomEnd)
            )

            Row(
                modifier = Modifier
                    .widthIn(max = containerMaxWidth)
                    .fillMaxWidth(fraction = 0.92f)
                    .height(containerHeight)
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(Color.Transparent)
            ) {
                BrandPanel(
                    modifier = Modifier
                        .width(brandPanelWidth)
                        .fillMaxHeight()
                )
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(colorScheme.outlineVariant.copy(alpha = 0.5f))
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .background(colorScheme.surfaceContainerLow),
                    contentAlignment = Alignment.Center
                ) {
                    AuthFormPanel(
                        authState = authState,
                        isLoading = isLoading,
                        onLogin = onLogin,
                        onRegister = onRegister,
                        onShowSnackbar = onShowSnackbar,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = formHorizontalPadding, vertical = formVerticalPadding)
                    )
                }
            }
        }
    }
}

// ── 左侧品牌面板 ──────────────────────────────────────────────────────
@Composable
internal fun BrandPanel(modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    BoxWithConstraints(modifier = modifier) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val rad150 = 150.0 * PI / 180.0
        val dx = (cos(rad150) * (w + h) / 2).toFloat()
        val dy = (sin(rad150) * (w + h) / 2).toFloat()
        val cx = w / 2f
        val cy = h / 2f
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.linearGradient(
                        colors = listOf(colorScheme.surfaceContainerHigh, colorScheme.surfaceContainer),
                        start = Offset(cx - dx, cy + dy),
                        end = Offset(cx + dx, cy - dy)
                    )
                )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(56.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(colorScheme.primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        BrandLogo(modifier = Modifier.size(26.dp))
                    }
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = "BigSmart",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        text = "专业量化\n交易助手",
                        fontSize = 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.onSurface,
                        lineHeight = 46.sp
                    )
                    Text(
                        text = "实时监控市场行情，智能执行\n交易策略，稳健增长您的组合",
                        fontSize = 15.sp,
                        color = colorScheme.onSurfaceVariant,
                        lineHeight = 24.sp
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(3.dp)
                            .clip(CircleShape)
                            .background(colorScheme.primary)
                    )
                    FeatureRow(icon = Icons.Filled.ShowChart, label = "实时行情与深度分析")
                    FeatureRow(icon = AnalyticsIcon, label = "机构主力行为跟踪")
                    FeatureRow(icon = Search, label = "图表形态自动识别")
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, label: String) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(16.dp)
        )
        Text(text = label, fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
    }
}

// ── 表单容器（登录/注册动画切换）────────────────────────────────────────
@Composable
private fun AuthFormPanel(
    authState: AuthContract.State,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onRegister: (String, String, String?) -> Unit,
    onShowSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var mode by remember { mutableStateOf(AuthMode.Login) }

    AnimatedContent(
        targetState = mode,
        modifier = modifier,
        transitionSpec = {
            val toRegister = targetState == AuthMode.Register
            val slideSpec = tween<IntOffset>(400, easing = FastOutSlowInEasing)
            val fadeInSpec = tween<Float>(400, delayMillis = 60, easing = FastOutSlowInEasing)
            val fadeOutSpec = tween<Float>(260, easing = FastOutSlowInEasing)
            (slideInHorizontally(slideSpec) { if (toRegister) it / 3 else -it / 3 } +
                    fadeIn(fadeInSpec)) togetherWith
                    (slideOutHorizontally(slideSpec) { if (toRegister) -it / 3 else it / 3 } +
                            fadeOut(fadeOutSpec))
        },
        label = "auth_mode"
    ) { currentMode ->
        when (currentMode) {
            AuthMode.Login -> LoginForm(
                authState = authState,
                isLoading = isLoading,
                onLogin = onLogin,
                onSwitchToRegister = { mode = AuthMode.Register },
                onShowSnackbar = onShowSnackbar
            )
            AuthMode.Register -> RegisterForm(
                isLoading = isLoading,
                onRegister = onRegister,
                onSwitchToLogin = { mode = AuthMode.Login },
                onShowSnackbar = onShowSnackbar
            )
        }
    }
}

// ── 登录表单 ──────────────────────────────────────────────────────────
@Composable
private fun LoginForm(
    authState: AuthContract.State,
    isLoading: Boolean,
    onLogin: (String, String) -> Unit,
    onSwitchToRegister: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val isError = authState.authStatus == AuthContract.AuthStatus.Error
    val errorMessage = authState.errorMessage

    val colorScheme = MaterialTheme.colorScheme
    Column {
        Text(
            text = "欢迎回来",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "请输入账号和密码继续",
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        AuthTextField(
            value = username,
            onValueChange = { username = it },
            label = "用户名",
            placeholder = "请输入用户名",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            isError = isError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        Spacer(Modifier.height(16.dp))

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            placeholder = "请输入密码",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Password,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier
                        .size(36.dp)
                        .focusProperties { canFocus = false }
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            isError = isError,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    if (username.isBlank() || password.isBlank()) {
                        onShowSnackbar("请输入账号和密码")
                    } else {
                        onLogin(username, password)
                    }
                }
            )
        )

        Spacer(Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            LinkText(text = "忘记密码？", onClick = {})
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (username.isBlank() || password.isBlank()) {
                    onShowSnackbar("请输入账号和密码")
                } else {
                    onLogin(username, password)
                }
            },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primaryContainer,
                contentColor = colorScheme.onPrimaryContainer
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colorScheme.onPrimaryContainer,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "登录",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        Spacer(Modifier.height(24.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "还没有账号？", fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            LinkText(text = "立即注册", onClick = onSwitchToRegister, bold = true)
        }
    }
}

// ── 注册表单 ──────────────────────────────────────────────────────────
@Composable
private fun RegisterForm(
    isLoading: Boolean,
    onRegister: (String, String, String?) -> Unit,
    onSwitchToLogin: () -> Unit,
    onShowSnackbar: (String) -> Unit
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var confirmPasswordVisible by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    val passwordStrength = AuthValidator.calculatePasswordStrength(password)
    val passwordsMatch = password == confirmPassword && password.isNotBlank()
    val canSubmit = !isLoading &&
            AuthValidator.validateUsername(username).isSuccess() &&
            AuthValidator.validatePassword(password).isSuccess() &&
            passwordsMatch

    val colorScheme = MaterialTheme.colorScheme
    Column {
        Text(
            text = "创建账号",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = colorScheme.onSurface
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "填写以下信息完成注册",
            fontSize = 14.sp,
            color = colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(28.dp))

        AuthTextField(
            value = username,
            onValueChange = { username = it },
            label = "用户名",
            placeholder = "请输入用户名",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        Spacer(Modifier.height(14.dp))

        AuthTextField(
            value = password,
            onValueChange = { password = it },
            label = "密码",
            placeholder = "请输入密码",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Password,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = { passwordVisible = !passwordVisible },
                    modifier = Modifier
                        .size(36.dp)
                        .focusProperties { canFocus = false }
                ) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            visualTransformation = if (passwordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next
            ),
            keyboardActions = KeyboardActions(
                onNext = { focusManager.moveFocus(FocusDirection.Down) }
            )
        )

        if (password.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            PasswordStrengthBar(strength = passwordStrength)
        }

        Spacer(Modifier.height(14.dp))

        AuthTextField(
            value = confirmPassword,
            onValueChange = { confirmPassword = it },
            label = "确认密码",
            placeholder = "再次输入密码",
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Password,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            },
            trailingIcon = {
                IconButton(
                    onClick = { confirmPasswordVisible = !confirmPasswordVisible },
                    modifier = Modifier
                        .size(36.dp)
                        .focusProperties { canFocus = false }
                ) {
                    Icon(
                        imageVector = if (confirmPasswordVisible) Icons.Default.VisibilityOff
                        else Icons.Default.Visibility,
                        contentDescription = null,
                        tint = colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            },
            visualTransformation = if (confirmPasswordVisible) VisualTransformation.None
            else PasswordVisualTransformation(),
            isError = confirmPassword.isNotBlank() && !passwordsMatch,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    focusManager.clearFocus()
                    val usernameValid = AuthValidator.validateUsername(username)
                    val passwordValid = AuthValidator.validatePassword(password)
                    if (username.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
                        onShowSnackbar("请完整填写所有信息")
                    } else if (usernameValid.isFailure()) {
                        onShowSnackbar(usernameValid.errorMessage().ifEmpty { "用户名格式不正确" })
                    } else if (passwordValid.isFailure()) {
                        onShowSnackbar(passwordValid.errorMessage().ifEmpty { "密码格式不正确" })
                    } else if (!passwordsMatch) {
                        onShowSnackbar("两次输入密码不一致")
                    } else {
                        onRegister(username, password, null)
                    }
                }
            )
        )

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                val usernameValid = AuthValidator.validateUsername(username)
                val passwordValid = AuthValidator.validatePassword(password)
                if (username.isBlank() || password.isBlank() || confirmPassword.isBlank()) {
                    onShowSnackbar("请完整填写所有信息")
                } else if (usernameValid.isFailure()) {
                    onShowSnackbar(usernameValid.errorMessage().ifEmpty { "用户名格式不正确" })
                } else if (passwordValid.isFailure()) {
                    onShowSnackbar(passwordValid.errorMessage().ifEmpty { "密码格式不正确" })
                } else if (!passwordsMatch) {
                    onShowSnackbar("两次输入密码不一致")
                } else {
                    onRegister(username, password, null)
                }
            },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colorScheme.primaryContainer,
                contentColor = colorScheme.onPrimaryContainer
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = colorScheme.onPrimaryContainer,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = "注册",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = "已有账号？", fontSize = 13.sp, color = colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
            LinkText(text = "返回登录", onClick = onSwitchToLogin, bold = true)
        }
    }
}

// ── 公共小组件 ────────────────────────────────────────────────────────
/** 带 hover 颜色变化的链接文字，无 ripple */
@Composable
private fun LinkText(
    text: String,
    onClick: () -> Unit,
    bold: Boolean = false
) {
    val primary = MaterialTheme.colorScheme.primary
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Text(
        text = text,
        fontSize = 13.sp,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Medium,
        color = if (isHovered) MaterialTheme.colorScheme.primaryContainer else primary,
        modifier = Modifier
            .hoverable(interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
    )
}

@Composable
private fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    isError: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier
            .fillMaxWidth(),
        label = { Text(text = label) },
        placeholder = { Text(text = placeholder) },
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        isError = isError,
        singleLine = true,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = MaterialTheme.shapes.small
    )
}

@Composable
private fun PasswordStrengthBar(strength: PasswordStrength) {
    val colorScheme = MaterialTheme.colorScheme
    val semantic = MaterialTheme.quantColors
    val (color, label) = when (strength) {
        PasswordStrength.WEAK   -> colorScheme.error to "弱"
        PasswordStrength.MEDIUM -> semantic.warning to "中"
        PasswordStrength.STRONG -> semantic.success to "强"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LinearProgressIndicator(
            progress = {
                when (strength) {
                    PasswordStrength.WEAK   -> 0.33f
                    PasswordStrength.MEDIUM -> 0.66f
                    PasswordStrength.STRONG -> 1f
                }
            },
            color = color,
            trackColor = colorScheme.outlineVariant,
            modifier = Modifier.weight(1f)
        )
        Text(text = label, color = color, fontSize = 11.sp)
    }
}

// ── 背景渐变辅助 ──────────────────────────────────────────────────────
private fun pageBrush(
    width: Float,
    height: Float,
    bgStart: Color,
    bgMid: Color,
    bgEnd: Color,
): Brush {
    val rad = 135.0 * PI / 180.0
    val half = (width + height) / 2f
    val dx = (cos(rad) * half).toFloat()
    val dy = (sin(rad) * half).toFloat()
    val cx = width / 2f
    val cy = height / 2f
    return Brush.linearGradient(
        colorStops = arrayOf(
            0f to bgStart,
            0.5f to bgMid,
            1f to bgEnd
        ),
        start = Offset(cx - dx, cy - dy),
        end = Offset(cx + dx, cy + dy)
    )
}
