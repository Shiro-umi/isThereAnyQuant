import abc
import asyncio
import json
from asyncio import StreamReader

from py_backtest.log import logger


# Base Looper
class Looper:
    def __init__(self, name, msg_handler, shutdown_event: asyncio.Event, producer: StreamReader | None = None):
        self._name = name
        self._producer = producer
        self._queue = asyncio.Queue()
        self._handler = msg_handler
        self._shutdown_event = shutdown_event

    async def start_looper(self):
        logger.debug(f'looper: {self._name} started!')
        while not self._shutdown_event.is_set():
            try:
                if self._producer:
                    # line_bytes
                    data = await asyncio.wait_for(self._producer.readline(), timeout=1.0)
                else:
                    data = await asyncio.wait_for(self._queue.get(), timeout=1.0)
                formatted = await self._formator(data)
                logger.debug(f'looper: {self._name}, new data: {formatted}')
                await self._handler(formatted)
            except asyncio.TimeoutError:
                continue
            except asyncio.CancelledError:
                logger.warning(f'looper: {self._name} cancelled! wait for other loopers clear up...')
                if not self._shutdown_event.is_set():
                    logger.warning(f'looper: {self._name} shutdown_event set')
                    self._shutdown_event.set()

    async def emit(self, payload):
        if self._producer:
            logger.warning(f'looper:{self._name}, emit was ignored because of data producer is set')
            return
        await self._queue.put(payload)

    async def emit_now(self, payload):
        if self._producer:
            logger.warning(f'looper:{self._name}, emit was ignored because of data producer is set')
            return
        await self._handler(await self._formator(payload))

    @abc.abstractmethod
    async def _formator(self, payload):
        pass


# Looper for sender thread
class SenderLooper(Looper):
    def __init__(self, handler, shutdown_event):
        super().__init__('sender', handler, shutdown_event)

    async def _formator(self, payload):
        msg = json.dumps(payload)
        msg_bytes = (msg + '\n').encode('utf-8')
        return msg_bytes

# Looper for receiver thread
class ReceiverLooper(Looper):
    def __init__(self, shutdown_event, producer: StreamReader, handler):
        super().__init__('receiver', handler, shutdown_event, producer)

    async def _formator(self, payload):
        if not payload:
            return None
        return payload.decode('utf-8').strip()


class WorkLooper(Looper):
    def __init__(self, handler, shutdown_event,):
        super().__init__('worker', handler, shutdown_event,)

    async def _formator(self, payload):
        return payload