from __future__ import annotations

import math


class AudioActivityGate:
    def __init__(
        self,
        *,
        min_rms: float = 160.0,
        noise_ratio: float = 2.0,
        min_active_frames: int = 1,
        min_active_ms: int = 0,
        frame_duration_ms: int = 60,
    ):
        self.min_rms = max(1.0, float(min_rms))
        self.noise_ratio = max(1.0, float(noise_ratio))
        frame_duration_ms = max(1, int(frame_duration_ms))
        requested_frames = max(1, int(min_active_frames))
        duration_frames = max(1, (max(0, int(min_active_ms)) + frame_duration_ms - 1) // frame_duration_ms)
        self.min_active_frames = max(requested_frames, duration_frames)
        self.min_active_ms = max(0, int(min_active_ms))
        self.frame_duration_ms = frame_duration_ms
        self.noise_floor = 0.0
        self.active = False
        self._voice_frames = 0
        self._voice_duration_ms = 0.0
        self.last_decision = "silence"

    def confirm(self, pcm_frame: bytes, vad_voice: bool, frame_duration_ms: float | None = None) -> bool:
        duration_ms = self.frame_duration_ms if frame_duration_ms is None else max(1.0, float(frame_duration_ms))
        rms = self._rms(pcm_frame)
        if not vad_voice:
            self.active = False
            self._voice_frames = 0
            self._voice_duration_ms = 0.0
            self.last_decision = "silence"
            if rms > 0:
                self.noise_floor = rms if self.noise_floor == 0 else self.noise_floor * 0.95 + rms * 0.05
            return False
        if rms <= 0 or rms < self.min_rms:
            self._voice_frames = 0
            self._voice_duration_ms = 0.0
            self.last_decision = "noise"
            return False
        if self.noise_floor > 0 and rms < self.noise_floor * self.noise_ratio:
            self._voice_frames = 0
            self._voice_duration_ms = 0.0
            self.last_decision = "noise"
            return False
        self._voice_frames += 1
        self._voice_duration_ms += duration_ms
        if self._voice_frames < self.min_active_frames or self._voice_duration_ms < self.min_active_ms:
            self.last_decision = "noise"
            return False
        if self.active:
            self.last_decision = "activity"
            return False
        self.active = True
        self.last_decision = "activity"
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
