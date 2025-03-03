package org.shiroumi.trading


object BackTesting {
//    private val client = HttpClient(CIO) {
//        install(WebSockets)
//    }
//
//    suspend fun test(a: Int, b: Int, port: Int): String {
//        println("Starting test")
//        return suspendCoroutine { continuation ->
//            supervisorScope.launch {
//
//                // todo start a py-socket-server on port
//
//                client.webSocket(method = HttpMethod.Get, host = "localhost", path = "/test", port = port) {
//                    send("action://do_at_${a}_${b}")
//                    val response = incoming.receive() as? Frame.Text
//                    val res = response?.readText() ?: throw RuntimeException("Unexpected response")
//                    continuation.resume(res)
//                }
//            }
//
//        }
//    }
}