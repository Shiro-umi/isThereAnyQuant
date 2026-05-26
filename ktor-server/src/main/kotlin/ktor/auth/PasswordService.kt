package ktor.auth

import at.favre.lib.crypto.bcrypt.BCrypt
import model.auth.AuthValidator
import model.auth.ValidationResult

/**
 * 密码加密服务
 * 使用 BCrypt 算法进行密码哈希和验证
 */
object PasswordService {

    private const val BCRYPT_COST = 12
    private val bcrypt = BCrypt.withDefaults()
    private val verifier = BCrypt.verifyer()

    fun hashPassword(password: String): String =
        bcrypt.hashToString(BCRYPT_COST, password.toCharArray())

    fun verifyPassword(password: String, hashedPassword: String): Boolean =
        verifier.verify(password.toCharArray(), hashedPassword).verified

    fun validatePasswordStrength(password: String): PasswordValidationResult {
        return when (val result = AuthValidator.validatePassword(password, strict = true)) {
            is ValidationResult.Success -> PasswordValidationResult(true)
            is ValidationResult.Failure -> PasswordValidationResult(false, result.errors)
        }
    }
}

/**
 * 密码验证结果
 */
data class PasswordValidationResult(
    val isValid: Boolean,
    val errors: List<String> = emptyList()
) {
    /**
     * 获取错误信息字符串
     */
    fun getErrorMessage(): String = errors.joinToString("；")
}
