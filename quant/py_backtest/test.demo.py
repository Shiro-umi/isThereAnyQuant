import json
import queue
import socket as pysocket
import threading
import time
from time import sleep
import sys

class ReceiveLooper:

    def __init__(self, socket: pysocket, threading_event_provider):
        print(threading_event_provider)
        self.threading_event_provider = threading_event_provider()
        self.socket = socket
        thread = threading.Thread(target=self.looper)
        thread.daemon = True
        thread.start()
        print("receiving thread started")

    def looper(self):
        print("receiver looper started")
        while 1:
            print("loop")
            data = self.socket.recv(1024).decode('utf-8')
            print(f"received: {data}")
            if data == 'error! \n':
                sys.exit(0)

            self.on_receive_data(data)

    def on_receive_data(self, data):
        print("on_receive_data: " + str(time.time()))
        event = None
        if self.threading_event_provider:
            event = self.threading_event_provider()
        print(f"event: {event}")
        if event:
            print(event)
            setattr(event, "result", data)
            event.set()
        else:
            try:
                json_data = json.loads(data)
                cmd = json_data['cmd']
                params = json_data.get('params', None)
                print(f"call_from_remote, cmd: {cmd}, params: {params}")
                getattr(test, cmd.replace('.', '_'))(sm, cmd, params)
            except Exception as e:
                print(e)


class SendLooper:
    def __init__(self, socket: pysocket):
        self._queue = queue.Queue()
        self._socket = socket
        thread = threading.Thread(target=self.looper)
        thread.daemon = True
        thread.start()

    def looper(self):
        while 1:
            data = self._queue.get()
            print(f"send: {data}")
            if data == "\n":
                sys.exit(0)
            self._socket.send(f"{data}\n".encode('utf-8'))

    def send(self, data):
        self._queue.put(data)


class SocketManager:
    event: threading.Event | None = None

    def __init__(self):
        self.socket = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
        # self.socket.connect(('127.0.0.1', int(sys.argv[1])))
        self.socket.connect(('127.0.0.1', 6332))
        self.send_looper = SendLooper(self.socket)
        self.receive_looper = ReceiveLooper(self.socket, lambda: self.event)


    def call_remote(self, cmd, params):
        data = json.dumps({"cmd": cmd, "params": params})
        print(f"call_remote, data: {data}")
        self.send_looper.send(data)

    def call_remote_sync(self, cmd, params):
        self.event = threading.Event()
        data = json.dumps({"cmd": cmd, "params": params})
        self.send_looper.send(data)
        self.event.wait()
        result = getattr(self.event, "result")
        self.event = None
        return result

sm = SocketManager()

if __name__ == "__main__":
    import test
    print("started")
    test.init(sm)
    while 1:
        # sm.call_remote("test", "test")
        sleep(1)

