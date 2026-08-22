import pytest

from core.playground.protocol import PlaygroundEvent, PlaygroundInput


@pytest.mark.parametrize("kind,field,value", [
    ("text", "text", "hello"),
    ("audio", "audio_ref", "audio-1"),
    ("vision", "image_ref", "image-1"),
    ("activity", "activity", {"state": "walking"}),
])
def test_input_parser_accepts_each_kind(kind, field, value):
    item = PlaygroundInput.parse({"session_id": "s1", "sequence": 1, "kind": kind, field: value})
    assert item.value == value


def test_input_parser_rejects_mixed_payloads():
    with pytest.raises(ValueError, match="exactly one"):
        PlaygroundInput.parse({"session_id": "s1", "sequence": 1, "kind": "text", "text": "x", "image_ref": "i"})


def test_event_serialization_redacts_and_bounds_values():
    event = PlaygroundEvent.completed("s1", 1, "text", "input", "x" * 1000, "ok")
    payload = event.to_dict()
    assert len(payload["input_summary"]) == 400
    assert "audio" not in payload
