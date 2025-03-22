import socket as pysocket
import sys
import threading
import os
import time


def send_messages(socket):
    try:
        while True:
            msg = input("input your msg, 'exit' to exit: ")
            if msg.lower() == 'exit':
                break
            try:
                socket.sendall(f"{msg}\n".encode('utf-8'))
            except (BrokenPipeError, ConnectionResetError):
                print("connection closed by server")
                break
    except KeyboardInterrupt:
        pass
    finally:
        socket.close()
        sys.exit(0)


def receive_messages(socket):
    try:
        while True:
            data = socket.recv(sys.maxsize)
            if not data:
                print("\n connection closed by server")
                socket.close()
                os._exit(0)
            print(f"\n response from server: {data.decode('utf-8')}")
    except (ConnectionResetError, OSError):
        print("\n connection reset.")
        socket.close()
        os._exit(0)


if __name__ == "__main__":
    client_socket = pysocket.socket(pysocket.AF_INET, pysocket.SOCK_STREAM)
    try:
        server_address = ('127.0.0.1', sys.argv[1])
        client_socket.connect(server_address)
        print(f"server connected {server_address}")
        send_thread = threading.Thread(target=send_messages, args=(client_socket,))
        send_thread.daemon = True
        send_thread.start()
        receive_thread = threading.Thread(target=receive_messages, args=(client_socket,))
        receive_thread.daemon = True
        receive_thread.start()
        while True:
            if not send_thread.is_alive() or not receive_thread.is_alive():
                break
            time.sleep(1)

    except ConnectionRefusedError:
        print("socket connection refused")
    except KeyboardInterrupt:
        print("\n user key interrupted")
    finally:
        client_socket.close()
        sys.exit(0)
