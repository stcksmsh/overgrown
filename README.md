# Overgrown

Overgrown is a free, open-source Android app that tells you when and how much to water each of your plants, and when to protect them from weather.

It works from three things: care data for the species, the plant's actual situation (pot, place, light, local weather), and your own feedback, from which it learns each plant. It runs entirely on the phone. There is no account, no server, no analytics and no advertising.

"Overgrown" is a working name.

## Status

Early development. Nothing is released. The engine and its proof come before the interface; see `docs/DECISIONS.md` for what has been decided and why.

## How it is built

The code in this repository is written by AI coding agents under the direction of the owner, Kosta Vukićević, who reviews it. Model constants are labelled in the source with their status (a published fact or a tunable starting guess) and their source.

## Layout

| Module | Contents |
|---|---|
| `:engine` | Water-balance model, alert rules, learning filter, baseline model. Kotlin Multiplatform `commonMain` only. |
| `:engine-sim` | Simulated-year harness, metrics and property tests. Never shipped. |
| `:weather` | Weather providers (Open-Meteo, MET Norway), aggregation, cache. |
| `:caredb` | Loader for the compiled care database asset. |
| `:data` | On-device storage, export and import. |
| `:app` | The Android app. |

The care database lives in a separate repository, `overgrown-data`, under AGPL-3.0, and is included here as a git submodule.

## Building

JDK 21 and the Android SDK (platform 36) are required.

```
./gradlew build
```

The `foss` flavor is the default and is the build distributed outside Google Play.

## Licence

GPL-3.0-only. See `LICENSE`. Contributions require a signed contributor licence agreement; see `CONTRIBUTING.md`.
