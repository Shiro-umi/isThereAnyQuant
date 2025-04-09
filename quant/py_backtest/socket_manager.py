import socket
import queue
import threading
import json


class SocketManager:
    def __init__(self, host, port, on_receive):
        self.on_receive = on_receive
        self.host = host
        self.port = port
        self.sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self.sock.connect((self.host, self.port))

        # threading queue
        self.send_queue = queue.Queue()
        self.result_queue = queue.Queue()
        self.running = True

        # threading sync
        self.lock = threading.Lock()
        self.condition = threading.Condition()

        # threading
        self.receiving_thread = threading.Thread(target=self._receiving_looper)
        self.sending_thread = threading.Thread(target=self._sending_looper)
        self.receiving_thread.start()
        self.sending_thread.start()

    # looper for send msg
    def _sending_looper(self):
        while self.running:
            try:
                data = self.send_queue.get(timeout=0.5)
                with self.lock:
                    self.sock.send(f"{json.dumps(data)}\n".encode('utf-8'))

                with self.condition:
                    self.condition.wait()
            except queue.Empty:
                continue
            except Exception as e:
                print(f"exception occurred during sending msg: {str(e)}")
                self.running = False

    # looper for receive msg
    def _receiving_looper(self):
        while self.running:
            try:
                data = self.sock.recv(1024).decode('utf-8')
                if not data:
                    break

                self.result_queue.put(json.loads(data))
                with self.condition:
                    self.condition.notify_all()
            except ConnectionResetError:
                print("connection reset.")
                self.running = False
            except Exception as e:
                print(f"exception occurred during receiving msg:: {str(e)}")
                self.running = False

    def reply(self, cmd, params, timeout=5):
        with self.condition:
            self.send_queue.put({"cmd": cmd, "params": params})
            self.condition.wait(timeout)
            json_data = self.result_queue.get()
            cmd = json_data['cmd']
            params = json_data.get('params', None)
            print(f"call_from_remote, cmd: {cmd}, params: {params}")
            self.on_receive(cmd, params)
            # getattr(self.script, cmd.replace('.', '_'))(self, cmd, params)

    def query(self, cmd, params, timeout=5):
        with self.condition:
            self.send_queue.put({"cmd": cmd, "params": params})
            self.condition.wait(timeout)
            return self.result_queue.get()

    def shutdown(self):
        self.running = False
        self.sending_thread.join()
        self.receiving_thread.join()
        self.sock.close()
