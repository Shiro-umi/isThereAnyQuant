package ktor.module.routing

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import org.jetbrains.kotlinx.dataframe.api.dataFrameOf
import org.jetbrains.kotlinx.kandy.dsl.plot
import org.jetbrains.kotlinx.kandy.letsplot.export.toPNG
import org.jetbrains.kotlinx.kandy.letsplot.feature.layout
import org.jetbrains.kotlinx.kandy.letsplot.layers.line
import org.jetbrains.kotlinx.kandy.util.color.Color
import org.jetbrains.kotlinx.kandy.util.context.invoke
import org.jetbrains.kotlinx.statistics.kandy.layers.candlestick
import org.shiroumi.database.old.functioncalling.getJoinedCandles
import org.shiroumi.server.today
import kotlin.text.substring

fun Route.candleImg(route: String) = get(route) {
    val tsCode = call.queryParameters["ts_code"] ?: return@get call.respond(
        HttpStatusCode.BadRequest,
        "task uuid can't be null or blank"
    )
    val tradeDate = call.queryParameters["date"] ?: today
    val bgColor = call.queryParameters["bg_color"]?.substring(2) ?: "ffffff"
    val primary = call.queryParameters["primary"]?.substring(2) ?: "ffffff"
    val secondary = call.queryParameters["secondary"]?.substring(2) ?: "000000"
    val candles = getJoinedCandles(tsCode, 60, endDate = tradeDate).res
    val df = dataFrameOf(
        "date" to candles.map { it.date },
        "open" to candles.map { it.open },
        "high" to candles.map { it.high },
        "low" to candles.map { it.low },
        "close" to candles.map { it.close },
//        "ema20" to candles.map { it.ema20 }
    )
    val png = df.plot {
        layout.style {
            plotCanvas.background {
                fillColor = Color.hex("#$bgColor")
                borderLineColor = Color.hex("#$bgColor")
            }
            blankAxes()
            global {
                this@global.line {
                    this@line.blank = true
                }
            }
        }
        layout.size = 1400 to 700
        candlestick("date", "open", "high", "low", "close") {
            increase {
                fillColor = Color.hex("#$primary")
                alpha = 0.0
                borderLine.color = Color.hex("#$primary")
                borderLine.width = 1.0
            }

            decrease {
                fillColor = Color.hex("#$secondary")
                borderLine.color = Color.hex("#$secondary")
            }
        }
        line {
            x(this@plot["date"])
            y(this@plot["ema20"])
            color = Color.WHITE
        }
    }.toPNG()
    call.respondBytes(png, ContentType.Image.PNG)
}