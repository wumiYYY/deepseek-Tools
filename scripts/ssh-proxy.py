"""Relay OpenSSH through the desktop's configured HTTP CONNECT proxy. No credentials are saved."""
import base64
import os
import socket
import sys
import threading
import urllib.parse


def main():
    host, port = sys.argv[1], int(sys.argv[2])
    value = os.environ.get("HTTPS_PROXY") or os.environ.get("HTTP_PROXY")
    proxy = urllib.parse.urlsplit(value or "")
    if proxy.scheme != "http" or not proxy.hostname:
        raise RuntimeError("An HTTP CONNECT proxy is required")
    connection = socket.create_connection((proxy.hostname, proxy.port or 80), timeout=30)
    authority = f"{host}:{port}"
    headers = [f"CONNECT {authority} HTTP/1.1", f"Host: {authority}"]
    if proxy.username is not None:
        pair = urllib.parse.unquote(proxy.username) + ":" + urllib.parse.unquote(proxy.password or "")
        headers.append("Proxy-Authorization: Basic " + base64.b64encode(pair.encode()).decode())
    connection.sendall(("\r\n".join(headers) + "\r\n\r\n").encode())
    response = bytearray()
    while not response.endswith(b"\r\n\r\n"):
        byte = connection.recv(1)
        if not byte or len(response) > 65536:
            raise RuntimeError("Proxy did not establish an SSH tunnel")
        response.extend(byte)
    if response.split(b"\r\n", 1)[0].split()[1] != b"200":
        raise RuntimeError("Proxy refused the SSH tunnel")
    connection.settimeout(None)

    def send():
        try:
            while chunk := os.read(sys.stdin.fileno(), 65536):
                connection.sendall(chunk)
            connection.shutdown(socket.SHUT_WR)
        except OSError:
            pass

    threading.Thread(target=send, daemon=True).start()
    try:
        while chunk := connection.recv(65536):
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
    finally:
        connection.close()


if __name__ == "__main__":
    main()
