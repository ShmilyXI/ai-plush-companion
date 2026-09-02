import numpy as np

from core.companion.audio_activity import AudioActivityGate


def pcm(value, samples=1600):
    return (np.full(samples, value, dtype=np.int16)).tobytes()


def test_quiet_vad_frame_does_not_count_as_activity():
    gate = AudioActivityGate(min_rms=160)

    assert gate.confirm(pcm(20), True) is False
    assert gate.last_decision == "noise"


def test_short_human_sound_counts_when_it_clears_energy_gate():
    gate = AudioActivityGate(min_rms=160)

    assert gate.confirm(pcm(500), True) is True
    assert gate.last_decision == "activity"
    assert gate.confirm(pcm(500), True) is False


def test_next_voice_burst_counts_after_a_silence_boundary():
    gate = AudioActivityGate(min_rms=160)

    assert gate.confirm(pcm(500), True) is True
    assert gate.confirm(pcm(20), False) is False
    assert gate.confirm(pcm(500), True) is True


def test_loud_frame_below_adaptive_noise_ratio_is_rejected():
    gate = AudioActivityGate(min_rms=10, noise_ratio=2.0)
    for _ in range(8):
        gate.confirm(pcm(300), False)

    assert gate.confirm(pcm(450), True) is False
    assert gate.confirm(pcm(900), True) is True


def test_activity_gate_can_require_consecutive_voice_frames():
    gate = AudioActivityGate(min_rms=160, min_active_frames=2)

    assert gate.confirm(pcm(500), True) is False
    assert gate.confirm(pcm(500), True) is True


def test_activity_duration_uses_actual_pcm_frame_length():
    gate = AudioActivityGate(min_rms=160, min_active_ms=300, frame_duration_ms=60)
    frame = pcm(500, samples=960)

    assert [gate.confirm(frame, True) for _ in range(4)] == [False, False, False, False]
    assert gate.confirm(frame, True) is True
