from __future__ import annotations

import math


class AudioActivityGate:
    def __init__(self, *, min_rms: float = 160.0, noise_ratio: float = 2.0):
        self.min_rms = max(1.0, float(min_rms))
        self.noise_ratio = max(1.0, float(noise_ratio))
        self.noise_floor = 0.0
        self.active = False

    def confirm(self, pcm_frame: bytes, vad_voice: bool) -> bool:
        rms = self._rms(pcm_frame)
        if not vad_voice:
            self.active = False
            if rms > 0:
                self.noise_floor = rms if self.noise_floor == 0 else self.noise_floor * 0.95 + rms * 0.05
            return False
        if rms <= 0 or rms < self.min_rms:
            return False
        if self.noise_floor > 0 and rms < self.noise_floor * self.noise_ratio:
            return False
        if self.active:
            return False
        self.active = True
        return True

    @staticmethod
    def _rms(pcm_frame: bytes) -> float:
        if not isinstance(pcm_frame, (bytes, bytearray)) or len(pcm_frame) < 2:
            return 0.0
        sample_count = len(pcm_frame) // 2
        total = 0.0
        for index in range(sample_count):
            value = int.from_bytes(pcm_frame[index * 2:index * 2 + 2], "little", signed=True)
            total += value * value
        return math.sqrt(total / sample_count)
