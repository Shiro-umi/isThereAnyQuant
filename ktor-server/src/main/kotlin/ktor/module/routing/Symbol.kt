package ktor.module.routing

import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import model.Candle
import model.symbol.Wave
import org.shiroumi.database.functioncalling.getRandomCandleSeq
import org.shiroumi.database.functioncalling.insertSymbolized

fun Route.symbol(route: String) = get(route) {
    val candles = getRandomCandleSeq(60)
        .mapIndexed { _, c ->
            Candle(
                date = c.date,
                open = c.open,
                close = c.close,
                low = c.low,
                high = c.high,
                vol = c.vol
            )
        }
    val json = Json.encodeToString(candles)
    call.respond(
        HttpStatusCode.OK,
        json
    )
}

@Serializable
data class SymbolSubmitRequest(
    val candles: String,
    val symbols: String
)

fun Route.symbolSubmit(route: String) = post(route) {
    try {
        val jsonBody = call.receiveText()
        val request = Json.decodeFromString<SymbolSubmitRequest>(jsonBody)

        val candles = Json.decodeFromString<List<Candle>>(request.candles)
        val waves = Json.decodeFromString<List<Wave>>(request.symbols)

        val result = insertSymbolized(waves, candles)

        call.respond(
            HttpStatusCode.OK,
            mapOf("message" to "Symbols saved", "id" to result)
        )
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.BadRequest,
            mapOf("error" to (e.message ?: "Invalid request"))
        )
    }
}
