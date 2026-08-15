from pathlib import Path
import math
import struct
import wave

out_dir = Path("/home/ubuntu/vx-repo/app/src/main/res/raw")
out_dir.mkdir(parents=True, exist_ok=True)
sample_rate = 44100
duration_seconds = 0.14
for name, frequency in (("beep_low", 440.0), ("beep_mid", 660.0), ("beep_high", 880.0)):
    frames = int(sample_rate * duration_seconds)
    samples = bytearray()
    for index in range(frames):
        t = index / sample_rate
        fade_in = min(1.0, index / (sample_rate * 0.008))
        fade_out = min(1.0, (frames - index) / (sample_rate * 0.018))
        envelope = max(0.0, min(fade_in, fade_out))
        value = int(0.34 * 32767 * envelope * math.sin(2.0 * math.pi * frequency * t))
        samples.extend(struct.pack("<h", value))
    with wave.open(str(out_dir / f"{name}.wav"), "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(samples)
