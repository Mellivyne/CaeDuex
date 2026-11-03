"""TLS relay for invited AppCast viewers. Run with a trusted certificate and key."""

import argparse
import asyncio
import secrets
import ssl
import struct


MAGIC = b'CPCAST\x01'
MAX_FRAME = 16 * 1024 * 1024
MAX_VIEWERS = 16
MAX_SESSIONS = 64


class Broadcast:
    def __init__(self):
        self.viewers = {}
        self.latest = None

    def close(self):
        for viewer in self.viewers:
            viewer.transport.abort()

    def frame(self, packet):
        self.latest = packet
        for queue in self.viewers.values():
            if queue.full():
                queue.get_nowait()
            queue.put_nowait(packet)


class Relay:
    def __init__(self):
        self.sessions = {}
        self.connections = 0

    async def handle(self, reader, writer):
        if self.connections >= 256:
            writer.close()
            return
        self.connections += 1
        try:
            header = await asyncio.wait_for(reader.readexactly(len(MAGIC) + 1), 5)
            if header == MAGIC + b'P':
                await self.publish(reader, writer)
            elif header == MAGIC + b'V':
                await self.watch(reader, writer)
        except (OSError, asyncio.TimeoutError, asyncio.IncompleteReadError):
            pass
        finally:
            self.connections -= 1
            writer.close()

    async def publish(self, reader, writer):
        if len(self.sessions) >= MAX_SESSIONS:
            writer.write(b'\x00')
            await writer.drain()
            return
        code = secrets.token_urlsafe(16).encode('ascii')
        while code in self.sessions:
            code = secrets.token_urlsafe(16).encode('ascii')
        stream = self.sessions[code] = Broadcast()
        try:
            writer.write(b'\x01' + code)
            await asyncio.wait_for(writer.drain(), 5)
            while True:
                size_bytes = await asyncio.wait_for(reader.readexactly(4), 20)
                size, = struct.unpack('!I', size_bytes)
                if size == 0:  # Keep the invite alive while the capture is idle.
                    continue
                if size > MAX_FRAME:
                    return
                packet = await asyncio.wait_for(reader.readexactly(size), 10)
                stream.frame(size_bytes + packet)
        finally:
            del self.sessions[code]
            stream.close()

    async def watch(self, reader, writer):
        code = await asyncio.wait_for(reader.readexactly(22), 5)
        stream = self.sessions.get(code)
        if stream is None or len(stream.viewers) >= MAX_VIEWERS:
            writer.write(b'\x00')
            await writer.drain()
            return
        queue = stream.viewers[writer] = asyncio.Queue(maxsize=1)
        if stream.latest is not None:
            queue.put_nowait(stream.latest)
        disconnected = asyncio.create_task(reader.read(1))
        pending = None
        try:
            writer.write(b'\x01')
            await asyncio.wait_for(writer.drain(), 5)
            while not disconnected.done():
                pending = asyncio.create_task(queue.get())
                done, _ = await asyncio.wait((pending, disconnected), return_when=asyncio.FIRST_COMPLETED)
                if disconnected in done or writer.is_closing():
                    break
                writer.write(pending.result())
                await asyncio.wait_for(writer.drain(), 3)
        finally:
            stream.viewers.pop(writer, None)
            disconnected.cancel()
            if pending is not None:
                pending.cancel()


async def serve(args):
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.minimum_version = ssl.TLSVersion.TLSv1_2
    context.load_cert_chain(args.cert, args.key)
    relay = Relay()
    async with await asyncio.start_server(relay.handle, args.bind, args.port, ssl=context,
                                          ssl_handshake_timeout=5) as server:
        await server.serve_forever()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--bind', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8443)
    parser.add_argument('--cert', required=True)
    parser.add_argument('--key', required=True)
    asyncio.run(serve(parser.parse_args()))
