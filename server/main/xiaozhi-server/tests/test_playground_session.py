import pytest

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
