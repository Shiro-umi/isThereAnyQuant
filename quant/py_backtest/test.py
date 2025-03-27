protocol = None
account = None


def init(proto):
    protocol = proto
    account = protocol.call_remote("trading.client.account")
    print(f"[sync] account: {account}")
    return ["trading.client.account"]


def trading_client_account_callback(account_info):
    print(f"[async] account: {account_info}")
