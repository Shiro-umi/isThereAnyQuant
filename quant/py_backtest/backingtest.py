from ctx import Context
from socket_manager import SocketManager
import time

if __name__ == '__main__':
    import script
    context = Context(script)
    manager = SocketManager('127.0.0.1', 6332, context.on_receive)
    context.set_socket_manager(manager)
    script.init(context)
    try:
        while True:
            if not manager.receiving_thread.is_alive():
                print("terminated.")
                break
            time.sleep(1)
    finally:
        manager.shutdown()
