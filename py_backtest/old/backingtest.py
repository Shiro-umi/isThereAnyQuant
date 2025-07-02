from py_backtest.ctx import Context
from socket_manager import SocketManager
import time
import asyncio
from py_backtest.log import logger


async def on_connected_callback(_context, _sm):
    logger.info("on_connected_callback")
    _context.set_socket_manager(_sm)
    await script.init(_context)


# --- 主执行逻辑 ---
async def run_main_logic(ctx, sm):
    # def signal_handler(sig, frame):
    #     print ('\nSignal Catched! You have just type Ctrl+C!')
    #     asyncio.run(sm.close())
    # signal.signal(signal.SIGINT, signal_handler)

    await sm.start()  # sm.start() 启动连接和后台循环
    while not sm.shutdown_event.is_set():
        logger.error(f"{sm.shutdown_event.is_set()}")
        if not sm.shutdown_event.is_set():
            time.sleep(1)


if __name__ == '__main__':
    from py_backtest import script

    context = Context(script)
    sm = SocketManager('127.0.0.1', 6332, context.on_receive, context, on_connected_callback)
    asyncio.run(run_main_logic(context, sm))
    while not sm.shutdown_event.is_set():
        logger.error(f"{sm.shutdown_event.is_set()}")
        if not sm.shutdown_event.is_set():
            time.sleep(1)
