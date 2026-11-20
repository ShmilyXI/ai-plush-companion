"""在交给语音识别之前，先看看这段声音里到底有没有人在说话。

本地的语音识别模型拿到没有人声的声音（风扇、键盘、单调的嗡嗡声），也常常硬识别出一个字或一个词，
比如一个逗号、“The.”。角色就会当真去回答。这里用服务里本来就有的 Silero 人声检测模型先过一遍：
人声累计不够长的，直接当成没说话。

模型很小，在 CPU 上跑一段几秒的声音只要几毫秒。整个进程共用一个，第一次用到时才加载。
模型文件找不到或者加载失败时不拦任何声音，照常交给识别，不能因为这一步让通话不能用。
"""

from __future__ import annotations

import os
import threading
from typing import Any

import numpy as np

WINDOW = 512            # 16 kHz 下每次判断 32 毫秒
SPEECH_PROBABILITY = 0.5
# 人声累计至少这么长才算说了话。一个字大约 200 毫秒。
MIN_SPEECH_MS = 200

_MODEL_PATH = os.path.join(os.path.dirname(__file__), "..", "..", "models", "snakers4_silero-vad",
                           "src", "silero_vad", "data", "silero_vad.onnx")
_lock = threading.Lock()
_session: Any = None
_unavailable = False


def _model():
    global _session, _unavailable
    if _session is not None or _unavailable:
        return _session
    with _lock:
        if _session is None and not _unavailable:
            try:
                import onnxruntime

                options = onnxruntime.SessionOptions()
                options.inter_op_num_threads = 1
                options.intra_op_num_threads = 1
                _session = onnxruntime.InferenceSession(os.path.abspath(_MODEL_PATH), providers=["CPUExecutionProvider"],
                                                        sess_options=options)
            except Exception:
                _unavailable = True
    return _session


def speech_ms(pcm: bytes) -> int | None:
    """这段 16 kHz、单声道、16 位的声音里，人声累计有多少毫秒。模型用不了时返回 None。"""
    session = _model()
    if session is None:
        return None
    samples = np.frombuffer(pcm[: len(pcm) // 2 * 2], dtype=np.int16).astype(np.float32) / 32768.0
    state = np.zeros((2, 1, 128), dtype=np.float32)
    context = np.zeros((1, 64), dtype=np.float32)
    voiced = 0
    for start in range(0, len(samples) - WINDOW + 1, WINDOW):
        chunk = samples[start:start + WINDOW].reshape(1, -1)
        inputs = np.concatenate([context, chunk], axis=1).astype(np.float32)
        output, state = session.run(None, {"input": inputs, "state": state, "sr": np.array(16000, dtype=np.int64)})
        context = chunk[:, -64:]
        if float(output[0][0]) >= SPEECH_PROBABILITY:
            voiced += 1
    return voiced * WINDOW * 1000 // 16000


def has_speech(pcm: bytes) -> bool:
    """有没有人在说话。判断不了的时候算有，交给识别。"""
    measured = speech_ms(pcm)
    return measured is None or measured >= MIN_SPEECH_MS
