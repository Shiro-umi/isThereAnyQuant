from ctx import Context
from socket_manager import SocketManager
import time

def run_test(ctx: Context):
    script.init(ctx)

if __name__ == '__main__':
    import script
    context = Context(script)
    manager = SocketManager('127.0.0.1', 6332, context.on_receive)
    context.set_socket_manager(manager)
    context.start()

    try:
        while True:
            if not manager.running:
                print("terminated.")
                break
            time.sleep(1)
    finally:
        manager.close()
