from socket_manager import SocketManager
from log import logger

class Context:
    def __init__(self, script):
        self._sm: SocketManager | None = None
        self._script = script

    def set_socket_manager(self, socket_manager: SocketManager):
        self._sm = socket_manager

    async def reply(self, cmd, params=''):
        logger.info("context reply")
        msg = {'cmd': cmd, 'params': params}
        await self._sm.reply(msg)

    async def query(self, cmd, params='', timeout=10):
        msg = {'cmd': cmd, 'params': params}
        return (await self._sm.query(msg, timeout))['params']

    async def on_receive(self, cmd, params=''):
        await (getattr(self._script, cmd.replace('.', '_'))(self, cmd, params))
