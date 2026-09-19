#!/usr/bin/env python3
"""Loopback-only test coordination plus a disconnectable TCP link to a real backend.
No fabricated REST or WebSocket business responses. Never log headers or credentials.
"""
import asyncio
import json
from collections import deque
from urllib.parse import urlsplit, parse_qs

online = True
writers = set()
commands = deque()
results = {}
next_id = 0

async def tunnel(reader, writer):
    if not online:
        writer.close()
        return
    upstream = None
    try:
        peer, upstream = await asyncio.open_connection('127.0.0.1', 18385)
        writers.update((writer, upstream))
        if not online:
            return
        async def copy(source, target):
            while data := await source.read(65536):
                target.write(data)
                await target.drain()
        tasks = [asyncio.create_task(copy(reader, upstream)), asyncio.create_task(copy(peer, writer))]
        await asyncio.wait(tasks, return_when=asyncio.FIRST_COMPLETED)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
    except (OSError, asyncio.CancelledError):
        pass
    finally:
        for item in (writer, upstream):
            if item:
                writers.discard(item)
                item.close()

async def control(reader, writer):
    global online, next_id
    try:
        raw = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 5)
        lines = raw.decode('ascii').split('\r\n')
        method, target, _ = lines[0].split(' ')
        headers = dict(line.split(': ', 1) for line in lines[1:] if ': ' in line)
        length = int(next((v for k, v in headers.items() if k.lower() == 'content-length'), '0'))
        if length > 65536:
            raise ValueError('request too large')
        body = json.loads(await reader.readexactly(length)) if length else {}
        parsed = urlsplit(target)
        response = {}
        if parsed.path == '/network' and method == 'POST':
            online = body['online'] is True
            if not online:
                for item in list(writers):
                    item.close()
            response = {'online': online}
            print(json.dumps({'networkOnline': online}), flush=True)
        elif parsed.path == '/command' and method == 'POST':
            if body.get('action') not in ('send', 'expect', 'finish'):
                raise ValueError('unsupported browser operation')
            next_id += 1
            commands.append({'id': str(next_id), **body})
            response = {'id': str(next_id)}
        elif parsed.path == '/next':
            response = commands.popleft() if commands else {}
        elif parsed.path == '/result' and method == 'POST':
            results[body['id']] = body
            response = {'ok': True}
            print(json.dumps({'browserCommand': body['id'], 'ok': body['ok']}), flush=True)
        elif parsed.path == '/result':
            response = results.get(parse_qs(parsed.query).get('id', [''])[0], {})
        elif parsed.path == '/status':
            response = {'online': online}
        else:
            raise ValueError('unsupported control route')
        payload = json.dumps(response).encode()
        writer.write(b'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nConnection: close\r\nContent-Length: ' + str(len(payload)).encode() + b'\r\n\r\n' + payload)
        await writer.drain()
    except (OSError, ValueError, asyncio.IncompleteReadError, asyncio.TimeoutError):
        pass
    finally:
        writer.close()

async def main():
    proxy = await asyncio.start_server(tunnel, '127.0.0.1', 18387)
    bridge = await asyncio.start_server(control, '127.0.0.1', 18386)
    print('A05 loopback test proxy :18387 -> real backend :18385; control :18386', flush=True)
    async with proxy, bridge:
        await asyncio.gather(proxy.serve_forever(), bridge.serve_forever())

if __name__ == '__main__':
    asyncio.run(main())
