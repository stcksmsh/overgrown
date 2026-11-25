#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-only
"""Builds app/src/main/assets/cities.tsv from Natural Earth's populated places (docs/DECISIONS.md D40).

Usage: python3 -I tools/ne_cities.py <ne_10m_populated_places_simple.zip> <out.tsv>

The zip is the download from naturalearthdata.com, read as it is; only its .dbf attribute table is used.
Each output line is: name, country, latitude, longitude, tab-separated, coordinates to two decimals.
"""
import struct
import sys
import zipfile


def records(dbf: bytes, encoding: str):
    """Yields each live record of a dBase III table as a dict of stripped strings."""
    count, header_len, record_len = struct.unpack("<IHH", dbf[4:12])
    fields = []
    pos = 32
    while dbf[pos] != 0x0D:
        name = dbf[pos:pos + 11].split(b"\0")[0].decode("ascii")
        fields.append((name, dbf[pos + 16]))
        pos += 32
    for i in range(count):
        start = header_len + i * record_len
        rec = dbf[start:start + record_len]
        if rec[0:1] == b"*":  # deleted
            continue
        row, off = {}, 1
        for name, length in fields:
            row[name] = rec[off:off + length].decode(encoding).strip()
            off += length
        yield row


def main(zip_path: str, out_path: str) -> None:
    with zipfile.ZipFile(zip_path) as z:
        dbf_name = next(n for n in z.namelist() if n.endswith(".dbf"))
        cpg = [n for n in z.namelist() if n.endswith(".cpg")]
        encoding = z.read(cpg[0]).decode("ascii").strip() if cpg else "utf-8"
        dbf = z.read(dbf_name)
    lines = set()
    for r in records(dbf, encoding):
        name, country = r["name"], r["adm0name"]
        lat, lon = float(r["latitude"]), float(r["longitude"])
        if not name or "\t" in name + country or not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise SystemExit(f"bad record: {r}")
        lines.add(f"{name}\t{country}\t{lat:.2f}\t{lon:.2f}\n")
    with open(out_path, "w", encoding="utf-8", newline="\n") as f:
        f.writelines(sorted(lines))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
