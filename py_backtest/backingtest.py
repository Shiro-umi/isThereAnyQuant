from time import sleep

from ctx import Context
from socket_manager import SocketManager
import time
import asyncio
from log import logger



async def on_connected_callback(_context, _sm):
    logger.info("on_connected_callback")
    _context.set_socket_manager(_sm)
    await script.init(_context)


# --- 主执行逻辑 ---
async def run_main_logic():
    context = Context(script)

    sm = SocketManager('127.0.0.1', 6332, context.on_receive, context, on_connected_callback)

    await sm.start()  # sm.start() 启动连接和后台循环
    while not sm.shutdown_event.is_set():
        time.sleep(1)


if __name__ == '__main__':
    import script

    # context = Context(script)
    # async def on_connected(sm: SocketManager):
    #     context.set_socket_manager(sm)
    #     await script.init(context)
    # sm = SocketManager('127.0.0.1', 6332, context.on_receive, on_connected)
    asyncio.run(run_main_logic())

    while True:
        time.sleep(1)
