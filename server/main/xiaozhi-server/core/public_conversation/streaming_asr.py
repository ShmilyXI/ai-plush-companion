from __future__ import annotations

import asyncio
import gzip
import json
import inspect
from collections.abc import Awaitable, Callable


class PublicStreamingAsr:
    def __init__(self, provider, *, on_partial: Callable[[str], Awaitable[None] | None], on_final: Callable[[str], Awaitable[None] | None]):
        self.provider = provider
        self.on_partial = on_partial
        self.on_final = on_final
        self.closed = False

    async def start(self):
        await self.provider.start(self.on_partial, self.on_final)

    @staticmethod
    async def _call(callback, value):
        result = callback(value)
        if inspect.isawaitable(result):
            await result

    async def push(self, frame: bytes):
        if self.closed:
            raise RuntimeError("stream ASR is closed")
        await self.provider.push(frame)

    async def end(self):
        if self.closed:
            return
        await self.provider.end()

    async def cancel(self):
        if self.closed:
            return
        self.closed = True
        await self.provider.cancel()


class DoubaoStreamingProvider:
    """Low-level adapter for the existing Doubao stream provider protocol."""

    def __init__(self, config):
        from core.providers.asr.doubao_stream import ASRProvider
        self.provider = ASRProvider(dict(config), True)
        self.ws = None
        self.reader = None
        self.last_text = ""
        self.final_sent = False
        self.on_partial = None
        self.on_final = None

    async def start(self, on_partial, on_final):
        import uuid
        import websockets
        self.on_partial = on_partial
        self.on_final = on_final
        headers = self.provider.token_auth()
        self.ws = await websockets.connect(self.provider.ws_url, additional_headers=headers, ping_interval=None, ping_timeout=None)
        request = gzip.compress(json.dumps(self.provider.construct_request(str(uuid.uuid4()))).encode())
        header = self.provider.generate_header()
        header.extend(len(request).to_bytes(4, "big"))
        await self.ws.send(header + request)
        await self.ws.recv()
        self.reader = asyncio.create_task(self._read())

    async def push(self, frame):
        payload = gzip.compress(frame)
        header = self.provider.generate_audio_default_header()
        header.extend(len(payload).to_bytes(4, "big"))
        await self.ws.send(header + payload)

    async def end(self):
        if self.ws is None:
            return
        payload = gzip.compress(b"")
        header = self.provider.generate_last_audio_default_header()
        header.extend(len(payload).to_bytes(4, "big"))
        await self.ws.send(header + payload)
        if self.reader:
            try:
                await asyncio.wait_for(self.reader, 10)
            except asyncio.TimeoutError:
                self.reader.cancel()
        await self._close()

    async def cancel(self):
        if self.reader:
            self.reader.cancel()
        await self._close()

    async def _read(self):
        while self.ws is not None:
            response = self.provider.parse_response(await self.ws.recv())
            payload = response.get("payload_msg") or {}
            result = payload.get("result") or {}
            text = str(result.get("text") or "").strip()
            if text and text != self.last_text:
                self.last_text = text
                await self.on_partial(text)
            for utterance in result.get("utterances") or []:
                if utterance.get("definite") and utterance.get("text") and not self.final_sent:
                    self.final_sent = True
                    await self.on_final(str(utterance["text"]).strip())

    async def _close(self):
        if self.ws is not None:
            await self.ws.close()
            self.ws = None
        self.reader = None
