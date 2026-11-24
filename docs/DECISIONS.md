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

1. **"If the predicted depletion lies inside the reported band, do not update."** K is not moved, but the variance shrinks, no further than the band's width allows (see D18). Without the shrink the engine never becomes confident and keeps asking: in the first harness run it prompted twice as often as the baseline and 57 % of answers were "still wet".
2. **"Increase variance after a surprising answer."** Surprising means any disagreement while the engine is confident, or a disagreement larger than two standard deviations at any time. Without the first test, agreeing answers shrink the variance until real errors can no longer move K.
3. **"Require two consistent answers before increasing water."** Two answers asking for the same direction of change. An agreeing answer in between does not cancel a pending one; an opposite one does. While K waits, the bucket is still set to what the person reported.

Process noise was lowered from 0.0005 to 0.0001 per day: at 0.0005 a year without answers returns the standard deviation almost to its starting value.

## D15. Limits of learning from four answer bands

Found by the property tests. With the brief's rule (move log K to the nearest band edge), corrections shrank towards zero near band edges, K could stay off by up to about a factor of two, and wet-feet plants (prompted at 0.3, the edge between "too wet" and "ok") could not learn at all. The owner was asked and allowed a change to the plan: see D18.

What remains after D18: an answer that agrees does not move K, so where truth and prediction share a band at the prompt, K can still stay up to about a factor of two off (in 1000 random plants: median final error 7 %, 90th percentile a factor of 1.6, worst a factor of 2). Wet-feet plants learn from "too wet" and "dry" answers but not from "ok". The kitchen scale is what pins K down further.

## D16. Simulation harness design

- Truth richer than the engine: hourly demand shaped by sun angle and vapour-pressure deficit and scaled by Penman-Monteith ET0; random plant coefficient (0.35-1.2), available water (0.25-0.35), terracotta wall loss, rain capture and wind sensitivity; uptake falls as soil dries; rain at a pseudo-random hour, with the first millimetre intercepted.
- Person: answers with a personal bias (sd 0.08) and noise (sd 0.12), acts on 90 % of prompts, and gives a moisture answer with 25 % of waterings.
- The baseline's summer interval is the engine's own dry-out time for the plant on the site's mean July day at K = 1, so both start from the same knowledge. The brief gives the baseline's rules but not where its summer interval comes from; the care database has no "water every N days" field by design.
- The models see a perfect 7-day forecast. Real forecasts are worse; the harness shows robustness, not real-world accuracy.
- "Check-ins until accurate" is reported as answers (check-ins plus answers given when watering), because the baseline never asks for a check-in.

## D17. Feeding and repotting reminders are not in G1

They are rule-based reminders that need the care database's feeding months, so they come with G2/G3 instead of the engine gate.

## D18. Learning update: interval likelihood instead of nearest band edge (departs from brief 5.7, approved by the owner)

The owner allowed a change to the brief's learning rule when the dead zones in D15 were reported.

A disagreeing answer is now treated as what it says: the true depletion lies somewhere in the band. If the engine predicted depletion d, the true depletion is about d x exp(delta), where delta is the error in log K, so the answer bounds delta to [ln(low/d), ln(high/d)], blurred by the person's error (observation noise 0.5). The normal belief about delta is multiplied by that likelihood and summarised again by its mean and variance (moment matching, by quadrature on 801 points; deterministic and `commonMain`-only). "Bone dry" is open above, because an empty pot hides how long it has been empty.

An agreeing answer keeps K where it is (the brief's rule) and shrinks the variance only as far as the same likelihood allows. Moving K towards the band's middle on agreeing answers was tried and rejected: K crept until the plant was reported too wet, again and again.

Everything else is kept: the asymmetry and the two-answer rule, the surprise rule, the clamps, "watered" without an answer never changing K.

Measured (property test, 1000 random plants mis-specified by a factor of two):

| | Nearest band edge (brief) | Interval likelihood (now) |
|---|---|---|
| Harmful answers stop by | answer 21 | answer 9 |
| Wet-feet plants learn | no | partly |

Measured (simulation harness, stress days per plant-year, outdoor, engine with provider ET0):

| | Nearest band edge | Interval likelihood | Baseline |
|---|---:|---:|---:|
| Belgrade | 14.6 | 18.2 | 50.4 |
| Seville | 16.8 | 22.9 | 39.7 |
| Edinburgh | 15.2 | 18.7 | 39.8 |

Waterings of still-wet soil fell (Belgrade 0.6 to 0.3 a year). Stress days rose because the simulated person's answers are noisy and biased, and "too wet", which the brief says to act on at once, now moves K further. That is a real trade-off for the owner to weigh; G5's kitchen-scale data is the way to settle it. Reverting is a one-function change in `Learning.kt`.

## D19. G3 storage: replay, not stored state

Nothing the engine derives is stored. Each plant's state is replayed from its first day over the stored daily weather and its events. The engine is deterministic and a few years of days take milliseconds, so this costs nothing. It means an engine change, an import or a corrected event always gives a consistent result, and there is no state to migrate. Each day's last stored forecast is that day's pseudo-observation (brief section 7); gaps are filled from five-year monthly means from the Open-Meteo archive, fetched once per place in the foreground.

## D20. HTTP without a library

`:weather` defines a one-method `HttpTransport`; the app implements it with the platform's `HttpURLConnection`. All network rules live in shared code and are tested with a fake transport, and no HTTP library is added.

## D21. Notifications through WorkManager, no exact alarms

Prompts and alerts are planned ahead from the cached forecast at every fetch and every change, as WorkManager one-time jobs with a delay. No exact-alarm permission. WorkManager restores its jobs after a reboot, which covers "reschedule on boot". Each job re-checks that its prompt is still due when it fires. Times (PRIOR): prompts at 09:00; winter soil checks (November-March) at 12:00, the brief's midday window; weather alerts at 18:00 the evening before, so there is time to move or wrap a pot; each alert once per plant, type and day. If the newest forecast is 3 days old, one notification asks the person to open the app, where MET Norway may be used.

## D22. "Watered" from a notification

A notification cannot ask whether water ran out of the bottom. The dose shown already includes extra to run through a drained pot, so "Watered" from a notification records `ran out = yes` when the pot has drainage holes and `no` when it has none. In the app, the question is asked.

## D23. Weather fetch limits

Open-Meteo is asked at most once an hour per place, in any state; in the background once a day, at this install's own minute of the day (from a random number drawn once and kept). After 3 consecutive Open-Meteo failures, or any rate limit, the app falls back to MET Norway, only in the foreground, and probes Open-Meteo again before each MET request. MET Norway backs off 10 minutes, doubling to 6 hours, on errors; it honours `Retry-After` on 429, and waits 24 hours after a 403, which it reports as a bug.

## D24. Before the care entry is reviewed

The compiled asset holds only reviewed entries, and none is reviewed yet. Until then the app offers "General defaults (no care data)", the engine's own defaults (dry top part, no winter rules). As soon as an entry is reviewed and the submodule re-pinned, the species and its profile appear in the picker.

## D25. Pot watering point for Chamaecyparis lawsoniana

The owner did not choose between 50 % and 40 %. Kept at the archetype's 50 % (the brief's PRIOR for a conifer in a pot); the kitchen-scale log decides.

## D26. Place entry

Places are found by city name through Open-Meteo's geocoding API (CC BY 4.0, foreground, on the person's request), or by typing coordinates. No location permission is requested in G3.

## D27. Indoor places

From G4 the place editor offers Indoors or Outdoors. An indoor place takes a light class (low, medium, high, direct; medium preselected so one is always stored) and a dry-air switch, and hides rain and wind exposure. Indoor places reuse the same city search and coordinate fields as outdoor ones: the engine still needs the latitude for day length, and one location input is less to build and to learn than a second one. No weather is fetched for an indoor place, so a garden of only indoor places makes no network request and the Today screen shows no forecast age, credit or refresh for it.

Indoors the engine reads only each day's date, so the planner replays an indoor plant over one day per date at the indoor reference temperature (`INDOOR_TEMP_REFERENCE_C`), which keeps the temperature-based heat and winter rules neutral, instead of over stored weather. Weather stored for a place that is switched from outdoors to indoors stays in the database but is not used while the place is indoors.

## D28. Light check reports a class only

On an indoor place, "Check the light" reads the phone's ambient light sensor (`Sensor.TYPE_LIGHT`, framework API, no permission) for about 3 seconds, takes the median of the readings, and selects `WaterBalance.lightClassOf(median)` in the light choice, where the person can still change it. The message names the class and never shows a lux number. A device without a light sensor does not show the action; no reading within the 3 seconds shows "No reading; try again" and changes nothing. A second check replaces the first selection.

The reading is not stored: the check sets `lightClass` only and leaves `lux` null. A single momentary reading is not a day's light (time of day, clouds, the angle the phone is held at), so it must not replace the class's typical lux in the engine. For the same reason the class chosen in the editor is authoritative: saving an indoor place sets `lux` to null, so a lux value stored earlier (for example by an import) cannot silently override the class the person picked. The 3-second window is a UI choice, not a model constant, so it lives in the app beside the check.

## D29. Feeding and repotting reminders

Reminders come from the care database's season fields (D17), resolved like the watering profile: the species' block for the plant's context (indoor or outdoor container), else its archetype's defaults. A field that is missing gives no reminder of that kind; feeding needs both the months and the interval.

- Feeding is due when the current month is a feeding month and there is no FED event within `feeding_interval_weeks` (or none at all). South of the equator (place latitude below 0) the northern-hemisphere months are shifted by six months.
- Repotting is due when `lastRepotOn`, else the plant's acquired date, plus `repot_interval_months` is on or before today. The existing "Repotted" action clears it.

Feeding is recorded with a new event type, FED, logged by a "Fed" button on the plant screen. It is a new value of the existing `type` column, so there is no schema change; it changes no watering state, and export and import carry it. The owner accepted that older builds cannot import an export that contains FED ("Older builds are allowed to be broken, noone is using it").

The owner chose to show reminders both on the Today list and in the notification. A due reminder appears on the plant's Today card and on its screen. A plant has one notification, which carries its watering prompt, its reminders, or both; watering prompts are scheduled and posted as before. A watering prompt includes the reminders due that day. Reminders are also scheduled ahead, at 09:00, each time the app recomputes (any logged action, an app refresh, or the daily background refresh): today's while they are due and not yet shown (at once if 09:00 has passed, so a job delayed past a recompute is not lost), else on the next day a reminder is due, which the planner works out from the current log (the end of the feeding interval moved into the next feeding month, the repot date). When the job fires it recomputes the plant and posts reminders only, never the watering prompt: nothing if a feed or repot logged since has cleared them, if they were already shown that day, or if a watering prompt is due that day (the prompt's own job carries them, at its usual time). A posted reminder is recorded as shown for that day, so reminders alone appear at most once a day, without the Watered and Snooze buttons. The job then queues the plant's next reminder itself, so the chain continues without a weather refresh or network. WorkManager can still deliver late when the device is off or in deep idle at 09:00; the Today list shows due reminders at any time.

The shipped asset has no reviewed entry with feeding data yet, so no reminder appears on a device until one is reviewed.

## D30. Plant photos

The owner asked for photos and a timeline of each plant kept on the phone, possibly to be used later. He chose that photos come from the camera app or the gallery ("Camera and gallery"), and that the garden export includes them as a zip ("Yes, export as a zip"; older builds may break, nobody uses them). Nothing analyses the photos now; each keeps its plant and its date so later uses stay possible.

- Adding: "Add photo" on the plant screen offers "Take a photo" (`ActivityResultContracts.TakePicture`, written by the camera app into `cacheDir/camera/` through a `FileProvider` that shares only that folder) or "Choose from gallery" (`PickVisualMedia`, the system photo picker). No permission is added. The picked or taken image is copied into the app's own storage, so deleting it from the gallery does not remove it from the plant.
- Storage: `filesDir/photos/`, one file per photo under a random name (`<uuid>.jpg` or `.png`; no extension when the format is neither). This folder was already excluded from Auto Backup and device transfer in `data_extraction_rules.xml` and `backup_rules.xml`, so a photo leaves the phone only in an export the person starts. The image is stored byte for byte as taken; only one above 4 MB is decoded (power-of-two subsampling, then an exact scale combined with the upright rotation in one step, so its long side is at most 3000 px) and re-encoded as JPEG 90, a size the owner can revisit. An image too large to decode in memory is refused with a message rather than crashing; so is an empty file, or a camera that reports success without writing one.
- Date: the EXIF date the photo was taken, when it has one that is not in the future, else today. A photo from the gallery thus lands on the day it was taken.
- Timeline: the plant's history lists events and photos together, newest first by date; on the same day the events come before the photo. Each photo can be deleted from there. The history shows the newest 30 photos as thumbnails at most 240 dp on the long side, decoded at that size; older photos stay stored and exported. Because the photo files are excluded from Auto Backup while the database is not, a restored phone has photo rows without files: the history then says the photo is not on this phone. Only the export zip carries photos to another phone.
- Deleting a photo deletes its file. Deleting a plant, or a place with its plants, deletes their photo files after the database rows are gone.
- Database: version 2 adds the `photo` table (`id`, `plantId`, `date`, `file`) and changes nothing else, so Room's `AutoMigration(from = 1, to = 2)` applies; existing places, plants and events are kept. There is no destructive fallback. `MigrationTest` builds a version 1 database from the exported `1.json` schema and opens it with the version 2 code.
- Export: format version 2 adds a `photos` list to the JSON. The export is a zip: `garden.json` at the root (the same JSON as before) and each photo file as `photos/<file>`. Import accepts that zip and a plain `.json` export from older builds (detected by the zip's `PK` signature). A photo listed in the JSON whose file is not in the zip is left out and counted, and the rest is imported; the app says how many were left out. Import still replaces everything or nothing: an entry whose path is absolute or contains `..`, a photo entry that is not a plain file name, or a photo whose `file` in the JSON is not a plain file name refuses the whole file before anything changes. New photo files are copied in before the database transaction, and every file the import created is removed again if a copy or the transaction fails; the old ones are deleted only after it succeeds. A live photo file is never overwritten: names are random, so a name already in use is the same photo and its file is kept. The zip is bounded before anything is kept in memory or on disk: `garden.json` (and a plain JSON file) at most 32 MB, each photo at most 32 MB, 4 GB in all and 20 000 entries, counting the bytes actually inflated rather than what the zip claims; a larger file is refused and changes nothing.
