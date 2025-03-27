import os
import socket as pysocket
import sys
import threading
import time
import json

protocol = None


class SocketManager:
    event: threading.Event | None = None

    def __init__(self):
        self.socket = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
        try:
            self.socket.connect(('127.0.0.1', int(sys.argv[1])))
            print(f"server connected f{('127.0.0.1', int(sys.argv[1]))}")
            t_looper = threading.Thread(target=self.looper)
            t_looper.daemon = True
            t_looper.start()
            while True:
                if not t_looper.is_alive():
                    break
                time.sleep(1)
        finally:
            self.socket.close()
            sys.exit(0)

    def looper(self):
        while True:
            data = self.socket.recv(1024).decode('utf-8')
            print(f"\n response from server: {data}")
            if not self.event:
                json_data = json.loads(data)
                cmd = json_data['cmd']
                params = json_data['params']
                getattr(test, cmd.replace('.', '_'))(cmd, params)
                pass
            else:
                setattr(self.event, "result", data)
                self.event.set()

    def call_remote(self, cmd, params="{}") -> str:
        self.event = threading.Event()
        self.socket.send(json.dumps({"cmd": cmd, "params": params}).encode('utf-8'))
        self.event.wait()
        result = getattr(self.event, "result")
        self.event = None
        return result

    def call_remote_async(self, cmd, params):
        self.socket.send(json.dumps({"cmd": cmd, "params": params}).encode('utf-8'))


class Protocol:
    def __init__(self):
        self.sm = SocketManager()
        self.sm.call_remote_async(
            cmd = "status.client.standby",
            params = test.init(protocol)
        )


if __name__ == "__main__":
    import test

    Protocol()
