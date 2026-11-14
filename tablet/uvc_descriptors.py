"""Parse a USB device's raw descriptors (from /sys/bus/usb/devices/*/descriptors, as `od -tx1` hex)
and report whether UVC Monitor can drive it: needs an MJPEG format on a BULK video endpoint.

Usage: python3 -I uvc_descriptors.py <hexdump-file>
Exit code: 0 = supported, 2 = not supported.
"""
import sys

FORMATS = {0x04: "UNCOMPRESSED", 0x06: "MJPEG", 0x10: "FRAME_BASED"}
FRAMES = {0x05: "UNCOMPRESSED", 0x07: "MJPEG", 0x11: "FRAME_BASED"}


def main(path):
    b = bytes(int(x, 16) for x in open(path).read().split())
    i = 18  # skip device descriptor
    if_class = if_sub = None
    fmt = None
    mjpeg_modes, bulk_video, iso_video = [], False, False
    while i + 2 <= len(b):
        length, dtype = b[i], b[i + 1]
        if length < 2:
            break
        d = b[i:i + length]
        if dtype == 0x04:
            if_class, if_sub = d[5], d[6]
        elif dtype == 0x05 and if_class == 0x0E and if_sub == 2 and d[2] & 0x80:
            kind = d[3] & 0x03
            bulk_video |= kind == 2
            iso_video |= kind == 1
        elif dtype == 0x24 and if_class == 0x0E and if_sub == 2:
            sub = d[2]
            if sub in FORMATS:
                fmt = FORMATS[sub]
            elif sub in FRAMES and fmt == "MJPEG" and length >= 26:
                w, h = d[5] | d[6] << 8, d[7] | d[8] << 8
                n = d[25]
                ivals = [int.from_bytes(d[26 + 4 * k:30 + 4 * k], "little") for k in range(max(n, 1))]
                ivals = [v for v in ivals if v]
                fps = 1e7 / min(ivals) if ivals else 0
                mjpeg_modes.append(f"{w}x{h}@{fps:.0f}")
        i += length

    print("MJPEG modes :", ", ".join(mjpeg_modes) or "none")
    print("Video EP    :", "BULK" if bulk_video else ("ISOCHRONOUS" if iso_video else "none"))
    ok = bool(mjpeg_modes) and bulk_video
    print("Supported   :", "YES" if ok else "NO (UVC Monitor needs MJPEG over a bulk endpoint)")
    return 0 if ok else 2


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
