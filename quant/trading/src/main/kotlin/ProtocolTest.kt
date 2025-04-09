import org.shiroumi.trading.socket.ProtocolSocketManager

fun main(args: Array<String>) {
    ProtocolSocketManager().bindToPort(6332)
}
