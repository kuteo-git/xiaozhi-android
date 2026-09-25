#!/usr/bin/env python3
"""WebSocket framing for the R1 shell on port 8080, WITHOUT opening a socket.

`tools/r1sh.py` talks to the speaker directly and that stopped working on this Mac: macOS refuses
Local Network access per binary, so python's socket returns "No route to host" to every port on the
R1 while `curl` and `nc` (system binaries in /usr/bin) connect fine. python still reaches the
internet and localhost, so this is a permission and not the network.

So the split: python builds and parses the bytes, `nc` carries them. See tools/r1sh.sh.

  build <host> <command>   -> handshake + one masked text frame on stdout
  parse <file>             -> the `data` field of every text frame the server sent
"""
import base64
import json
import os
import struct
import sys


def build(host: str, cmd: str) -> bytes:
    key = base64.b64encode(os.urandom(16)).decode()
    handshake = (
        f"GET / HTTP/1.1\r\nHost: {host}:8080\r\nUpgrade: websocket\r\n"
        f"Connection: Upgrade\r\nSec-WebSocket-Key: {key}\r\n"
        f"Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: v1\r\n\r\n"
    ).encode()
    payload = json.dumps({"type": "shell", "type_id": "myshell", "shell": cmd}).encode()
    mask = os.urandom(4)
    n = len(payload)
    if n < 126:
        header = struct.pack("!BB", 0x81, 0x80 | n)
    elif n < 65536:
        header = struct.pack("!BBH", 0x81, 0x80 | 126, n)
    else:
        header = struct.pack("!BBQ", 0x81, 0x80 | 127, n)
    masked = bytes(b ^ mask[i % 4] for i, b in enumerate(payload))
    return handshake + header + mask + masked


def parse(raw: bytes) -> str:
    cut = raw.find(b"\r\n\r\n")
    if cut < 0:
        return "ERROR: no handshake in reply: %r" % raw[:200]
    if b"101" not in raw[:20]:
        return "ERROR: handshake refused: %s" % raw[:200].decode("utf8", "replace")
    p, out = cut + 4, []
    while p + 2 <= len(raw):
        b0, b1 = raw[p], raw[p + 1]
        p += 2
        length, masked = b1 & 0x7F, b1 & 0x80
        if length == 126:
            if p + 2 > len(raw):
                break
            length = struct.unpack("!H", raw[p:p + 2])[0]
            p += 2
        elif length == 127:
            if p + 8 > len(raw):
                break
            length = struct.unpack("!Q", raw[p:p + 8])[0]
            p += 8
        mask = b""
        if masked:
            mask, p = raw[p:p + 4], p + 4
        if p + length > len(raw):
            break
        body, p = raw[p:p + length], p + length
        if masked:
            body = bytes(c ^ mask[i % 4] for i, c in enumerate(body))
        if (b0 & 0x0F) == 1:                      # text frame; ignore ping/pong/close
            try:
                out.append(json.loads(body.decode("utf8", "replace")).get("data", ""))
            except Exception:
                out.append(body.decode("utf8", "replace"))
    return "".join(out)


if __name__ == "__main__":
    if sys.argv[1] == "build":
        sys.stdout.buffer.write(build(sys.argv[2], sys.argv[3]))
    else:
        print(parse(open(sys.argv[2], "rb").read()))
