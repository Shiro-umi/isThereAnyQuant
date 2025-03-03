import asyncio
import websockets
import sys

port = 5000

async def websocket_handler(websocket):
    async for message in websocket:
        if message == "do_at_9_25":
            # todo do sth
            await websocket.send("result")
        elif message == "do_at_9_50":
            # todo do sth
            await websocket.send("result")

        await websocket.send("response")

# noinspection PyTypeChecker
async def main():
    async with websockets.serve(websocket_handler, "localhost", port):
        print(f"WebSocket server started on ws://localhost:{port}")
        await asyncio.Future()  # Run forever

# Run the server
if __name__ == "__main__":
    port = int(sys.argv[1])
    asyncio.run(main())
