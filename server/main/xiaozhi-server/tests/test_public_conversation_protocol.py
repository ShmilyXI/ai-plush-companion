import pytest

from core.public_conversation.protocol import AudioTurnInput, ConversationEvent, TextTurnInput


def test_event_serializes_ordered_public_fields_without_secrets():
    event = ConversationEvent(
        event_type="llm.delta",
        conversation_id="conversation-a",
        turn_id="turn-a",
        sequence=2,
        occurred_at=1710000000000,
        details={"text": "你好", "api_key": "must-not-leak"},
    )

    payload = event.to_dict()

    assert payload == {
        "type": "llm.delta",
        "conversation_id": "conversation-a",
        "turn_id": "turn-a",
        "sequence": 2,
        "occurred_at": 1710000000000,
        "details": {"text": "你好"},
    }
    assert "api_key" not in str(payload)


def test_event_rejects_non_positive_sequence():
    with pytest.raises(ValueError, match="sequence"):
        ConversationEvent(
            event_type="session.ready",
            conversation_id="conversation-a",
            turn_id=None,
            sequence=0,
            occurred_at=1710000000000,
        )


def test_text_turn_requires_request_id_and_text():
    with pytest.raises(ValueError, match="request_id"):
        TextTurnInput(request_id="", text="你好")

    with pytest.raises(ValueError, match="text"):
        TextTurnInput(request_id="request-a", text="")

    with pytest.raises(ValueError, match="too long"):
        TextTurnInput(request_id="request-a", text="x" * 8001)


def test_audio_turn_rejects_oversized_payload():
    with pytest.raises(ValueError, match="too large"):
        AudioTurnInput(request_id="request-a", sequence=0, data="x" * (2 * 1024 * 1024 + 1), final=True)
