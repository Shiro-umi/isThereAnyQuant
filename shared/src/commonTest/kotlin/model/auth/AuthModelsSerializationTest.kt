package model.auth

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Shared 模型序列化测试
 * 验证认证相关模型可以正确序列化和反序列化
 */
class AuthModelsSerializationTest {
    
    private val json = Json { ignoreUnknownKeys = true }
    
    @Test
    fun `test LoginRequest serialization`() {
        val request = LoginRequest(
            username = "test_user",
            password = "secure_password",
            rememberMe = true
        )
        
        val jsonString = json.encodeToString(request)
        println("LoginRequest JSON: $jsonString")
        
        assertContains(jsonString, "\"username\":\"test_user\"")
        assertContains(jsonString, "\"password\":\"secure_password\"")
        assertContains(jsonString, "\"rememberMe\":true")
        
        // 反序列化验证
        val decoded = json.decodeFromString<LoginRequest>(jsonString)
        assertEquals("test_user", decoded.username)
        assertEquals("secure_password", decoded.password)
        assertEquals(true, decoded.rememberMe)
        
        println("✅ LoginRequest 序列化测试通过")
    }
    
    @Test
    fun `test LoginResponse serialization`() {
        val response = LoginResponse(
            accessToken = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.test",
            refreshToken = "dGVzdF9yZWZyZXNoX3Rva2Vu",
            expiresIn = 7200,
            user = UserProfile(
                id = "550e8400-e29b-41d4-a716-446655440000",
                username = "test_user",
                nickname = "Test User",
                avatar = null,
                roles = listOf(UserRole.TRADER, UserRole.ANALYST),
                createdAt = "2024-01-15T08:30:00Z"
            )
        )
        
        val jsonString = json.encodeToString(response)
        println("LoginResponse JSON: $jsonString")
        
        assertContains(jsonString, "\"accessToken\"")
        assertContains(jsonString, "\"refreshToken\"")
        assertContains(jsonString, "\"expiresIn\":7200")
        assertContains(jsonString, "\"TRADER\"")
        assertContains(jsonString, "\"ANALYST\"")
        
        // 反序列化验证
        val decoded = json.decodeFromString<LoginResponse>(jsonString)
        assertEquals(7200, decoded.expiresIn)
        assertEquals(2, decoded.user.roles.size)
        assertTrue(decoded.user.roles.contains(UserRole.TRADER))
        
        println("✅ LoginResponse 序列化测试通过")
    }
    
    @Test
    fun `test RegisterRequest serialization`() {
        val request = RegisterRequest(
            username = "new_user",
            password = "SecurePass123!",
            nickname = "New User"
        )

        val jsonString = json.encodeToString(request)
        println("RegisterRequest JSON: $jsonString")

        val decoded = json.decodeFromString<RegisterRequest>(jsonString)
        assertEquals("new_user", decoded.username)
        assertEquals("New User", decoded.nickname)
        
        println("✅ RegisterRequest 序列化测试通过")
    }
    
    @Test
    fun `test UserRole serialization`() {
        val roles = listOf(UserRole.ADMIN, UserRole.TRADER, UserRole.ANALYST, UserRole.VIEWER)
        
        roles.forEach { role ->
            val jsonString = json.encodeToString(role)
            println("UserRole $role JSON: $jsonString")
            
            val decoded = json.decodeFromString<UserRole>(jsonString)
            assertEquals(role, decoded)
        }
        
        println("✅ UserRole 序列化测试通过")
    }
    
    @Test
    fun `test AuthErrorResponse serialization`() {
        val error = AuthErrorResponse(
            error = "INVALID_CREDENTIALS",
            message = "用户名或密码错误",
            timestamp = "2024-01-15T10:30:00Z"
        )
        
        val jsonString = json.encodeToString(error)
        println("AuthErrorResponse JSON: $jsonString")
        
        assertContains(jsonString, "INVALID_CREDENTIALS")
        assertContains(jsonString, "用户名或密码错误")
        
        println("✅ AuthErrorResponse 序列化测试通过")
    }
    
    @Test
    fun `test json parsing from server response`() {
        // 模拟服务器返回的 JSON
        val serverResponse = """
            {
                "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
                "refreshToken": "refresh_token_value",
                "expiresIn": 7200,
                "user": {
                    "id": "user-uuid-123",
                    "username": "demo_user",
                    "email": "demo@example.com",
                    "nickname": "Demo User",
                    "avatar": null,
                    "roles": ["TRADER"],
                    "createdAt": "2024-01-15T08:30:00Z"
                }
            }
        """.trimIndent()
        
        val response = json.decodeFromString<LoginResponse>(serverResponse)
        assertEquals("eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9", response.accessToken)
        assertEquals("demo_user", response.user.username)
        assertEquals(1, response.user.roles.size)
        assertEquals(UserRole.TRADER, response.user.roles[0])
        
        println("✅ 服务器响应 JSON 解析测试通过")
    }
}
