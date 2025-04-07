socket = None
account = None

def init(s):
    socket = s
    # account = socket.call_remote_sync("trading.client.account", ["trading.client.account"])
    # print(f"[sync] account: {account}")
    socket.call_remote("status.client.standby", ["trading.server.do_at_9_25"])

def status_client_standby():
    print(f"[async] startup {socket}")

def do_at_9_25():
    print("[async] do at 9_25")