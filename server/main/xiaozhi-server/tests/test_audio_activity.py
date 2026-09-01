import numpy as np

from core.companion.audio_activity import AudioActivityGate


def pcm(value, samples=1600):
    return (np.full(samples, value, dtype=np.int16)).tobytes()


def test_quiet_vad_frame_does_not_count_as_activity():
    gate = AudioActivityGate(min_rms=160)

    assert gate.confirm(pcm(20), True) is False


def test_short_human_sound_counts_when_it_clears_energy_gate():
    gate = AudioActivityGate(min_rms=160)

    assert gate.confirm(pcm(500), True) is True
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
