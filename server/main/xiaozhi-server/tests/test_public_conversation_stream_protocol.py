import pytest

from core.public_conversation.protocol import StreamControlFrame, StreamStartInput


def test_stream_start_requires_pcm16_format_and_sample_rate():
    value = StreamStartInput.from_payload({
        "type": "stream.start",
        "request_id": "r1",
        "audio": {"format": "pcm_s16le", "sample_rate": 16000, "channels": 1},
    })
    assert value.sample_rate == 16000
    with pytest.raises(ValueError, match="sample_rate"):
        StreamStartInput.from_payload({
            "type": "stream.start", "request_id": "r1",
            "audio": {"format": "pcm_s16le", "sample_rate": 8000, "channels": 1},
        })


def test_stream_audio_end_is_idempotent_and_stop_is_valid():
    assert StreamControlFrame.from_payload({"type": "stream.audio.end"}).type == "stream.audio.end"
    assert StreamControlFrame.from_payload({"type": "stream.stop"}).type == "stream.stop"


def test_stream_control_rejects_unknown_frame():
    with pytest.raises(ValueError, match="unsupported"):
        StreamControlFrame.from_payload({"type": "stream.unknown"})
