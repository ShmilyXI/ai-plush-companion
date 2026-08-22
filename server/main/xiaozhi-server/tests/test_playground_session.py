import pytest
import base64

from core.playground.protocol import PlaygroundSnapshot, VirtualDevice
from core.playground.service import PlaygroundService


def test_session_accepts_ordered_inputs_and_resumes_after_cursor():
    service = PlaygroundService()
    service.create({"session_id": "s1", "config": {}, "virtual_device": {}})
    session = service.get("s1")
    session.accept({"session_id": "s1", "sequence": 1, "kind": "text", "text": "hello"})
    assert [event.sequence for event in session.events_after(0)] == [1]
    assert session.events_after(1) == []


def test_session_rejects_wrong_sequence_and_session_id():
    service = PlaygroundService()
    service.create({"session_id": "s1", "config": {}, "virtual_device": {}})
    session = service.get("s1")
    with pytest.raises(ValueError, match="mismatch"):
        session.accept({"session_id": "other", "sequence": 1, "kind": "text", "text": "hello"})
    with pytest.raises(ValueError, match="sequence"):
        session.accept({"session_id": "s1", "sequence": 2, "kind": "text", "text": "hello"})


def test_session_executes_runtime_llm(monkeypatch):
    class FakeLLM:
        def response(self, session_id, dialogue):
            assert session_id == "s1"
            assert dialogue[-1]["content"] == "hello"
            yield "真实 provider 回复"

    from core.utils import llm
    monkeypatch.setattr(llm, "create_instance", lambda provider, config: FakeLLM())
    service = PlaygroundService()
    service.create({"session_id": "s1", "config": {"profileSystemPrompt": "你是小夏"}, "runtime_models": {"LLM": {"config": {"type": "openai"}}}, "virtual_device": {}})
    session = service.get("s1")
    item = session.accept({"session_id": "s1", "sequence": 1, "kind": "text", "text": "hello"})
    generated = __import__("asyncio").run(session.execute(__import__("core.playground.protocol", fromlist=["PlaygroundInput"]).PlaygroundInput.parse({"session_id": "s1", "sequence": 1, "kind": "text", "text": "hello"})))
    assert generated[0].capability == "llm"
    assert generated[0].output_summary == "真实 provider 回复"


def test_session_returns_asr_transcript_details(monkeypatch):
    class FakeASR:
        async def speech_to_text_wrapper(self, frames, session_id, raise_errors=False):
            return "识别出来的文字", None
    from core.utils import asr
    monkeypatch.setattr(asr, "create_instance", lambda *args: FakeASR())
    service = PlaygroundService()
    service.create({"session_id": "s1", "config": {}, "runtime_models": {"ASR": {"config": {"type": "fake"}}}, "virtual_device": {}})
    session = service.get("s1")
    payload = {"session_id": "s1", "sequence": 1, "kind": "audio", "audio_ref": "data:audio/pcm;base64," + base64.b64encode(b"pcm").decode()}
    session.accept(payload)
    generated = __import__("asyncio").run(session.execute(__import__("core.playground.protocol", fromlist=["PlaygroundInput"]).PlaygroundInput.parse(payload)))
    assert generated[0].details == {"transcript": "识别出来的文字"}


def test_session_returns_playable_tts_details(monkeypatch):
    class FakeTTS:
        audio_file_type = "wav"
        async def text_to_speak(self, text, output_file):
            return b"RIFFfake"
    from core.utils import tts
    monkeypatch.setattr(tts, "create_instance", lambda *args: FakeTTS())
    service = PlaygroundService()
    service.create({"session_id": "s1", "config": {}, "runtime_models": {"TTS": {"config": {"type": "fake"}}}, "virtual_device": {}})
    session = service.get("s1")
    payload = {"session_id": "s1", "sequence": 1, "kind": "tts", "text": "请朗读"}
    session.accept(payload)
    generated = __import__("asyncio").run(session.execute(__import__("core.playground.protocol", fromlist=["PlaygroundInput"]).PlaygroundInput.parse(payload)))
    assert generated[0].details["audioDataUrl"].startswith("data:audio/x-wav;base64,")
