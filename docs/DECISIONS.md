# Decisions

Each entry: the decision, and why. Newest last.

## D1. Default branch is `main`; gate work arrives by pull request

The first commit (G0 foundations) goes straight to `main` because an empty repository has nothing to branch from. Later work is proposed on a branch and merged by the owner.

## D2. Toolchain and library versions

Pinned in `gradle/libs.versions.toml` and `gradle/wrapper/gradle-wrapper.properties` (with the distribution checksum). Chosen as the newest stable release of each on Google Maven, Maven Central and services.gradle.org when G0 was set up: Gradle 9.9.2, Android Gradle Plugin 9.5.2, Kotlin 2.4.21, JDK 21, build-tools 36.0.0 (the AGP default, pinned explicitly). Stable means no alpha, beta, RC or milestone tag. The wrapper jar's SHA-256 matches the checksum Gradle publishes for 9.9.2.

`targetSdk` is 36 as the brief requires. `compileSdk` is 37.1 because the current stable AndroidX releases (Compose foundation 1.13, core 1.19, activity 1.13) refuse to build against anything lower. Compiling against a newer API level does not change runtime behaviour; only `targetSdk` does.

Room 2.8.5, SQLite 2.7.1 and DataStore 1.3.0 match the versions the brief lists as stable Kotlin Multiplatform libraries.

## D3. `kotlinx-datetime` 0.8.0

The brief marked its stability UNVERIFIED. Checked: 0.8.0 is the newest release on Maven Central; there is no 1.0. The library is still pre-1.0, so its API may change between minor versions. It is Apache-2.0 and is the only time library allowed in `:engine`, so it is used, but the engine keeps its use to dates (`LocalDate`, day-of-year arithmetic), which is the most stable part of the API.

## D4. `:engine` targets: JVM, `iosArm64`, `iosSimulatorArm64`

JVM is what Android and the simulation harness consume. The two iOS targets exist only so CI proves the engine compiles without the JVM (rule 5). Kotlin compiles Apple-target klibs on Linux with `kotlin.native.enableKlibsCrossCompilation`, so CI does not need a macOS runner. Linking an iOS binary does need macOS and is not attempted.

## D5. `minSdk` 26

The brief does not name one. Android 8.0 (API 26) is the first with notification channels, which every alert in this app needs, and it covers nearly all phones still in use. Lower would add compatibility code for no real users.

## D6. Dependency-licence check with Licensee

`app.cash.licensee` (Apache-2.0, Maven Central) runs as part of `check` on every module that ships, with an allow-list of Apache-2.0, MIT, BSD-2-Clause and BSD-3-Clause. A dependency with any other licence, or none, fails the build. `:engine-sim` is test tooling and is not checked.

## D7. Reproducible-build check runs on every CI run, not only on tags

The brief requires it on each tagged commit. It also runs on pull requests and on `main`, so a change that breaks reproducibility is caught when it is made, not at release time. The two builds use separate checkout directories and no build cache, so a path or cache dependence shows up as a difference.

## D8. Release builds use R8

`isMinifyEnabled` and `isShrinkResources` are on for release. R8 output is deterministic for fixed inputs, and CI's double build checks that.

## D9. No contact address published yet

The brief allows "GitHub issue or the owner's address" for commercial enquiries and lists "publisher and contact" for the privacy policy. The owner's address is personal data, so it is left out until the owner chooses which address to publish.

## D10. CLA is signed by a pull-request comment

A comment naming the commit of `CLA.md` and the contributor's full name is the simplest record that needs no third-party service or account. Subject to the owner's legal review.

## D11. Open-Meteo provides ET0, radiation and history (checks the brief's UNVERIFIED items)

Checked against the live API and its licence page (https://open-meteo.com/en/licence):
(a) daily FAO-56 Penman-Monteith reference evapotranspiration, `et0_fao_evapotranspiration`, is available on the forecast and the historical API; (b) daily `shortwave_radiation_sum` is available; (c) a historical archive exists at `archive-api.open-meteo.com`, with reanalysis from the Copernicus Climate Change Service. All API data is under CC BY 4.0. The licence asks for a link "next to any location Open-Meteo data are displayed", so the app's weather screens must carry "Weather data by Open-Meteo.com" with a link, not only the About screen.

Consequences: the engine uses the provider's ET0 when it is present and Hargreaves-Samani otherwise (MET Norway fallback). The simulation harness runs on real 2015-2024 history for three climates, committed under `engine-sim/src/main/resources/weather/` with attribution.

Still open: how Open-Meteo counts its daily limit (per address or per application).

## D12. Pot height and available water

The brief gives the height effect as -1.5 to -2.5 percentage points of container capacity per cm between 4 and 15 cm. Available water is roughly half of container capacity, so the engine applies +0.01 available-water fraction per cm below 15 cm, inside 4-15 cm only, clamped to 0.20-0.35. A pot 15 cm or taller has 0.30. Shapes without a measurement map to 8 cm (shallow), 15 cm (standard) and 25 cm (tall). All PRIOR. Pot-top area, needed for rain capture, is substrate volume divided by height.

## D13. Values the brief does not give, chosen as PRIORs

All in `Coefficients.kt` with their reasons:
- Dry fraction for wet-feet plants 0.3 and for epiphyte soak 0.6.
- Light-class typical lux when there is no reading: geometric mean of the class bounds (540, 2415, 7637), and 21 600 for direct.
- Indoor light-term floor 0.15; temperature effect 3 % per degC from 21 degC; dry-air factor 1.15; both bounded at +/-20 %.
- Winter mode: mean temperature of the last 7 days below 5 degC. Winter rules apply only to outdoor plants whose care profile says so (evergreen containers).
- Winter floor: no rain refill for 14 days and no soil check for 14 days. The brief's "dry, windy, sunny spell" is reduced to "dry", because there is no solar radiation from MET Norway and the check is a safety net.
- Root-freeze alerts 2 degC earlier for pots under 10 litres or wind-exposed places.
- Waterlogging caution after 3 consecutive days of at least 2 mm on an open, undrained pot.
- Rain hold: "water now" is held for a day when tomorrow's expected rain refill covers at least half the deficit.
- Observation noise 0.5 log units, 0.4 when an undrained pot is reported too wet; variance bump 0.10 after a move or repot, 0.05 after 3 gap-filled days, 0.05 after a surprising answer; "water now" instead of "check the soil" below a log-K standard deviation of 0.2.

## D14. Learning: how three of the brief's rules are read

1. **"If the predicted depletion lies inside the reported band, do not update."** K is not moved, but the variance shrinks as for an observation with zero error. Without this the engine never becomes confident and keeps asking: in the first harness run it prompted twice as often as the baseline and 57 % of answers were "still wet".
2. **"Increase variance after a surprising answer."** Surprising means any disagreement while the engine is confident, or a disagreement larger than two standard deviations at any time. Without the first test, agreeing answers shrink the variance until real errors can no longer move K, and the engine kept disagreeing with the person indefinitely.
3. **"Require two consistent answers before increasing water."** Two answers asking for the same direction of change. An agreeing answer in between does not cancel a pending one; an opposite one does.

Process noise was lowered from 0.0005 to 0.0001 per day: at 0.0005 a year without answers returns the standard deviation almost to its starting value.

## D15. Limits of learning from four answer bands (for the owner)

Found by the property tests, not designed in:
- **Dead zones.** Updates move log K only to the nearest band edge, and not at all when the answer agrees. Where the truth and the prediction share a band at the prompt, K stops. For dry fractions in the middle of the "ok" band (0.4-0.6), K can stay off by up to about a factor of two while every answer agrees. Near a band edge, corrections shrink towards zero. The plant is not harmed (harmful answers stop within 21 answers in 1000 random plants), but K is not exact.
- **Wet-feet plants cannot learn from answers.** They are prompted at depletion 0.3, the edge between "too wet" and "ok", so no answer disagrees with the prediction.
- The kitchen-scale calibration is what pins K down further.

Possible changes, none made because each departs from the brief: update towards the middle of the reported band instead of its edge; a fifth answer band, or a finer scale for wet-feet plants; occasional check-ins at a deliberately chosen depletion where answers are informative.

## D16. Simulation harness design

- Truth richer than the engine: hourly demand shaped by sun angle and vapour-pressure deficit and scaled by Penman-Monteith ET0; random plant coefficient (0.35-1.2), available water (0.25-0.35), terracotta wall loss, rain capture and wind sensitivity; uptake falls as soil dries; rain at a pseudo-random hour, with the first millimetre intercepted.
- Person: answers with a personal bias (sd 0.08) and noise (sd 0.12), acts on 90 % of prompts, and gives a moisture answer with 25 % of waterings.
- The baseline's summer interval is the engine's own dry-out time for the plant on the site's mean July day at K = 1, so both start from the same knowledge. The brief gives the baseline's rules but not where its summer interval comes from; the care database has no "water every N days" field by design.
- The models see a perfect 7-day forecast. Real forecasts are worse; the harness shows robustness, not real-world accuracy.
- "Check-ins until accurate" is reported as answers (check-ins plus answers given when watering), because the baseline never asks for a check-in.

## D17. Feeding and repotting reminders are not in G1

They are rule-based reminders that need the care database's feeding months, so they come with G2/G3 instead of the engine gate.
