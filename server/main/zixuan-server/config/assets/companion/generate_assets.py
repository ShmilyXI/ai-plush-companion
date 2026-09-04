import math
import random
import struct
import wave
from pathlib import Path


SAMPLE_RATE = 24000
ROOT = Path(__file__).parent


def envelope(position, duration, attack=0.08, release=0.2):
    attack_gain = min(1.0, position / max(attack, 0.001))
    release_gain = min(1.0, (duration - position) / max(release, 0.001))
    return max(0.0, min(attack_gain, release_gain))


def write_wav(name, duration, sample_function):
    frames = []
    for index in range(int(SAMPLE_RATE * duration)):
        time = index / SAMPLE_RATE
        sample = max(-1.0, min(1.0, sample_function(time, duration)))
        frames.append(struct.pack("<h", int(sample * 32767)))
    with wave.open(str(ROOT / name), "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(SAMPLE_RATE)
        audio.writeframes(b"".join(frames))


def laugh(time, duration):
    value = 0.0
    for start, frequency in ((0.02, 300), (0.20, 350), (0.38, 410)):
        local = time - start
        if 0 <= local <= 0.16:
            pulse = envelope(local, 0.16, 0.025, 0.06)
            value += pulse * (
                math.sin(2 * math.pi * frequency * local)
                + 0.35 * math.sin(4 * math.pi * frequency * local)
            )
    return 0.22 * value


def sigh(time, duration):
    rng = random.Random(1200 + int(time * SAMPLE_RATE))
    falling_tone = math.sin(2 * math.pi * (240 - 130 * time / duration) * time)
    air = rng.uniform(-1, 1)
    return envelope(time, duration, 0.12, 0.3) * (0.07 * falling_tone + 0.12 * air)


def hesitate(time, duration):
    pause = 0.09 if 0.25 < time < 0.34 else 1.0
    wobble = 145 + 7 * math.sin(2 * math.pi * 4 * time)
    return 0.12 * pause * envelope(time, duration, 0.06, 0.15) * math.sin(
        2 * math.pi * wobble * time
    )


def breathe(time, duration):
    rng = random.Random(2400 + int(time * SAMPLE_RATE))
    air = rng.uniform(-1, 1)
    slow = 0.55 + 0.45 * math.sin(math.pi * time / duration)
    return 0.11 * envelope(time, duration, 0.18, 0.24) * slow * air


write_wav("laugh.wav", 0.62, laugh)
write_wav("sigh.wav", 0.86, sigh)
write_wav("hesitate.wav", 0.58, hesitate)
write_wav("breathe.wav", 0.74, breathe)
