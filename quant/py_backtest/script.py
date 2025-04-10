def init(context):
    context.reply("status.client.standby",
                  ["trading.server.do_at_9_25", "trading.server.do_at_9_30"])


def trading_server_do_at_9_25(context, cmd, params):
    print("[async] do at 9_25")
    account = context.query("trading.client.account")
    print(f"[sync] account: {account}")
    context.reply("status.client.next")


def trading_server_do_at_9_30(context, cmd, params):
    print("[async] do at 9_30")
    context.reply("status.client.next")

