# SPDX-License-Identifier: GPL-3.0-only
"""Fetch the historical daily weather the simulation harness runs on.

Source: Open-Meteo Historical Weather API (https://open-meteo.com/en/docs/historical-weather-api),
data under CC BY 4.0, reanalysis from the Copernicus Climate Change Service.
Changes made: none beyond selecting columns and writing CSV.

Run from the repository root:  python3 engine-sim/tools/fetch_weather.py
"""

import csv
import json
import os
import urllib.parse
import urllib.request

SITES = {
    # name: (latitude, longitude). Two decimals, as the app sends.
    "belgrade": (44.82, 20.46),   # continental, the owner's balcony
    "seville": (37.39, -5.98),    # hot, dry summer
    "edinburgh": (55.95, -3.19),  # cool, wet, windy
}
START, END = "2015-01-01", "2024-12-31"
DAILY = [
    "temperature_2m_max", "temperature_2m_min", "precipitation_sum",
    "wind_speed_10m_mean", "relative_humidity_2m_mean", "et0_fao_evapotranspiration",
    "shortwave_radiation_sum",
]
OUT = os.path.join(os.path.dirname(__file__), "..", "src", "main", "resources", "weather")


def fetch(lat, lon):
    q = urllib.parse.urlencode({
        "latitude": lat, "longitude": lon, "start_date": START, "end_date": END,
        "daily": ",".join(DAILY), "timezone": "auto", "wind_speed_unit": "ms",
    })
    with urllib.request.urlopen("https://archive-api.open-meteo.com/v1/archive?" + q, timeout=120) as r:
        return json.load(r)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (lat, lon) in SITES.items():
        d = fetch(lat, lon)["daily"]
        path = os.path.join(OUT, f"{name}.csv")
        with open(path, "w", newline="") as f:
            w = csv.writer(f, lineterminator="\n")
            w.writerow(["date", "tmax_c", "tmin_c", "precip_mm", "wind_ms", "rh_pct", "et0_mm", "radiation_mj"])
            for i, day in enumerate(d["time"]):
                w.writerow([day] + [d[v][i] for v in DAILY])
        print(path, len(d["time"]), "days")


if __name__ == "__main__":
    main()
