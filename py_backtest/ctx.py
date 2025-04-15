from socket_manager import SocketManager
import threading

class Context:
    def __init__(self, script):
        self._sm = None
        self._script = script

    def set_socket_manager(self, socket_manager: SocketManager):
        self._sm = socket_manager

    def reply(self, cmd, params = '', timeout=5):
        self._sm.reply(cmd, params, timeout)

    def query(self, cmd, params = '', timeout=5):
        return self._sm.query(cmd, params, timeout)

    def on_receive(self, cmd, params = ''):
        getattr(self._script, cmd.replace('.', '_'))(self, cmd, params)

    def start(self):
        threading.Thread(target = lambda : self._script.init(self)).start()