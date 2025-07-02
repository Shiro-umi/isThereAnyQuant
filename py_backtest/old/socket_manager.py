import asyncio
from asyncio import CancelledError
import json

from py_backtest.log import logger

class SocketManager:
    def __init__(self, host, port, msg_handler, context, on_connected):
        self._host = host
        self._port = port
        self._msg_handler = msg_handler
        self._context = context
        self._on_connected = on_connected
        self._send_queue = asyncio.Queue()  # send queue
        self._script_queue = asyncio.Queue()  # script queue
        self._future_queue = asyncio.Queue() # query future queue
        self._reader = None
        self._writer = None
        self._tasks = []
        self.shutdown_event = asyncio.Event()

    async def _connect(self):
        try:
            logger.info(f"connecting {self._host}:{self._port}...")
            self._reader, self._writer = await asyncio.open_connection(self._host, self._port)
            logger.info(f"connected. reader: {self._reader}, writer: {self._writer}")
            self._tasks.append(asyncio.create_task(self._sender_looper(), name="_sender_looper"))
            self._tasks.append(asyncio.create_task(self._receiver_looper(), name="_receiver_looper"))
            self._tasks.append(asyncio.create_task(self._script_looper(), name="_script_looper"))
            await self._on_connected(self._context, self)
            for task in self._tasks:
                await task
        except ConnectionRefusedError:
            logger.error(f"connection refused")
            raise
        except Exception as e:
            logger.error(f"connection failed: {e}")
            raise

    async def close(self):
        logger.info("closing...")
        if self._writer:
            try:
                if not self._writer.is_closing():
                    self._writer.close()
                    await self._writer.wait_closed()
                logger.info("exit writer.")
            except Exception as e:
                logger.error(f"exception occurs when closing writer: {e}")
        self._writer = None
        self._reader = None
        # cancel all tasks
        for task in self._tasks:
            if not task.done():
                task.cancel()
        results = await asyncio.gather(*self._tasks, return_exceptions=True)
        for i, result in enumerate(results):
            task_name = self._tasks[i].get_name()  # 获取任务名称
            if isinstance(result, asyncio.CancelledError):
                logger.info(f"{task_name} cancelled")
            elif isinstance(result, Exception):
                logger.error(f"{task_name} exception occurs during cancellation: {result}")
        self._tasks = []  # 清空任务列表
        logger.info("socket closed.")
        self.shutdown_event.set()  # notify loopers shutdown

    async def _handle_disconnect(self):
        logger.warning("connection lost, cleaning up...")
        await self.close()

    async def _sender_looper(self):
        logger.info("_sender: sender_looper started")
        try:
            while True:
                try:
                    # get msg_to_send from send_queue. check _shutdown_event every 1 sec.
                    message_dict = await asyncio.wait_for(self._send_queue.get(), timeout=1.0)
                    if not self._writer:
                        logger.error("sender: not connected, drop")
                        self._send_queue.task_done()
                        continue

                    msg = json.dumps(message_dict)
                    msg_bytes = (msg + '\n').encode('utf-8')

                    # logger.info(f"sender: sending msg {msg.strip()}")
                    self._writer.write(msg_bytes)
                    await self._writer.drain()  # wait for writer clear
                    self._send_queue.task_done()
                except asyncio.TimeoutError:
                    continue  # check _shutdown_event
                except (ConnectionError, BrokenPipeError, ConnectionResetError) as e:
                    logger.error(f"sender: error occurs, stoping.. \\n {e}")
                    await self._handle_disconnect()
                    break
                except Exception as e:
                    logger.error(f"sender: unexpected error occurs：{e}", exc_info=True)
                    await asyncio.sleep(0.1)
        except CancelledError:
            logger.warning("_sender_looper cancelled")
        finally:
            logger.warning("_sender_looper closed")

    async def _receiver_looper(self):
        logger.info("_receiver_looper started")
        try:
            while True:
                if not self._reader:
                    if self.shutdown_event.is_set(): break
                    await asyncio.sleep(0.5)
                    continue
                try:
                    # read msg from _reader. check _shutdown_event every 1 sec.
                    line_bytes = await asyncio.wait_for(self._reader.readline(), timeout=1.0)
                    # logger.warning("_receiver_looper read_lines")

                    if not line_bytes:  # EOF
                        logger.info("receiver: connection closed by remote.")
                        await self._handle_disconnect()
                        break

                    line_json = line_bytes.decode('utf-8').strip()
                    logger.info(f"receiver: msg received {line_json}")

                    try:
                        message_dict = json.loads(line_json)
                    except json.JSONDecodeError:
                        logger.warning(f"receiver: error occurs during json decoding, {line_json}")
                        continue

                    if not self._future_queue.empty():
                        future: asyncio.Future = await self._future_queue.get()
                        future.set_result(message_dict)
                    else :
                        # script working msg queue
                        await self._script_queue.put(message_dict)

                except asyncio.TimeoutError:
                    continue
                except (ConnectionError, asyncio.IncompleteReadError, BrokenPipeError, ConnectionResetError) as e:
                    logger.error(f"receiver: connection error occurs {e}")
                    await self._handle_disconnect()
                    break
                except Exception as e:
                    logger.error(f"receiver: unexpected error occurs：{e}", exc_info=True)
                    await self._handle_disconnect()
                    await asyncio.sleep(0.1)
        except asyncio.CancelledError:
            logger.info("receiver: looper cancelled.")
        finally:
            logger.info("receiver: looper stoped")

    async def _script_looper(self):
        try:
            while True:
                try:
                    msg_dict = await asyncio.wait_for(self._script_queue.get(), timeout=1.0)
                    # logger.info(f"msg_handler：received {msg_dict}")
                    try:
                        await self._msg_handler(msg_dict['cmd'], msg_dict.get('params', ''))
                    except Exception as e:
                        logger.error(f"msg_handler: error occurs during handling msg {msg_dict}", exc_info=True)
                    finally:
                        self._script_queue.task_done()
                except asyncio.TimeoutError:
                    continue
        except asyncio.CancelledError:
            logger.info("msg_handler: looper cancelled.")
        finally:
            logger.info("msg_handler: looper stopped.")

    async def start(self):
        await self._connect()

    async def reply(self, payload: dict):
        # logger.info(f"send_msg: msg put to _send_queue {payload}")
        if not self._writer:
            logger.error("send_msg: not connected, abort.")
            raise ConnectionError("not connected")
        await self._send_queue.put(payload)

    async def query(self, payload: dict, timeout: float = 10.0):
        if not self._writer:
            logger.error("query_msg: not connected, abort.")
            raise ConnectionError("not connected")
        future = asyncio.get_event_loop().create_future()  # create a future
        await self._future_queue.put(future)
        await self._send_queue.put(payload)
        logger.debug(f"query_msg: msg put to _send_queue {payload}")
        try:
            # wait future response
            response_payload = await asyncio.wait_for(future, timeout=timeout)
            logger.debug(f"query_msg: response received {response_payload}")
            return response_payload
        except asyncio.TimeoutError:
            logger.warning(f"query_msg: timeout.")
            raise
        except (ConnectionError, asyncio.CancelledError) as e:
            logger.error(f"query_msg: query cancelled, {e}")
            raise
