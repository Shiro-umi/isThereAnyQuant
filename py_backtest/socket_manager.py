import asyncio
import json
from asyncio import StreamWriter, StreamReader, wait_for

from py_backtest.looper import SenderLooper, ReceiverLooper, WorkLooper
from py_backtest.log import logger


class SocketManager:
    def __init__(self, host, port, on_receive, shutdown_event: asyncio.Event):
        self._future: asyncio.Future | None = None
        self._worker: WorkLooper | None = None
        self._sender: SenderLooper | None = None
        self._receiver: ReceiverLooper | None = None
        self._host = host
        self._port = port
        self._on_receive = on_receive
        self._reader: StreamReader | None = None
        self._writer: StreamWriter | None = None
        self._query_queue = asyncio.Queue()
        self._tasks = []
        self._future_event: asyncio.Event | None = None
        self._shutdown_event = shutdown_event

    async def connect(self, on_connected):
        logger.info(f"connecting {self._host}:{self._port}...")
        self._reader, self._writer = await asyncio.open_connection(self._host, self._port)
        logger.info(f"connected.")
        self._sender = SenderLooper(handler=self._handle_send, shutdown_event=self._shutdown_event)
        self._receiver = ReceiverLooper(producer=self._reader, handler=self._handle_receive,
                                        shutdown_event=self._shutdown_event)
        self._worker = WorkLooper(handler=self._handle_work, shutdown_event=self._shutdown_event)
        await on_connected()
        await self._start_looper()

    async def _start_looper(self):
        sender_task = asyncio.create_task(self._sender.start_looper(), name="_sender_looper")
        self._tasks.append(sender_task)
        receiver_task = asyncio.create_task(self._receiver.start_looper(), name="_receiver_looper")
        self._tasks.append(receiver_task)
        worker_task = asyncio.create_task(self._worker.start_looper(), name="_worker_looper")
        self._tasks.append(worker_task)
        await asyncio.gather(*self._tasks)

    async def close(self):
        if self._writer.is_closing():
            return

        self._writer.close()
        await self._writer.wait_closed()
        self._writer = None
        self._reader = None
        for task in self._tasks:
            if not task.done():
                task.cancel()
        results = await asyncio.gather(*self._tasks, return_exceptions=True)
        for i, result in enumerate(results):
            task_name = self._tasks[i].get_name()
            if isinstance(result, asyncio.CancelledError):
                logger.info(f"{task_name} cancelled")
            elif isinstance(result, Exception):
                logger.error(f"{task_name} exception occurs during cancellation: {result}")
        self._tasks = []  # 清空任务列表
        logger.info("socket closed.")
        self._shutdown_event.set()

    async def _handle_send(self, msg_bytes):
        # if not self._future_event:
        #     self._writer.write(msg_bytes)
        #     return
        # await self._future_event.wait()
        self._writer.write(msg_bytes)

    async def _handle_receive(self, payload):
        if not payload:
            print("payload" + payload)
            return await self.close()
        try:
            msg_dict = json.loads(payload)
            if self._query_queue.empty():  # schedule
                logger.debug(f'receiver: send data to worker_looper, data: {msg_dict}')
                await self._worker.emit(msg_dict)
            else:  # query
                logger.debug(f'receiver: set data to future, data: {msg_dict}')
                future: asyncio.Future = await self._query_queue.get()
                future.set_result(msg_dict)
        except json.JSONDecodeError:
            logger.warning(f"receiver: error occurs during json decoding, {payload}")

    async def _handle_work(self, payload):
        logger.debug(f'work_looper: handle {payload}')
        await self._on_receive(payload)

    async def reply(self, msg):
        await self._sender.emit(msg)

    async def query(self, msg, timeout):
        future = asyncio.get_event_loop().create_future()
        await self._query_queue.put(future)  # create a future
        await self._sender.emit_now(msg)
        self._future_event = asyncio.Event()

        try:
            # wait future response
            resp = await asyncio.wait_for(future, timeout=timeout)
            self._future_event.set()
            self._future_event = None
            return resp
        except asyncio.TimeoutError:
            logger.warning(f"query_msg: timeout.")
