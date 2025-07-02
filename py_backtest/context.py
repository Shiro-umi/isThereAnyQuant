from socket_manager import SocketManager


class Context:
    def __init__(self, script):
        self._sm: SocketManager | None = None
        self._script = script

    def set_socket_manager(self, socket_manager: SocketManager):
        self._sm = socket_manager

    async def reply(self, cmd, params=''):
        msg = {'cmd': cmd, 'params': params}
        await self._sm.reply(msg)

    async def query(self, cmd, params='', timeout=3):
        msg = {'cmd': cmd, 'params': params}
        return (await self._sm.query(msg, timeout)).get('params')

    async def on_receive(self, payload:dict):
        mtd = payload['cmd'].replace('.', '_')
        if hasattr(self._script, mtd):
            await (getattr(self._script, mtd)(self, payload['cmd'], payload.get('params')))
