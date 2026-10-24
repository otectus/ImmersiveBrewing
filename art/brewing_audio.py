"""Immersive Brewing's original sound palette, synthesised from code (spec 24.4). GPL-3.0 with the mod.

Each function returns mono float samples in [-1, 1] at RATE. Loops wrap their events around the end so they repeat
seamlessly. Deterministic (fixed seeds), so tools/generate_sounds.py --check can compare encoded bytes.
"""
import numpy as np

RATE = 44100


def t(seconds):
    return np.arange(int(RATE * seconds)) / RATE


def env(n, attack=0.005, release=0.1):
    a = max(1, int(RATE * attack))
    r = max(1, int(RATE * release))
    e = np.ones(n)
    e[:a] = np.linspace(0, 1, a)
    e[-r:] = np.minimum(e[-r:], np.linspace(1, 0, r))
    return e


def lowpass(x, alpha):
    y = np.empty_like(x)
    acc = 0.0
    for i, v in enumerate(x):
        acc += alpha * (v - acc)
        y[i] = acc
    return y


def highpass(x, alpha):
    return x - lowpass(x, alpha)


def norm(x, peak=0.8):
    m = np.max(np.abs(x))
    return x if m == 0 else x / m * peak


def bubble(freq, length, rate):
    tt = t(length)
    sweep = freq * (1 + 1.6 * tt / length)
    phase = 2 * np.pi * np.cumsum(sweep) / RATE
    return np.sin(phase) * np.exp(-tt * rate)


def place(buf, sound, at, loop=True):
    n = len(sound)
    for i in range(n):
        j = at + i
        if j >= len(buf):
            if not loop:
                break
            j %= len(buf)
        buf[j] += sound[i]


def crunch(seed, length, cut, density):
    rng = np.random.default_rng(seed)
    n = int(RATE * length)
    out = np.zeros(n)
    for _ in range(density):
        at = rng.integers(0, n - 2000)
        burst = rng.normal(0, 1, 1500) * np.exp(-np.arange(1500) / rng.uniform(150, 400))
        place(out, burst, at, loop=False)
    return norm(lowpass(out, cut) * env(n, 0.002, 0.05), 0.7)


def mortar_crush():
    return crunch(1, 0.28, 0.35, 9)


def mortar_grind():
    rng = np.random.default_rng(2)
    n = int(RATE * 0.32)
    grit = rng.normal(0, 1, n) * (0.6 + 0.4 * np.sin(np.arange(n) / RATE * 2 * np.pi * 23))
    return norm(highpass(lowpass(grit, 0.5), 0.02) * env(n, 0.03, 0.12), 0.55)


def mortar_mash():
    rng = np.random.default_rng(3)
    n = int(RATE * 0.26)
    wet = lowpass(rng.normal(0, 1, n), 0.08) * env(n, 0.02, 0.15)
    return norm(wet + 0.3 * bubble(180, 0.26, 18)[:n], 0.6)


def mortar_lift():
    tt = t(0.14)
    knock = np.sin(2 * np.pi * 240 * tt) * np.exp(-tt * 45) + 0.4 * np.sin(2 * np.pi * 510 * tt) * np.exp(-tt * 70)
    return norm(knock, 0.6)


def kettle_simmer():
    rng = np.random.default_rng(4)
    n = int(RATE * 2.0)
    out = lowpass(rng.normal(0, 0.05, n), 0.05)
    for _ in range(26):
        place(out, 0.5 * bubble(rng.uniform(500, 900), 0.05, 70), rng.integers(0, n))
    return norm(out, 0.5)


def kettle_boil():
    rng = np.random.default_rng(5)
    n = int(RATE * 2.0)
    out = lowpass(rng.normal(0, 0.25, n), 0.03)
    for _ in range(60):
        place(out, rng.uniform(0.4, 0.9) * bubble(rng.uniform(220, 520), 0.08, 40), rng.integers(0, n))
    return norm(out, 0.6)


def kettle_foam():
    rng = np.random.default_rng(6)
    n = int(RATE * 0.6)
    hiss = highpass(rng.normal(0, 1, n), 0.2) * env(n, 0.05, 0.3)
    return norm(hiss, 0.35)


def kettle_swirl():
    rng = np.random.default_rng(7)
    n = int(RATE * 2.0)
    tt = np.arange(n) / RATE
    swish = lowpass(rng.normal(0, 1, n), 0.04) * (0.6 + 0.4 * np.sin(2 * np.pi * tt * 1.0))
    return norm(swish, 0.45)


def kettle_add():
    tt = t(0.3)
    plop = np.sin(2 * np.pi * np.cumsum(260 - 160 * tt / 0.3) / RATE) * np.exp(-tt * 14)
    rng = np.random.default_rng(8)
    splash = highpass(rng.normal(0, 0.3, len(tt)), 0.3) * np.exp(-tt * 30)
    return norm(plop + splash, 0.7)


def alembic_drop():
    tt = t(0.16)
    drip = np.sin(2 * np.pi * np.cumsum(900 + 1400 * tt / 0.16) / RATE) * np.exp(-tt * 40)
    return norm(drip, 0.5)


def alembic_valve():
    rng = np.random.default_rng(9)
    n = int(RATE * 0.25)
    out = np.zeros(n)
    for i in range(5):
        tick = np.sin(2 * np.pi * 1800 * t(0.02)) * np.exp(-t(0.02) * 300)
        place(out, tick, int(i * n / 6) + rng.integers(0, 200), loop=False)
    return norm(out + 0.2 * lowpass(rng.normal(0, 1, n), 0.1) * env(n), 0.5)


def glass(freq, length):
    tt = t(length)
    return (np.sin(2 * np.pi * freq * tt) + 0.5 * np.sin(2 * np.pi * freq * 2.76 * tt)) * np.exp(-tt * 12)


def alembic_receiver():
    return norm(glass(2300, 0.22), 0.45)


def bottle_clink():
    n = int(RATE * 0.36)
    out = np.zeros(n)
    place(out, glass(2600, 0.2), 0, loop=False)
    place(out, 0.8 * glass(3100, 0.2), int(RATE * 0.12), loop=False)
    return norm(out, 0.5)


def chime(freqs, length, decay):
    tt = t(length)
    out = sum(np.sin(2 * np.pi * f * tt) * np.exp(-tt * decay) for f in freqs)
    return out * env(len(tt), 0.004, 0.05)


def brew_complete():
    n = int(RATE * 0.9)
    out = np.zeros(n)
    place(out, chime([523.25, 1046.5], 0.7, 5), 0, loop=False)
    place(out, chime([659.25, 1318.5], 0.6, 5), int(RATE * 0.18), loop=False)
    return norm(out, 0.45)


def cue_ready():
    return norm(chime([880, 1760], 0.3, 10), 0.4)


def cue_warning():
    n = int(RATE * 0.42)
    out = np.zeros(n)
    place(out, chime([392], 0.2, 12), 0, loop=False)
    place(out, chime([311], 0.22, 12), int(RATE * 0.18), loop=False)
    return norm(out, 0.45)


SOUNDS = {
    'mortar/crush': mortar_crush, 'mortar/grind': mortar_grind, 'mortar/mash': mortar_mash, 'mortar/lift': mortar_lift,
    'kettle/simmer': kettle_simmer, 'kettle/boil': kettle_boil, 'kettle/foam': kettle_foam, 'kettle/swirl': kettle_swirl,
    'kettle/add': kettle_add, 'alembic/drop': alembic_drop, 'alembic/valve': alembic_valve, 'alembic/receiver': alembic_receiver,
    'bottle/clink': bottle_clink, 'brew/complete': brew_complete, 'cue/ready': cue_ready, 'cue/warning': cue_warning,
}


def sounds():
    return {name: fn() for name, fn in SOUNDS.items()}
