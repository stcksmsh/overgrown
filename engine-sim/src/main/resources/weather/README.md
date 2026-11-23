# Historical weather for the simulation harness

Weather data by [Open-Meteo.com](https://open-meteo.com/), Historical Weather API, licensed under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Reanalysis data from the Copernicus Climate Change Service (C3S).

Daily values 2015-01-01 to 2024-12-31 for Belgrade (44.82, 20.46), Seville (37.39, -5.98) and Edinburgh (55.95, -3.19). Changes made: columns selected and written as CSV; no values altered. Fetched by `engine-sim/tools/fetch_weather.py`.

Used only by the test harness; never shipped in the app.
