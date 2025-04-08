def init(context):
    # account = socket.call_remote_sync("trading.client.account", ["trading.client.account"])
    # print(f"[sync] account: {account}")
    context.call_remote("status.client.standby",
                        ["trading.server.do_at_9_25", "trading.server.do_at_9_30"])


def trading_server_do_at_9_25(context, cmd, params):
    print("[async] do at 9_25")
    account = context.call_remote_sync("trading.client.account", "")
    print(f"[sync] account: {account}")
    context.call_remote("status.client.next", "")

def trading_server_do_at_9_30(context, cmd, params):
    print("[async] do at 9_30")
    context.call_remote("status.client.next", "")
