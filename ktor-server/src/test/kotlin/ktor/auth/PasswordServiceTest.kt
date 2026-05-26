package ktor.auth

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 密码服务测试
 */
class PasswordServiceTest {
    
    @Test
    fun `test password hashing and verification`() {
        println("📝 测试密码加密和验证...")
        
        val password = "SecurePass123!"
        
        // 1. 加密密码
        val hashed = PasswordService.hashPassword(password)
        println("   原始密码: $password")
        println("   加密后: ${hashed.take(30)}...")
        
        assertTrue(hashed.startsWith("$2a$"))
        println("   ✅ 密码加密成功")
        
        // 2. 验证正确密码
        val valid = PasswordService.verifyPassword(password, hashed)
        assertTrue(valid)
        println("   ✅ 正确密码验证通过")
        
        // 3. 验证错误密码
        val invalid = PasswordService.verifyPassword("wrong_password", hashed)
        assertFalse(invalid)
        println("   ✅ 错误密码验证失败（符合预期）")
        
        // 4. 验证耗时（BCrypt 应该较慢）
        val start = System.currentTimeMillis()
        PasswordService.hashPassword(password)
        val duration = System.currentTimeMillis() - start
        println("   加密耗时: ${duration}ms")
        assertTrue(duration > 50) // 至少 50ms
        println("   ✅ 加密耗时符合预期（>50ms）")
        
        println("✅ 密码服务测试通过！\n")
    }
    
    @Test
    fun `test password strength validation`() {
        println("📝 测试密码强度验证...")
        
        // 1. 弱密码 - 太短
        val weak1 = PasswordService.validatePasswordStrength("short")
        assertFalse(weak1.isValid)
        println("   密码 'short': ❌ 不通过 - ${weak1.getErrorMessage()}")
        
        // 2. 弱密码 - 缺少大写字母
        val weak2 = PasswordService.validatePasswordStrength("lowercase123!")
        assertFalse(weak2.isValid)
        println("   密码 'lowercase123!': ❌ 不通过 - ${weak2.getErrorMessage()}")
        
        // 3. 弱密码 - 缺少数字
        val weak3 = PasswordService.validatePasswordStrength("NoNumbers!")
        assertFalse(weak3.isValid)
        println("   密码 'NoNumbers!': ❌ 不通过 - ${weak3.getErrorMessage()}")
        
        // 4. 弱密码 - 缺少特殊字符
        val weak4 = PasswordService.validatePasswordStrength("NoSpecial123")
        assertFalse(weak4.isValid)
        println("   密码 'NoSpecial123': ❌ 不通过 - ${weak4.getErrorMessage()}")
        
        // 5. 强密码
        val strong = PasswordService.validatePasswordStrength("SecurePass123!")
        assertTrue(strong.isValid)
        println("   密码 'SecurePass123!': ✅ 通过")
        
        println("✅ 密码强度验证测试通过！\n")
    }
}
