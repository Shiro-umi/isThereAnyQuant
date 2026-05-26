package org.shiroumi.server.e2e

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.openqa.selenium.By
import org.openqa.selenium.Dimension
import org.openqa.selenium.OutputType
import org.openqa.selenium.WebDriver
import org.openqa.selenium.WebDriverException
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.interactions.Actions
import org.openqa.selenium.logging.LogType
import org.openqa.selenium.logging.LoggingPreferences
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.logging.Level
import kotlin.io.path.createDirectories
import kotlin.math.max

/**
 * Manual visual inspection helper for the deployed Compose Web strategy tracking flow.
 *
 * Compose Web has no stable business DOM nodes. This test uses Chrome as a real user surface,
 * drives navigation through canvas coordinates, and writes full-window screenshots for inspection.
 */
@Tag("e2e")
@EnabledIfEnvironmentVariable(named = "QUANT_VISUAL_E2E_ENABLED", matches = "true")
class StrategyServiceVisualInspectionTest {
    private val baseUrl = env("QUANT_E2E_BASE_URL", "http://localhost:9871")
    private val strategyHost = env("QUANT_E2E_STRATEGY_HOST", "127.0.0.1")
    private val strategyPort = env("QUANT_E2E_STRATEGY_PORT", "9971").toInt()
    private val username = env("QUANT_E2E_USERNAME", "shiroumi")
    private val password = System.getenv("QUANT_E2E_PASSWORD").orEmpty()
    private val screenshotDir = Path.of(
        env(
            "QUANT_VISUAL_SCREENSHOT_DIR",
            Path.of(System.getProperty("user.dir")).resolve("build/e2e-screenshots").toString(),
        )
    )
    private val json = Json { ignoreUnknownKeys = true }
    private var driver: ChromeDriver? = null

    @AfterEach
    fun tearDown() {
        driver?.quit()
        driver = null
    }

    @Test
    fun `full screen visual inspection of deployed strategy tracking page`() {
        assumeTrue(password.isNotBlank(), "Set QUANT_E2E_PASSWORD for the deployed login account")
        assertStrategyServiceSocketReachable()
        screenshotDir.createDirectories()

        val browser = newChromeDriver()
        driver = browser
        browser.manage().timeouts().implicitlyWait(Duration.ofMillis(300))
        browser.manage().timeouts().scriptTimeout(Duration.ofSeconds(70))
        fillAvailableScreen(browser)

        browser.get(baseUrl)
        waitUntil(Duration.ofSeconds(30), "Compose canvas is ready") {
            browser.findElements(By.id("compose-root")).firstOrNull()?.let {
                it.size.width > 0 && it.size.height > 0
            } == true
        }
        waitForLoadingOverlayToLeave(browser)
        printPageDiagnostics(browser, "initial-page")
        saveScreenshot(browser, "01-login-or-loading.png")

        loginThroughComposeCanvas(browser)
        val accessToken = refreshAccessTokenFromBrowserContext(browser)
        waitUntil(Duration.ofSeconds(30), "authenticated Compose canvas is ready") {
            browser.findElements(By.id("compose-root")).firstOrNull()?.let {
                it.size.width > 0 && it.size.height > 0
            } == true
        }
        waitForLoadingOverlayToLeave(browser)
        printPageDiagnostics(browser, "authenticated-home")
        saveScreenshot(browser, "02-authenticated-home.png")

        val canvas = browser.findElement(By.id("compose-root"))
        openPositionTrackingThroughCanvas(browser, canvas)
        Thread.sleep(2_000)
        printPageDiagnostics(browser, "strategy-position-tracking")
        val trackingPath = saveScreenshot(browser, "03-strategy-position-tracking.png")

        val wsFrame = subscribeTrackingFromBrowserContext(browser, accessToken)
        assertTrue(
            wsFrame.contains("\"topic\":\"STRATEGY_POSITION_TRACKING\""),
            "Expected strategy tracking websocket frame, got=$wsFrame"
        )
        println("VISUAL_SCREENSHOT=$trackingPath")
    }

    private fun newChromeDriver(): ChromeDriver {
        val logs = LoggingPreferences().apply {
            enable(LogType.BROWSER, Level.ALL)
        }
        val options = ChromeOptions().apply {
            addArguments("--start-maximized")
            addArguments("--window-size=1920,1080")
            addArguments("--lang=zh-CN")
            setCapability("goog:loggingPrefs", logs)
        }
        return ChromeDriver(options)
    }

    private fun fillAvailableScreen(browser: ChromeDriver) {
        runCatching { browser.manage().window().fullscreen() }
            .recoverCatching { browser.manage().window().maximize() }
            .onFailure { error ->
                if (error !is WebDriverException) throw error
            }

        val size = browser.manage().window().size
        if (size.width < 1200 || size.height < 700) {
            browser.manage().window().size = Dimension(1920, 1080)
        }
    }

    private fun assertStrategyServiceSocketReachable() {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(strategyHost, strategyPort), 2_000)
        }
    }

    private fun loginThroughComposeCanvas(browser: WebDriver) {
        val surface = browser.findElement(By.id("compose-root"))
        val width = surface.size.width
        val height = surface.size.height
        clickCanvasPoint(browser, surface, (width * 0.61).toInt(), (height * 0.435).toInt())
        Actions(browser).sendKeys(username).perform()
        clickCanvasPoint(browser, surface, (width * 0.61).toInt(), (height * 0.545).toInt())
        Actions(browser).sendKeys(password).perform()
        clickCanvasPoint(browser, surface, (width * 0.61).toInt(), (height * 0.66).toInt())
        Thread.sleep(1_500)
    }

    private fun refreshAccessTokenFromBrowserContext(browser: ChromeDriver): String {
        @Suppress("UNCHECKED_CAST")
        val response = browser.executeAsyncScript(
            """
            const done = arguments[arguments.length - 1];
            fetch('/api/auth/refresh', {
              method: 'POST',
              credentials: 'include',
              headers: { 'Content-Type': 'application/json' },
            }).then(async response => {
              done({ status: response.status, body: await response.text() });
            }).catch(error => {
              done({ status: 0, body: String(error) });
            });
            """.trimIndent()
        ) as Map<String, Any?>

        assertEquals(200L, response["status"], "Refresh token cookie should exist after canvas login: ${response["body"]}")
        val body = response["body"] as String
        return json.parseToJsonElement(body).jsonObject["accessToken"]?.jsonPrimitive?.content
            ?: error("Refresh response does not contain accessToken: $body")
    }

    private fun openPositionTrackingThroughCanvas(browser: WebDriver, canvas: org.openqa.selenium.WebElement) {
        val width = canvas.size.width
        val height = canvas.size.height
        val trackingX = max(36, (width * 0.03).toInt())
        val trackingY = (height * 0.24).toInt()
        Actions(browser)
            .moveToElement(canvas, trackingX - width / 2, trackingY - height / 2)
            .click()
            .perform()
    }

    private fun clickCanvasPoint(browser: WebDriver, surface: org.openqa.selenium.WebElement, x: Int, y: Int) {
        Actions(browser)
            .moveToElement(surface, x - surface.size.width / 2, y - surface.size.height / 2)
            .click()
            .perform()
    }

    private fun subscribeTrackingFromBrowserContext(browser: ChromeDriver, accessToken: String): String {
        @Suppress("UNCHECKED_CAST")
        val response = browser.executeAsyncScript(
            """
            const token = arguments[0];
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
            const timer = setTimeout(() => finish({ ok: false, message: 'timeout waiting for tracking frame' }), 45000);
            const socket = new WebSocket(wsUrl.toString());
            socket.onopen = () => socket.send(JSON.stringify({ command: 'SUBSCRIBE', targetId: 'STRATEGY_POSITION_TRACKING' }));
            socket.onerror = () => finish({ ok: false, message: 'websocket error' });
            socket.onmessage = (event) => {
              if (String(event.data).includes('STRATEGY_POSITION_TRACKING')) {
                finish({ ok: true, data: String(event.data) });
              }
            };
            """.trimIndent(),
            accessToken,
        ) as Map<String, Any?>
        assertEquals(true, response["ok"], "Tracking websocket subscription failed: ${response["message"]}")
        return response["data"] as String
    }

    private fun saveScreenshot(browser: ChromeDriver, name: String): Path {
        val path = screenshotDir.resolve(name).toAbsolutePath()
        Files.write(path, browser.getScreenshotAs(OutputType.BYTES))
        println("VISUAL_SCREENSHOT=$path")
        return path
    }

    private fun waitForLoadingOverlayToLeave(browser: ChromeDriver) {
        runCatching {
            waitUntil(Duration.ofSeconds(25), "HTML loading overlay to leave") {
                browser.executeScript("return !document.getElementById('app-loading')") == true
            }
        }
    }

    private fun printPageDiagnostics(browser: ChromeDriver, label: String) {
        val pageState = browser.executeScript(
            """
            const root = document.getElementById('compose-root');
            return {
              label: arguments[0],
              href: location.href,
              readyState: document.readyState,
              loadingPresent: !!document.getElementById('app-loading'),
              rootTag: root ? root.tagName : null,
              rootChildren: root ? root.children.length : -1,
              rootText: root ? root.innerText : null,
              bodyText: document.body.innerText.slice(0, 800),
              scripts: Array.from(document.scripts).map(s => ({ src: s.src, type: s.type })),
              failedResources: performance.getEntriesByType('resource')
                .filter(e => e.transferSize === 0 && !String(e.name).includes('favicon'))
                .map(e => e.name).slice(0, 20)
            };
            """.trimIndent(),
            label,
        )
        println("VISUAL_PAGE_STATE=$pageState")
        browser.manage().logs().get(LogType.BROWSER).forEach {
            println("VISUAL_BROWSER_LOG=${it.level}:${redactSensitive(it.message)}")
        }
    }

    private fun redactSensitive(message: String): String = message
        .replace(Regex("Bearer\\s+[A-Za-z0-9_.-]+"), "Bearer ***")
        .replace(Regex("\\\"accessToken\\\"\\s*:\\s*\\\"[^\\\"]+\\\""), "\"accessToken\":\"***\"")
        .replace(Regex("\\\"refreshToken\\\"\\s*:\\s*\\\"[^\\\"]+\\\""), "\"refreshToken\":\"***\"")
        .replace(Regex("\\\"password\\\"\\s*:\\s*\\\"[^\\\"]+\\\""), "\"password\":\"***\"")
        .replace(Regex("""\\"accessToken\\"\s*:\s*\\"[^\\"]+\\""""), """\"accessToken\": \"***\"""")
        .replace(Regex("""\\"refreshToken\\"\s*:\s*\\"[^\\"]+\\""""), """\"refreshToken\": \"***\"""")
        .replace(Regex("""\\"password\\"\s*:\s*\\"[^\\"]+\\""""), """\"password\": \"***\"""")
        .replace(Regex("eyJ[A-Za-z0-9_.-]+"), "***")

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
