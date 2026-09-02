import asyncio

import pytest

from core.handle.sendAudioHandle import _wait_for_audio_completion
from core.utils.audioRateController import AudioRateController


@pytest.mark.asyncio
async def test_sender_failure_marks_audio_queue_empty_and_stops_retry_loop():
    controller = AudioRateController(frame_duration=60)
    controller.add_audio(b"packet")

    async def fail(_packet):
        raise RuntimeError("socket closed")

    task = controller.start_sending(fail)
    await asyncio.wait_for(task, timeout=1)

    assert controller.queue_empty_event.is_set()
    assert not controller.queue
    assert not controller.queue_has_data_event.is_set()
    assert isinstance(controller.send_error, RuntimeError)


@pytest.mark.asyncio
async def test_stop_wait_surfaces_background_sender_failure():
    controller = AudioRateController(frame_duration=0)
    controller.add_audio(b"packet")

    async def fail(_packet):
        raise RuntimeError("socket closed")

    task = controller.start_sending(fail)
    await asyncio.wait_for(task, timeout=1)

    class Connection:
        config = {"tts_audio_completion_timeout_seconds": 1}
        logger = type(
            "Logger",
            (),
            {"bind": lambda self, **_kwargs: self, "debug": lambda self, *_args, **_kwargs: None},
        )()

        audio_rate_controller = controller

    with pytest.raises(RuntimeError, match="socket closed"):
        await _wait_for_audio_completion(Connection())


@pytest.mark.asyncio
async def test_audio_queue_preserves_proactive_marker_for_background_sender():
    controller = AudioRateController(frame_duration=0)
    controller.add_audio(b"notification", mark_proactive=False)
    observed = []

    async def capture(_packet):
        observed.append(controller.current_mark_proactive)

    task = controller.start_sending(capture)
    await asyncio.wait_for(task, timeout=1)

    assert observed == [False]


@pytest.mark.asyncio
async def test_old_sender_cleanup_cannot_clear_a_new_generation_queue():
    controller = AudioRateController(frame_duration=0)
    old_started = asyncio.Event()
    release_old = asyncio.Event()
    sent = []

    async def old_sender(packet):
        sent.append(packet)
        old_started.set()
        try:
            await release_old.wait()
        except asyncio.CancelledError:
            # A provider may ignore cancellation while a socket write is in flight.
            await release_old.wait()

    async def new_sender(packet):
        sent.append(packet)

    old_task = controller.start_sending(old_sender)
    controller.add_audio(b"old")
    await asyncio.wait_for(old_started.wait(), timeout=1)

    controller.reset()
    new_task = controller.start_sending(new_sender)
    controller.add_audio(b"new")
    release_old.set()

    for _ in range(100):
        if b"new" in sent:
            break
        await asyncio.sleep(0.01)
    assert b"new" in sent
    new_task.cancel()
    await asyncio.gather(new_task, return_exceptions=True)
    old_task.cancel()
    await asyncio.gather(old_task, return_exceptions=True)


@pytest.mark.asyncio
async def test_stale_sender_does_not_advance_new_generation_play_position():
    controller = AudioRateController(frame_duration=60)
    started = asyncio.Event()
    release = asyncio.Event()

    async def old_sender(_packet):
        started.set()
        try:
            await release.wait()
        except asyncio.CancelledError:
            await release.wait()

    old_task = controller.start_sending(old_sender)
    controller.add_audio(b"old")
    await asyncio.wait_for(started.wait(), timeout=1)

    controller.reset()
    release.set()
    await asyncio.sleep(0)

    assert controller.play_position == 0
    old_task.cancel()
    await asyncio.gather(old_task, return_exceptions=True)
