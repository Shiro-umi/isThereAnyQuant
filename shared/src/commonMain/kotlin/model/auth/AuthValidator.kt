package model.auth

/**
 * 认证验证器
 * 统一前后端的验证逻辑
 */
object AuthValidator {

    // ==================== 用户名验证 ====================

    private const val USERNAME_MIN_LENGTH = AuthConstants.USERNAME_MIN_LENGTH
    private const val USERNAME_MAX_LENGTH = AuthConstants.USERNAME_MAX_LENGTH
    private val USERNAME_PATTERN = Regex("^[a-zA-Z0-9_]+$")

    /**
     * 验证用户名
     * @return 验证结果
     */
    fun validateUsername(username: String): ValidationResult {
        val errors = buildList {
            if (username.length < USERNAME_MIN_LENGTH) {
                add("用户名长度至少为 $USERNAME_MIN_LENGTH 位")
            }
            if (username.length > USERNAME_MAX_LENGTH) {
                add("用户名长度最多为 $USERNAME_MAX_LENGTH 位")
            }
            if (!USERNAME_PATTERN.matches(username)) {
                add("用户名只能包含字母、数字和下划线")
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }

    // ==================== 密码验证 ====================

    private const val PASSWORD_MIN_LENGTH = AuthConstants.PASSWORD_MIN_LENGTH
    private const val PASSWORD_MAX_LENGTH = 128

    /**
     * 验证密码强度
     * @param password 密码
     * @param strict 是否严格模式（后端验证使用）
     * @return 验证结果
     */
    fun validatePassword(password: String, strict: Boolean = true): ValidationResult {
        val errors = buildList {
            if (password.length < PASSWORD_MIN_LENGTH) {
                add("密码长度至少为 $PASSWORD_MIN_LENGTH 位")
            }
            if (password.length > PASSWORD_MAX_LENGTH) {
                add("密码长度过长")
            }

            if (strict) {
                if (!password.any { it.isUpperCase() }) {
                    add("密码必须包含至少一个大写字母")
                }
                if (!password.any { it.isLowerCase() }) {
                    add("密码必须包含至少一个小写字母")
                }
                if (!password.any { it.isDigit() }) {
                    add("密码必须包含至少一个数字")
                }
                if (!password.any { !it.isLetterOrDigit() }) {
                    add("密码必须包含至少一个特殊字符 (!@#$%^&*等)")
                }
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }

    /**
     * 计算密码强度等级
     * @return 密码强度
     */
    fun calculatePasswordStrength(password: String): PasswordStrength {
        if (password.length < 8) return PasswordStrength.WEAK

        var score = 0
        if (password.any { it.isUpperCase() }) score++
        if (password.any { it.isLowerCase() }) score++
        if (password.any { it.isDigit() }) score++
        if (password.any { !it.isLetterOrDigit() }) score++

        return when (score) {
            0, 1 -> PasswordStrength.WEAK
            2, 3 -> PasswordStrength.MEDIUM
            4 -> PasswordStrength.STRONG
            else -> PasswordStrength.WEAK
        }
    }

    // ==================== 注册请求验证 ====================

    /**
     * 验证注册请求
     * @param request 注册请求
     * @return 验证结果
     */
    fun validateRegisterRequest(request: RegisterRequest): ValidationResult {
        val errors = buildList {
            validateUsername(request.username).onFailure { addAll(it) }
            validatePassword(request.password).onFailure { addAll(it) }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }

    // ==================== 登录请求验证 ====================

    /**
     * 验证登录请求
     * @param request 登录请求
     * @return 验证结果
     */
    fun validateLoginRequest(request: LoginRequest): ValidationResult {
        val errors = buildList {
            if (request.username.isBlank()) {
                add("用户名不能为空")
            }
            if (request.password.isBlank()) {
                add("密码不能为空")
            }
        }

        return if (errors.isEmpty()) {
            ValidationResult.Success
        } else {
            ValidationResult.Failure(errors)
        }
    }
}

/**
 * 验证结果密封类
 */
sealed class ValidationResult {
    object Success : ValidationResult()
    data class Failure(val errors: List<String>) : ValidationResult()

    /**
     * 是否成功
     */
    fun isSuccess(): Boolean = this is Success

    /**
     * 是否失败
     */
    fun isFailure(): Boolean = this is Failure

    /**
     * 获取错误消息
     */
    fun errorMessage(): String = when (this) {
        is Success -> ""
        is Failure -> errors.joinToString("；")
    }

    /**
     * 失败时执行回调
     */
    inline fun onFailure(action: (List<String>) -> Unit): ValidationResult {
        if (this is Failure) {
            action(errors)
        }
        return this
    }
}

/**
 * 密码强度枚举
 */
enum class PasswordStrength {
    WEAK, MEDIUM, STRONG
}
