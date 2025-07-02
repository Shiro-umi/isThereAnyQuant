import asyncio
import time
from context import Context

from py_backtest.log import logger
from py_backtest.socket_manager import SocketManager

if __name__ == '__main__':
    import script
    _shutdown_event = asyncio.Event()
    context = Context(script)
    sm = SocketManager('127.0.0.1', 6332, context.on_receive, _shutdown_event)
    context.set_socket_manager(sm)
    asyncio.run(sm.connect(lambda : script.init(context)))
    while not _shutdown_event.is_set():
        time.sleep(1)
    logger.error(f"main process exit.")
