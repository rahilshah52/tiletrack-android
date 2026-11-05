#!/usr/bin/env python3
"""Makes the app's pictures and the ring sound during the build, so the repository holds only text files.
Icons are drawn from the TileTrack logo (the same shapes the website uses); needs rsvg-convert and ffmpeg."""
import math, os, struct, subprocess, wave

RES = os.path.join(os.path.dirname(__file__), '..', 'app', 'src', 'main', 'res')
ART = ('<path d="M90 118 L144 96 L90 74 L36 96 Z" fill="{c1}"/><path d="M90 102 L144 80 L90 58 L36 80 Z" fill="{c2}"/>'
       '<path d="M90 86 L144 64 L90 42 L36 64 Z" fill="{c3}"/><path d="M44 132 H112" stroke="{c3}" stroke-width="8" stroke-linecap="round"/>'
       '<path d="M104 122 L118 132 L104 142" fill="none" stroke="{c3}" stroke-width="8" stroke-linecap="round" stroke-linejoin="round"/>')
COLOURS = dict(c1='#c8d4e3', c2='#e6edf5', c3='#f2a541')
LOGO = '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180"><rect width="180" height="180" rx="40" fill="#1b3354"/>' + ART.format(**COLOURS) + '</svg>'
# adaptive icon foreground: artwork only, inside Android's safe zone (the background colour comes from colors.xml)
FORE = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 180 180"><g transform="translate(90 92) scale(0.62) translate(-90 -92)">'
        + ART.format(**COLOURS) + '</g></svg>')
# notification icon: white shapes on transparent (Android uses only the shape)
BADGE = ('<svg xmlns="http://www.w3.org/2000/svg" viewBox="20 30 140 120">' + ART.format(c1='rgba(255,255,255,.55)', c2='rgba(255,255,255,.8)', c3='#ffffff') + '</svg>')

def png(svg, path, px):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    subprocess.run(['rsvg-convert', '-w', str(px), '-h', str(px), '-o', path], input=svg.encode(), check=True)

for d, px in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
    png(LOGO, f'{RES}/mipmap-{d}/ic_launcher.png', px)
    png(FORE, f'{RES}/mipmap-{d}/ic_launcher_foreground.png', px * 108 // 48)
    png(BADGE, f'{RES}/drawable-{d}/ic_notify.png', px // 2)
png(LOGO, f'{RES}/drawable/splash.png', 288)

# 10-second ring: sharp two-tone trill (1320 / 1760 Hz, 55 ms steps), two 0.8 s bursts every 2.2 s, as on the website
sr = 44100; n = int(sr * 10.0); data = []
for i in range(n):
    t = i / sr; c = t % 2.2; v = 0.0
    for b in (0.0, 1.1):
        if b <= c < b + 0.8:
            f = 1760 if int((c - b) / 0.055) % 2 else 1320
            env = min(1, (c - b) / 0.01, (b + 0.8 - c) / 0.02)
            v = (math.sin(2 * math.pi * f * t) + math.sin(2 * math.pi * 3 * f * t) / 3) * env
    data.append(v)
peak = max(abs(x) for x in data)
os.makedirs(f'{RES}/raw', exist_ok=True)
wav = f'{RES}/raw/order_ring_src.wav'
with wave.open(wav, 'wb') as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
    w.writeframes(b''.join(struct.pack('<h', int(x / peak * 0.95 * 32767)) for x in data))
subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-i', wav, '-c:a', 'libvorbis', '-q:a', '5', f'{RES}/raw/order_ring.ogg'], check=True)
os.remove(wav)
print('pictures and ring made')
