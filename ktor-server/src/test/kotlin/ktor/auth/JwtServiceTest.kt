package ktor.auth

import model.auth.UserRole
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JWT 服务测试
 */
class JwtServiceTest {
    
    private val jwtService = JwtService(
        secret = "test-secret-key-for-jwt-testing-32chars",
        issuer = "test-issuer",
        audience = "test-audience"
    )
    
    @Test
    fun `test access token generation and verification`() {
        println("📝 测试 Access Token 生成和验证...")
        
        val userId = UUID.randomUUID()
        val username = "test_user"
        val roles = listOf(UserRole.TRADER, UserRole.ANALYST)
        
        // 1. 生成 Token
        val token = jwtService.generateAccessToken(userId, username, roles)
        println("   生成 Token: ${token.take(50)}...")
        assertTrue(token.isNotEmpty())
        println("   ✅ Token 生成成功")
        
        // 2. 验证 Token
        val result = jwtService.verifyAccessToken(token)
        assertTrue(result.isValid())
        println("   ✅ Token 验证通过")
        
        // 3. 提取信息
        val decoded = (result as TokenVerificationResult.Valid).decodedJWT
        val extractedUserId = jwtService.extractUserId(decoded)
        val extractedUsername = jwtService.extractUsername(decoded)
        val extractedRoles = jwtService.extractRoles(decoded)
        
        assertEquals(userId, extractedUserId)
        assertEquals(username, extractedUsername)
        assertEquals(2, extractedRoles.size)
        assertTrue(extractedRoles.contains(UserRole.TRADER))
        println("   用户 ID: $extractedUserId")
        println("   用户名: $extractedUsername")
        println("   角色: $extractedRoles")
        println("   ✅ 信息提取正确")
        
        // 4. 检查过期时间
        val remainingSeconds = jwtService.getRemainingSeconds(decoded)
        assertTrue(remainingSeconds > 7100) // 应该接近 2 小时
        println("   剩余有效时间: ${remainingSeconds}秒")
        println("   ✅ 过期时间正确")
        
        println("✅ Access Token 测试通过！\n")
    }
    
    @Test
    fun `test refresh token generation and verification`() {
        println("📝 测试 Refresh Token 生成和验证...")
        
        val userId = UUID.randomUUID()
        
        // 1. 生成普通 Refresh Token
        val token = jwtService.generateRefreshToken(userId, rememberMe = false)
        println("   生成 Refresh Token: ${token.take(50)}...")
        
        // 2. 验证 Token
        val result = jwtService.verifyRefreshToken(token)
        assertTrue(result.isValid())
        println("   ✅ Refresh Token 验证通过")
        
        // 3. 提取用户 ID
        val decoded = (result as TokenVerificationResult.Valid).decodedJWT
        val extractedUserId = jwtService.extractUserId(decoded)
        assertEquals(userId, extractedUserId)
        println("   ✅ 用户 ID 提取正确")
        
        // 4. 生成记住我模式的 Token
        val rememberMeToken = jwtService.generateRefreshToken(userId, rememberMe = true)
        val rememberMeResult = jwtService.verifyRefreshToken(rememberMeToken)
        assertTrue(rememberMeResult.isValid())
        
        val rememberMeDecoded = (rememberMeResult as TokenVerificationResult.Valid).decodedJWT
        val rememberMeExpiry = jwtService.extractExpiration(rememberMeDecoded)
        val normalExpiry = jwtService.extractExpiration(decoded)
        
        // 记住我模式的过期时间应该更长
        assertTrue(rememberMeExpiry > normalExpiry)
        println("   记住我模式过期时间更长: ✅")
        
        println("✅ Refresh Token 测试通过！\n")
    }
    
    @Test
    fun `test invalid token verification`() {
        println("📝 测试无效 Token 验证...")
        
        // 1. 验证错误的 Token
        val invalidResult = jwtService.verifyAccessToken("invalid.token.here")
        assertFalse(invalidResult.isValid())
        println("   无效 Token: ❌ 验证失败（符合预期）")
        
        // 2. 验证空字符串
        val emptyResult = jwtService.verifyAccessToken("")
        assertFalse(emptyResult.isValid())
        println("   空 Token: ❌ 验证失败（符合预期）")
        
        // 3. 使用不同密钥创建的服务验证
        val wrongService = JwtService(
            secret = "different-secret-key-32-chars-long",
            issuer = "test-issuer",
            audience = "test-audience"
        )
        val validToken = jwtService.generateAccessToken(
            UUID.randomUUID(), 
            "test", 
            listOf(UserRole.VIEWER)
        )
        val wrongResult = wrongService.verifyAccessToken(validToken)
        assertFalse(wrongResult.isValid())
        println("   密钥不匹配: ❌ 验证失败（符合预期）")
        
        println("✅ 无效 Token 测试通过！\n")
    }
    
    @Test
    fun `test token type verification`() {
        println("📝 测试 Token 类型验证...")
        
        val userId = UUID.randomUUID()
        
        // 1. Access Token 不能用 Refresh Token 验证器验证
        val accessToken = jwtService.generateAccessToken(userId, "test", listOf(UserRole.TRADER))
        val refreshResult = jwtService.verifyRefreshToken(accessToken)
        assertFalse(refreshResult.isValid())
        println("   Access Token 用 Refresh 验证器: ❌ 失败（符合预期）")
        
        // 2. Refresh Token 不能用 Access Token 验证器验证
        val refreshToken = jwtService.generateRefreshToken(userId)
        val accessResult = jwtService.verifyAccessToken(refreshToken)
        assertFalse(accessResult.isValid())
        println("   Refresh Token 用 Access 验证器: ❌ 失败（符合预期）")
        
        println("✅ Token 类型测试通过！\n")
    }
}
