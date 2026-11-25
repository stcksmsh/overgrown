#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Join numbered PNG frames, one every 16 ms, into a looping GIF.

    tools/frames_to_gif.py out.gif FRAMES_DIR [FRAMES_DIR ...]

Each FRAMES_DIR holds 0000.png, 0001.png, ... as a Compose test writes them while it advances the clock by 16 ms
per frame. With several directories the clips play side by side, each labelled with its directory name; a shorter
clip holds its last frame. Frames are halved in size to keep the file small. Needs Pillow.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw

LABEL_HEIGHT = 24
HOLD_MS = 800  # the last frame stays this long before the clip loops


def main(out: str, dirs: list[str]) -> None:
    clips = [[Image.open(p).convert("RGB") for p in sorted(Path(d).glob("*.png"))] for d in dirs]
    if not all(clips):
        sys.exit("a frames directory has no PNG files")
    w, h = clips[0][0].width // 2, clips[0][0].height // 2
    labelled = len(clips) > 1
    frames = []
    for i in range(max(len(c) for c in clips)):
        sheet = Image.new("RGB", (w * len(clips), h + (LABEL_HEIGHT if labelled else 0)), "white")
        for j, clip in enumerate(clips):
            sheet.paste(clip[min(i, len(clip) - 1)].resize((w, h), Image.LANCZOS), (w * j, LABEL_HEIGHT if labelled else 0))
            if labelled:
                ImageDraw.Draw(sheet).text((w * j + 8, 6), Path(dirs[j]).name, fill="black")
        frames.append(sheet)
    # GIF delays are whole hundredths of a second: 20, 20, 10 ms repeated average 16.7 ms, the 16 ms step at 60 fps.
    durations = [10 if i % 3 == 2 else 20 for i in range(len(frames))]
    durations[-1] = HOLD_MS
    frames[0].save(out, save_all=True, append_images=frames[1:], duration=durations, loop=0, optimize=True)


if __name__ == "__main__":
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2:])
