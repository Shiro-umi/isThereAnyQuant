from log import logger

async def init(context):
    logger.info("script init")
    await context.reply("status.client.standby",
                  ["trading.server.do_at_9_25", "trading.server.do_at_9_30"])


async def trading_server_do_at_9_25(context, cmd, params):
    print("[async] do at 9_25")
    account = await context.query("trading.client.account")
    print(f"[sync] account: {account}")
    await context.reply("status.client.next")


async def trading_server_do_at_9_30(context, cmd, params):
    print("[async] do at 9_30")
    await context.reply("status.client.next")

