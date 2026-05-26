package org.shiroumi.server.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.openqa.selenium.By
import org.openqa.selenium.Dimension
import org.openqa.selenium.WebDriver
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.interactions.Actions
import java.net.InetSocketAddress
import java.net.Socket
import java.time.Duration
import kotlin.math.max
import kotlin.test.assertEquals

/**
 * Deployed end-to-end smoke test for the service-owned strategy flow.
 *
 * Business chain under test:
 * 1. A deployed strategy-service is reachable on the internal JSONL socket.
 * 2. The deployed Ktor web app serves the Compose Web canvas shell.
 * 3. A real user login is performed from Chrome's same-origin page context so the browser receives
 *    the same HttpOnly auth cookie as an interactive login.
 * 4. The user opens the strategy position tracking screen.
 * 5. Chrome subscribes to STRATEGY_POSITION_TRACKING and receives a full SYNC/UPDATE/ERROR frame
 *    from Ktor, which is the external adapter over strategy-service owned POSITION_TRACKING snapshots.
 *
 * Compose Web renders into a canvas and does not expose stable business DOM nodes. Browser operations here
 * therefore use the browser page context for auth and the canvas element as the navigation input surface.
 * The business assertion is taken from a browser-native WebSocket opened inside Chrome, not DOM text lookup.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "QUANT_E2E_ENABLED", matches = "true")
class StrategyServiceDeployedE2ETest {
    private val baseUrl = env("QUANT_E2E_BASE_URL", "http://localhost:9871")
    private val strategyHost = env("QUANT_E2E_STRATEGY_HOST", "127.0.0.1")
    private val strategyPort = env("QUANT_E2E_STRATEGY_PORT", "9971").toInt()
    private val username = env("QUANT_E2E_USERNAME", "shiroumi")
    private val password = System.getenv("QUANT_E2E_PASSWORD").orEmpty()
    private val headless = env("QUANT_E2E_HEADLESS", "false").toBooleanStrictOrNull() ?: false
    private val json = Json { ignoreUnknownKeys = true }

    private var driver: ChromeDriver? = null

    @AfterEach
    fun tearDown() {
        driver?.quit()
        driver = null
    }

    @Test
    fun `deployed app logs in and receives strategy tracking websocket frame`() {
        assumeTrue(password.isNotBlank(), "Set QUANT_E2E_PASSWORD for the deployed login account")
        assertStrategyServiceSocketReachable()

        val browser = newChromeDriver()
        driver = browser
        browser.manage().timeouts().implicitlyWait(Duration.ofMillis(300))
        browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(70))
        browser.manage().window().size = Dimension(1440, 1000)

        browser.get(baseUrl)
        waitUntil(Duration.ofSeconds(30), "Compose canvas is ready") {
            browser.findElements(By.id("compose-root")).isNotEmpty() &&
                browser.executeScript("return !!window.__quantAppReady || document.readyState === 'complete'") == true
        }

        // KMP Compose Web has no business DOM nodes. Use the one canvas as the hit target.
        val canvas = browser.findElement(By.id("compose-root"))
        assertTrue(canvas.size.width > 0 && canvas.size.height > 0, "Compose canvas should have a rendered size")

        val accessToken = loginFromBrowserContext(browser)
        browser.navigate().refresh()
        waitUntil(Duration.ofSeconds(30), "Compose canvas is ready after auth refresh") {
            browser.findElements(By.id("compose-root")).isNotEmpty() &&
                browser.executeScript("return document.readyState === 'complete'") == true
        }

        val authenticatedCanvas = browser.findElement(By.id("compose-root"))
        openPositionTrackingThroughCanvas(browser, authenticatedCanvas)

        val trackingFrame = subscribeTrackingFromBrowserContext(
            browser = browser,
            accessToken = accessToken,
            timeout = Duration.ofSeconds(45),
        )

        assertNotNull(trackingFrame, "Expected a STRATEGY_POSITION_TRACKING websocket frame")
        assertTrue(
            trackingFrame.contains("\"action\":\"SYNC\"") ||
                trackingFrame.contains("\"action\":\"UPDATE\"") ||
                trackingFrame.contains("\"action\":\"ERROR\""),
            "Tracking frame must carry a terminal frontend action, got=$trackingFrame"
        )
    }

    private fun newChromeDriver(): ChromeDriver {
        val options = ChromeOptions().apply {
            addArguments("--disable-gpu")
            addArguments("--window-size=1440,1000")
            addArguments("--lang=zh-CN")
            if (headless) addArguments("--headless=new")
        }
        return ChromeDriver(options)
    }

    private fun assertStrategyServiceSocketReachable() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(strategyHost, strategyPort), 2_000)
        }
    }

    private fun loginFromBrowserContext(browser: ChromeDriver): String {
        @Suppress("UNCHECKED_CAST")
        val response = browser.executeAsyncScript(
            """
            const username = arguments[0];
            const password = arguments[1];
            const done = arguments[arguments.length - 1];
            fetch('/api/auth/login', {
              method: 'POST',
              credentials: 'include',
              headers: { 'Content-Type': 'application/json' },
              body: JSON.stringify({ username, password, rememberMe: false })
            }).then(async response => {
              done({ status: response.status, body: await response.text() });
            }).catch(error => {
              done({ status: 0, body: String(error) });
            });
            """.trimIndent(),
            username,
            password,
        ) as Map<String, Any?>

        assertEquals(200L, response["status"], "Browser-context login should succeed: ${response["body"]}")
        val body = response["body"] as String
        return json.parseToJsonElement(body).jsonObject["accessToken"]?.jsonPrimitive?.content
            ?: error("Browser-context login response does not contain accessToken: $body")
    }

    private fun openPositionTrackingThroughCanvas(browser: WebDriver, canvas: org.openqa.selenium.WebElement) {
        val actions = Actions(browser)
        val width = canvas.size.width
        val height = canvas.size.height

        // Desktop NavigationRail: third top-level item is "跟踪".
        val trackingX = max(36, (width * 0.03).toInt())
        val trackingY = (height * 0.24).toInt()

        actions.moveToElement(canvas, trackingX - width / 2, trackingY - height / 2)
            .click()
            .perform()
    }

    private fun subscribeTrackingFromBrowserContext(
        browser: ChromeDriver,
        accessToken: String,
        timeout: Duration,
    ): String {
        @Suppress("UNCHECKED_CAST")
        val response = browser.executeAsyncScript(
            """
            const token = arguments[0];
            const timeoutMs = arguments[1];
            const done = arguments[arguments.length - 1];
            const wsUrl = new URL('/ws/app-stream', window.location.href);
            wsUrl.protocol = wsUrl.protocol === 'https:' ? 'wss:' : 'ws:';
            wsUrl.searchParams.set('token', token);

            let finished = false;
            const finish = (value) => {
              if (finished) return;
              finished = true;
              clearTimeout(timer);
              try { socket.close(); } catch (_) {}
              done(value);
            };
            const timer = setTimeout(() => finish({ ok: false, message: 'timeout waiting for tracking frame' }), timeoutMs);
            const socket = new WebSocket(wsUrl.toString());
            socket.onopen = () => {
              socket.send(JSON.stringify({ command: 'SUBSCRIBE', targetId: 'STRATEGY_POSITION_TRACKING' }));
            };
            socket.onerror = () => finish({ ok: false, message: 'websocket error' });
            socket.onmessage = (event) => {
              if (String(event.data).includes('STRATEGY_POSITION_TRACKING')) {
                finish({ ok: true, data: String(event.data) });
              }
            };
            """.trimIndent(),
            accessToken,
            timeout.toMillis(),
        ) as Map<String, Any?>

        assertEquals(true, response["ok"], "Browser WebSocket tracking subscription failed: ${response["message"]}")
        return response["data"] as String
    }

    private fun waitUntil(timeout: Duration, label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + timeout.toNanos()
        var lastError: Throwable? = null
        while (System.nanoTime() < deadline) {
            try {
                if (predicate()) return
            } catch (t: Throwable) {
                lastError = t
            }
            Thread.sleep(250)
        }
        lastError?.let { throw AssertionError("Timed out waiting for $label", it) }
        throw AssertionError("Timed out waiting for $label")
    }

    private fun env(name: String, default: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: default
}
