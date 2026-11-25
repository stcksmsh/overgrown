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
- Export: format version 2 adds a `photos` list to the JSON. The export is a zip: `garden.json` at the root (the same JSON as before) and each photo file as `photos/<file>`. Import accepts that zip and a plain `.json` export from older builds (detected by the zip's `PK` signature). A photo listed in the JSON whose file is not in the zip is left out and counted, and the rest is imported; the app says how many were left out. Import still replaces everything or nothing: an entry whose path is absolute or contains `..`, a photo entry that is not a plain file name, or a photo whose `file` in the JSON is not a plain file name refuses the whole file before anything changes. New photo files are copied in before the database transaction, and every file the import created is removed again if a copy or the transaction fails; the old ones are deleted only after it succeeds. A live photo file is never overwritten: names are random, so a name already in use is the same photo and its file is kept. The zip is bounded before anything is kept in memory or on disk: `garden.json` (and a plain JSON file) at most 32 MB, each photo at most 32 MB, 1 GB in all and 20 000 entries, counting the bytes actually inflated rather than what the zip claims; a larger file is refused and changes nothing. Photos are unpacked to a staging folder and then copied, so an import briefly needs about twice their size; before copying, import checks that the photos plus a 64 MB margin fit in the free space and otherwise refuses the file. The rollback and the cleanup of old and staged files run to the end even if the import is cancelled.

## D31. Design spec v1 and the owner's UI decisions

The owner reviewed the planning package with an outside planning agent and returned a design spec, now `docs/DESIGN.md`, with reference mockups (Today, Plant, Add plant step 1) in `docs/design/mockups/`. Where the mockups and the spec disagree, the spec wins. The spec's section 9 records his decisions:

1. SIL OFL 1.1 is allowed for bundled fonts only. The licence gate (D6) gains that one exception; the bundled typeface is Bricolage Grotesque, listed in the notices.
2. "Use my location" is added, which reverses part of D26: approximate location only, requested on tap, optional, coordinates rounded to about 1 km, the nearest city name from a bundled offline list. City search stays.
3. Two bottom tabs, Today and Plants; Places and Settings open from a top-bar icon.
4. Soil answers on Today cards and in notifications are Wet / OK / Dry; the plant screen also offers "Bone dry". A quick "Dry" covers the Dry and Bone dry bands together. D18's interval likelihood works on any [low, high] interval, but the code needs a small change before this ships: `Learning.band()` maps each `Moisture` value to its band and `update()` treats only `Moisture.BONE_DRY` as open above, so a quick "Dry" needs its own answer value whose band runs from the Dry band's lower edge and is open above, with the open-above test keyed to the band rather than to `BONE_DRY`, and tests. The learning rule itself does not change.
5. Pot size is asked as the width across the top plus a shape; the volume is computed and editable under "Advanced".
6. "Not sure" for rain and wind stores partly and normal; learning exposure from answers after rain is after V1.
7. Cards and tiles use the care profile's glyph; a plant photo appears only on the plant screen in V1.
8. Resting tiles are surface coloured with a moss chip.
9. Indoor plants are in V1.
10. Launch coverage: about 12 owner-reviewed archetypes and a table of about 300 common plant names mapped to them; archetypes are reviewed before species entries.
11. Weighing (calibration) moves from developer options into Settings; the engine starts using weights after V1.
12. The trademark check and the lawyer review of the licences start now, in parallel.
13. The repositories go public at the first beta.

Screenshot tests (Roborazzi or Paparazzi) of every component state and screen, in light and dark and at 1.0x and 1.3x font scale, are what the owner reviews.

## D32. Design foundation: theme tokens, bundled font, screenshot tests

**Licence gate exception (D6, per D31.1).** SIL OFL 1.1 is allowed for bundled fonts and for nothing else. The only such font is Bricolage Grotesque, version 1.001, taken from the typeface's upstream repository https://github.com/ateliertriay/bricolage at commit `84745e5b96261ae5f8c6c856e262fe78d1d6efdd` (the commit Google Fonts imports from): the static `fonts/ttf/BricolageGrotesque-Regular.ttf`, `-SemiBold.ttf` and `-ExtraBold.ttf` (the three weights the six type styles use) and `OFL.txt`. The fonts are in `app/src/main/res/font/`, the licence text ships as `res/raw/ofl_bricolage_grotesque.txt`, and Settings, About names the font and shows the licence. Licensee checks Maven dependencies only, so its allow-list is unchanged; a font arrives only by this route and needs this entry. Static instances were chosen over the variable font because they need no variation settings and render the same under Robolectric.

**Tokens.** Every colour, type style, radius and spacing value from `docs/DESIGN.md` sections 2-4 is in `app/src/main/kotlin/com/kostavukicevic/plants/ui/theme/Theme.kt`, each hex value once. Ranges in the spec are kept as two tokens: card padding 16 and 18, bar radius 5 and 6. The Material 3 colour, type and shape slots map onto these tokens so the existing screens take the new look without layout changes: primary is ink (primary buttons are ink), the surface containers are all surface, and every type slot is one of the six styles.

**Error text in dark.** The existing screens write warnings in the Material `error` colour. Brick as text fails in dark (2.91:1 on dark surface, 3.32:1 on dark ground), so dark error text uses the brick-tint value `#F3D9CF` (over 12:1 on both). Light error text is brick (4.83:1 on ground, 5.54:1 on surface). No new colour is introduced. This goes away when later slices replace those lines with brick alert cards.

**Contrast check.** `ContrastTest` checks every text/fill pair at 4.5:1, and the gauge outlines, gauge fill and tint chips at 3:1, in both themes. All dark values from DESIGN.md pass as written, so none changed. The `outline` role (secondary-button borders) is not a text or gauge colour and is not checked; it measures 1.61:1 light and 1.52:1 dark.

**Screenshot tests.** Roborazzi 1.79.0 with Robolectric 4.18.1 (test-only, Maven Central, Apache-2.0), plus Compose `ui-test-junit4` from the existing Compose BOM. Every unit-test run compares against the goldens in `app/src/test/snapshots/` with Roborazzi's default comparator, so `./gradlew build` fails when an image changes; `-Proborazzi.record=true` re-records them. The Roborazzi Gradle plugin is not used: two system properties do the same job.

**PotGauge on brick and moss (owner's choice).** DESIGN.md section 5 draws a gauge on a state block in the block's text colour with a white empty part. On brick and moss the text colour is white, so the pot would be solid white at every level. The owner compared captures of a transparent empty part and a translucent (30 % alpha) white one, and chose transparent over both that and the spec-literal white: on white-text blocks the empty part shows the block colour, with a white outline and fill (white on brick 5.54:1, on moss 6.37:1; checked in `ContrastTest`). Sky, marigold and surface follow the spec as written. A full gauge fills to the rim line.

## D33. Glyphs (PR #20)

Six vector drawables from the add-plant mockup's SVGs. Stems are drawn as 3-unit round-capped strokes, because the mockup's zero-width stems left the fern blank. The succulent rosette is scaled 0.85 about its base so it fits the 48-unit box. A plant with no archetype, or an unknown one, gets the leafy glyph; the owner can change this fallback. On the moss-tint chip the glyph uses the fixed dark ink, because moss-tint is the same in both themes and the dark theme's light ink would not show on it.

## D34. Navigation (PR #21)

One ⋮ menu in the top bar holds Places and Settings, rather than two icons, because the app has no icon assets and the menu keeps the bar uncluttered. The two tabs are plain selectable boxes in theme roles, not Material's NavigationBar, for the same reason. A place that still has plants cannot be deleted: `Garden.deletePlace` would delete its plants too, so the app names them instead. Delete with Undo hides the item and deletes it only when the snackbar closes without Undo; the snackbar is `SnackbarDuration.Short`, because Material makes a snackbar with an action indefinite, so the delete would never run. If the activity is recreated while the snackbar shows, the delete is dropped and the item comes back (safe direction). A delete that fails brings the item back and says so.

## D35. Alert events, Done and Dismiss

D33 and D34 were not on `main` when this was written, so this entry is D35.

**Grouping.** Today shows one alert card per weather event (`docs/DESIGN.md` section 5, AlertCard). `alertEvents(statuses, today)` in `data/.../AlertEvents.kt` turns each plant's per-day alerts into events: one kind over a run of consecutive days, with every plant that has that kind on any of those days. Root-freeze caution and strong are one kind, cold; the event's severity is its strongest day. Heat, heavy rain, wind and waterlogging are the other kinds. A day without the kind ends the run, so the same kind on Mon-Tue and Thu is two events. Plants in different places share an event, because the card names plants, not places. Days before today are ignored. The function is pure; storage is separate.

**Done and Dismiss (owner's decision).** The card has two buttons. Done ("I protected the plants") hides the event for its whole range; it shows again only if it gets more severe (cold caution to strong) or a plant that was not in it at Done time joins it. Dismiss ("not now") hides it for the day it was pressed; it shows again the next day if still forecast, and at once if it gets more severe the same day (a frost warning hidden by "not now" is the dangerous case). Pressing Done again on an event that came back replaces the earlier Done, so the new severity and plants are the baseline.

**Event identity.** A stored Done or Dismiss matches an event of the same kind whose date range overlaps the stored range. Kind plus start date was rejected because a refresh that drops or adds the first day would lose the Done; kind plus plant set was rejected because a new plant joining must bring the event back as the same event, not hide it as a different one. When a Done matches an event that now ends later, the stored end moves to the event's end, so an event extended by a refresh (Mon-Wed to Mon-Thu) stays hidden until its new end rather than reappearing on Thursday. The cost of overlap matching: a new event of the same kind that touches a Done event's range is treated as the same event, and is hidden unless it is worse or reaches a new plant.

**Storage.** One DataStore Preferences key, `alert_acks`, holding a JSON list of `{kind, start, end, severity, plantIds, dismissedOn}` (`dismissedOn` null for Done). No database change. Each read prunes entries whose range ended before today and dismissals from earlier days, in the same DataStore transaction, and writes only when something changed: a garden with no alerts stores nothing. An unreadable value counts as no entries, so the alerts show again instead of the app failing.

## D36. Care-data pin moved to the indoor archetypes

The `overgrown-data` submodule moves from `06d0079` to `7756c25` (data PR #7), which marks the four indoor archetypes `reviewed`. The compiler ships only `status: reviewed` entries, so the asset now holds five archetypes: `hardy-potted-conifer`, `indoor-epiphytic-orchid`, `indoor-moisture-loving`, `indoor-succulent`, `indoor-tropical-foliage`. The species entries (`dracaena-trifasciata`, `chamaecyparis-lawsoniana`) stay draft and are not in it.

`CareDataPinTest` (in `:data` jvmTest) reads the pinned YAML and asserts exactly those five archetypes are reviewed and none is draft, and that the snake plant species is draft. It tests the source files rather than the compiled JSON because the compile task lives in `:app` and a test in `:caredb` or `:data` cannot reach it without a build change; the compiler's only filter is the same `status` field. The compiled `caredb.json` was also inspected once by hand and holds the five ids and no species. The older tests use inline fixture JSON and did not assert the shipped set, so none needed updating.

## D37. CI off until close to V1 (owner's decision)

The owner asked to switch CI off and drop it as a merge requirement until the app is close to V1, because each CI run took about 12 minutes and every fix round or merge of `main` triggered another, which made iteration slow. The `CI` workflow is disabled in GitHub (`gh workflow disable CI`; the workflow file is unchanged, so `gh workflow enable CI` restores it). `.nonslop.toml` sets `[merge] allow_no_ci = true`, so the coordinator's merge check accepts a pull request with no checks. Every builder brief still requires the full test command (`./gradlew build :engine:compileKotlinIosArm64 :engine:compileKotlinIosSimulatorArm64`) to pass locally, and the reproducible-build check (D7) does not run until CI is back. Re-enable both before the first beta.

## D38. Add-plant wizard

The single add/edit plant form is replaced by four steps (`ui/AddPlantWizard.kt`, `docs/DESIGN.md` section 6): what plant, where, the pot, sun and rain. Editing a plant opens the same steps filled in, with Save on every step. `EditPlantScreen` is removed; `EditPlaceScreen` stays for the Places list. No schema change.

**Pot geometry.** The pot is modelled as a frustum whose base is 0.75 of the top width, with height over top width 0.5 (shallow), 0.9 (standard) and 1.5 (tall). Litres = pi h (D^2 + Dd + d^2) / 12 / 1000. A 20 cm standard pot comes to about 4.4 litres. These are geometry, not care data, so they sit in the UI file. The width is not stored: the wizard stores the computed height in `potHeightCm` (which the engine already uses for the column height), and editing reads the width back from it, or from the volume for plants saved before the wizard. A stored volume more than 1 % off the computed one is shown as the Advanced litres override. Stored numbers are shown to six significant digits, so typed values such as 0.25 L come back as typed. Saving an edit keeps the stored volume, height and plant width unless the width, shape, litres or plant width field differs from what was shown; only then are they computed again. Widths of 0, empty, or above 300 cm are refused, for the pot and for the plant.

**Step 1.** The name search covers species (common and accepted names) and, because no species ship yet (D36), archetype names too. Picking a result fills the name, shows the pick as a moss row with Change, and stops searching while the name is edited, so "No match" never shows under a pick. A typed name with no match is kept as the name. Herb has no archetype, so picking it means the general care rules. Selected tiles are moss with a check mark and no border (DESIGN wins over the mockup's outlined tile).

**Step 2.** No places of the chosen kind means the new-place form shows at once. The new place asks a name and a city; coordinates are only under Advanced. "Use my location" (DESIGN section 9, item 2) is not in this slice. A failed city search says it cannot search right now. A new outdoor place triggers a weather refresh on save, as `EditPlaceScreen` does, but the screen leaves as soon as the plant is written: the fetch runs in a scope that outlives the screen, and a failed fetch is only logged (Today already shows a failed fetch).

**Step 4.** Exposure is the place's, so answering for an existing place changes that place; when other plants share it the step names them. Rain Yes / A little / No / Not sure stores OPEN / PARTIAL / COVERED / PARTIAL; wind Yes / No / Not sure stores EXPOSED / SHELTERED / NORMAL. "Not sure" is not recorded separately yet (DESIGN asks for it; it needs a column, so it waits for a schema change). Indoors, "Does sun fall on the leaves?" Yes / A little / No / Not sure sets the light class to DIRECT / HIGH / MEDIUM / MEDIUM, which the light choices and the light check can then change. Changing an indoor place's class clears its stored lux, which the engine would otherwise use in place of the class (`WaterBalance`), as `EditPlaceScreen` does.

**Not asked.** Root-bound and the place's dry-air flag are not wizard questions; editing keeps their stored values.

**State.** All answers and the step are saved with the instance state, so rotation and process death keep them. Back on step 1 leaves without saving; system back goes to the previous step. Save is idempotent: the written plant's id is kept in the saved state and a created place becomes the selected place, so saving again after a rotation updates them instead of adding another. Editing a plant deleted elsewhere says so and offers only Back. A stored species this build's database lacks shows the stored name as the pick, without searching.

## D39. Settings: reminder time, theme, import confirmation, weighing

Settings now holds what `docs/DESIGN.md` section 6 lists, with the owner's answers S1-S3 (`docs/STATE.md`). New keys in the existing DataStore: `reminder_minute` (minutes after midnight), `theme` (`SYSTEM`, `LIGHT` or `DARK`) and `weighing`. A missing or unreadable value reads as the default.

**Reminder time.** One time a day, default 08:00 (S2), for every watering prompt (water, soil check, repot-or-water-daily) and for the feeding and repotting reminders: the owner asked for one reminder time, so the 09:00 they used before is replaced, not kept beside it. The winter soil check at 12:00 is dropped rather than moved to the chosen time plus three hours: it was a fixed midday window, and an offset from a time the person picks (say 20:00) lands somewhere meaningless; one time is also what the owner asked for. Weather alerts stay at 18:00 the evening before, because their point is time to move or cover pots before the night. Changing the time reschedules all pending work at once. A prompt or reminder whose time today has already passed waits for the same time tomorrow instead of posting at once; before, any prompt for today was posted within a minute on every reschedule after 09:00, which also meant every app resume posted it again. A watering prompt is now recorded as shown for the day (one SharedPreferences value per plant, the last day shown, so nothing accumulates), and neither the scheduler nor the worker posts it again that day. After posting, the job arms the next day's at the chosen time, so an unanswered prompt comes back each morning without waiting for the daily refresh; a reschedule that finds today's prompt already shown arms tomorrow's too. A prompt shown yesterday and still unanswered keeps its daily slot: if a late job or a reschedule means that slot has passed today, it is posted at once rather than skipped to tomorrow, so an ignored prompt never drops to every other day. The shown-prompt record is cleared on import (plant ids are reused) and for plants that no longer exist. The cost: a prompt that first becomes due after the chosen time (a forecast change found by the daily refresh in the afternoon) is notified the next morning; the Today screen shows it at once.

**Theme.** Follows the system by default, with Light and Dark as choices (S3). The choice is read before anything is drawn, so a chosen theme does not flash the system's first, and a change applies at once without restarting the activity. The system bar icons follow the app's theme, not the system's. No units switch: the app is metric only (S1), and Settings says so in one line.

**Import confirmation.** Import opens a brick dialog ("Replace your plants?") before the file picker; Cancel, back and a tap outside leave everything as it is, and Cancel is the filled button.

**Weighing.** Pot weighing (`DESIGN` 9.11, D31.11) has its own switch in Settings; it shows the weighing card on each plant's page, and the engine does not use the weights before V1. Until the switch is first set it follows developer mode, which showed the card before, so a phone that had developer mode on keeps the card. Developer options remain only for the calibration CSV export.

## D40. "Use my location" from an offline city list

The add-plant wizard's new-place form for an outdoor place has a "Use my location" button (DESIGN section 9, item 2; D38 left it out). Only a tap asks for permission, and only `ACCESS_COARSE_LOCATION`; the manifest declares no fine or background location, and a Robolectric test reads the merged manifest's requested permissions to keep it so. One fix is taken with `LocationManagerCompat.getCurrentLocation` (framework `LocationManager`, no Google Play Services; androidx.core covers API 26-29 with a single update). The provider is the first enabled of fused (API 31+), network, GPS. No fix within 10 s, location off, or the permission taken away all give one short message and leave the fields as they were; a denied permission gives another and the city search still works. Nothing is saved while waiting: a rotation cancels the request with the screen's scope, and the button is there again.

**Rounding.** The fix is rounded to two decimals of a degree (at most about 1.1 km north-south, less east-west) before anything is kept, which matches the existing "about 1 km" privacy note. The rounded values are what the latitude and longitude fields hold and what the place stores.

**Nearest city.** A plain haversine (6371 km sphere) over every entry of the list, no index: about 7,300 entries take well under a frame, and only on a tap. Within 50 km the label is the city and country ("Near Belgrade, Serbia" through the existing `wizard_city_picked`); farther away (at sea, remote places) the coordinates are still used and the label adds the distance, "Near Horta, Portugal (1408 km)", so it does not claim the person is in that city. A place name the person already typed is kept (owner decision W2); an empty name is filled with the city's name only within 50 km, since a far city is not a fitting name, and then the person names it.

**City list (owner decision W1).** Natural Earth 1:10m Populated Places, simple version, 5.1.2, from https://www.naturalearthdata.com/downloads/10m-cultural-vectors/10m-populated-places/ (file `ne_10m_populated_places_simple.zip`, served from https://naciscdn.org/naturalearth/10m/cultural/ne_10m_populated_places_simple.zip, SHA-256 `2963ee08f7743f13f37d5243acd77a2f7a510e26a36c7e04b920f1b381f2b251`). The terms page (https://www.naturalearthdata.com/about/terms-of-use/), checked for this slice, says: "All versions of Natural Earth raster + vector map data found on this website are in the public domain." and "No permission is needed to use Natural Earth." and "Crediting the authors is unnecessary." `tools/ne_cities.py` (Python standard library only) reads the zip's `.dbf` table and writes `app/src/main/assets/cities.tsv`: one line per place with `name`, `adm0name`, latitude and longitude to two decimals, tab-separated, sorted, duplicates dropped. Rebuild with `python3 -I tools/ne_cities.py <zip> app/src/main/assets/cities.tsv`. The asset has 7,342 lines and is 235,258 bytes; the raw download is not committed.

## D41. Today matches the mockup (owner's choice)

Between current `main` and PR #26 (`claude/today-look`, opened from an older `main`), the owner chose "whichever is closer to the mockup" (`docs/design/mockups/today.html`). PR #26's look is ported by hand onto current `main`; PR #26 itself is not merged.

- **Buttons on a state block are white with dark ink in both themes.** This covers the soil answers (Wet, OK, Dry), the alert card's Done and the Cancel of the brick import confirmation in Settings. DESIGN.md section 5 says secondary buttons on a block use the surface fill, which in the dark theme drew dark buttons on marigold and brick. The mockup draws them white, and the owner chose the mockup, so this departs from DESIGN.md section 5 on purpose. The fill and ink are one token, `BlockButton` in `Theme.kt`.
- **Taken from PR #26:** an icon per alert kind (cold is the mockup's six-arm asterisk; heat, rain and wind), the alert sentence in label weight (16/600), a solid moss chip with a white glyph on resting tiles (DESIGN.md section 9.8), the top bar and tabs on the ground colour, Today's title at display size (44/800), and page padding 20 and item gap 10 from the spacing tokens instead of 16 and 12.
- **Kept from `main`:** the alert sentence as `main` writes it ("Move Boris against the wall."), which is the mockup's wording; the second button "Dismiss" and its behaviour (Done hides the event's range, Dismiss hides today only, D35), not PR #26's "Not now"; and all of PR #27's alert logic (one card per event, grouping, Done and Dismiss wiring). `main` is already what the mockup shows there, and PR #26 predates it.

## D42. Notification minors from #10, #27 and #32

- **A prompt that cannot be posted** (notifications off, permission revoked) arms tomorrow's job at the chosen time, exactly once per run, so it neither waits for the daily refresh nor retries in a loop. It is not recorded as shown.
- **Import keeps shown prompts of the same plants.** Import keeps the file's plant ids, so an id alone does not say whether it is the same plant: a file from another phone can reuse id 1 for another plant. A plant whose id, name and acquired date are all unchanged by the import keeps its "prompt shown today" record, so today's prompt is not posted again; every other id starts clean, as before. A restored backup of the same garden therefore repeats nothing; a different garden is prompted afresh.
- **Posted alert events are read, checked and written under one process-wide lock.** `postEvent` checks, posts and records inside it, so two workers at once post an event once; the database read stays outside the lock.
- **The "overlap split case" (#27).** A refresh can split one posted event at a milder middle day into two events, both covered by the one notification. Done or Dismiss on that notification used to store the answer for the first part only, leaving the second on Today though the person had answered it. It now stores it for every visible event the notification covers (matched as `alreadyPosted` matches, so a part that got worse still stores nothing).
- **No prompt or reminder at night.** A prompt or reminder job that runs between 22:00 and 07:00 local time more than 3 hours after the chosen time last came round was held up (Doze, phone off). It posts nothing and is armed again for the next occurrence of the chosen time. A chosen time at night is still honoured: a job within 3 hours of it posts. The rule needs no stored schedule, because these jobs are only ever armed at the chosen time.
- **BigTextStyle stays on every prompt.** Text that fits reads the same expanded and collapsed, and the notification expands anyway because of its action buttons. A single long sentence (large font, narrow screen) would be cut to one line without it, and "more than one sentence" cannot tell whether text fits. No change.

## D43. Polish after the wizard, location and Places fixes

Small fixes found in review of #21, #25, #29, #31 and #33. Each has a test or a re-recorded golden.

- **Wizard save.** An exception thrown while saving no longer crashes the app: the wizard stays open with its answers and shows "Could not save the plant. Try again." Cancellation is still passed on. A second tap on Save that lands before the disabled button is drawn does nothing, so a double tap saves once.
- **Pot widths over 300 cm on edit (amends D38).** A typed pot width must still be at most 300 cm. A width loaded from an existing plant and left as it was is accepted at any positive value, so a plant stored before the limit (or imported) can be edited and saved. Exactly 300 is accepted either way. A stored 301 left untouched saves unchanged; changing it to any other width above 300 (for example 302) is refused with the usual message, as on a new plant.
- **Location without GPS (amends D40).** The provider list is fused (API 31+), then network. GPS is dropped: the app asks only for approximate location, which does not cover GPS, and asking GPS for a fix without fine permission throws. With only GPS on, or location off, the person gets the "could not find your location" message and the fields stay as they were.
- **Dates.** `Texts.date` writes the day without a leading zero, "4 Nov", everywhere it is used: plant history lines and week headings, the "forecast fetched" line on Today and calibration entries.
- **Delete buttons.** Every delete is one `DeleteButton`: brick fill and brick text (DESIGN section 5). Each is followed by an Undo (history entries, list rows, photos) or behind a confirmation. Deleting a whole plant from its page used to happen at once with no Undo; it now asks "Delete Boris? Its history and photos go too." first, with Cancel.
- **Learning dots.** Empty dots use the `outline` token in both themes. `ground` nearly matched the dark surface, so the empty dots disappeared; in light the empty dots are now a little darker than the mockup's.
- **Choice chips.** At 1.3x font scale a long chip label wraps to two lines inside a taller chip, and chip rows keep a gap, instead of clipping the second line.
- **Places.** A test now holds the plants query back and shows that no Delete is offered until the plants are known, so a delete cannot skip the in-use check (#33).

## D44. Screen transitions, three variants to choose from

Screens no longer swap at once. Three variants are built so the owner can compare them on the phone. They are chosen under Settings > Developer options > Transitions (Shared, Slide, Fade). The choice is stored in `Settings.transition`, and the default is Shared. The choice stays a developer option until the owner picks one; the other two then go.

- **Shared.** Opening a plant from a Today card, a resting tile or a Plants row grows the card into the plant screen's moss name block. The plant's name and glyph move into place, and the two screens crossfade around them (`SharedTransitionLayout`, `sharedBounds` keyed `card/plant:<id>`, `name/…`, `glyph/…`). Back reverses it. Every other push and pop uses Slide.
- **Slide.** The pushed screen slides in from the right over the old one, which moves 20% of its width to the left. A pop is the same in reverse, with the leaving screen kept on top.
- **Fade.** Material fade through: the old screen fades out over the first 35%, then the new one fades in while it grows from 92% scale.
- **Tabs.** Today and Plants always crossfade, whatever the variant.
- **Timing.** Pushes and pops take 300 ms (Material `medium2`); the tab crossfade takes 200 ms (`short4`). Moves use emphasized easing, `CubicBezier(0.2, 0, 0, 1)`. Fade through uses emphasized decelerate `(0.05, 0.7, 0.1, 1)` coming in and emphasized accelerate `(0.3, 0, 0.8, 0.15)` going out. The values live in `Motion` in `Theme.kt`.
- **Predictive back.** The shown screen follows the top of the back stack through a `SeekableTransitionState`. The system back gesture (`PredictiveBackHandler`) drags the pop with the finger. Letting go completes it. A cancelled gesture plays the pop backwards from where the finger was, then rests on the same screen.
- **Remove animations.** When the system animator duration scale is 0, the screen is drawn without the animated container and changes on the next frame.
- **The tabs moved into the two tab screens.** They were the Scaffold's bottom bar, so a pushed screen could not slide over them. The snackbar is offset by their height and its shadow is clipped at their edge, so it sits and draws where it did before; the goldens are unchanged.
- **A second tap pushes once.** A screen's buttons act only while that screen is the top of the stack, so a second tap on a card that is already leaving does nothing. Its back buttons work the same way.
- **The choice is read in the background.** Until the stored value arrives (a few milliseconds after start), Shared is used.
- **Edges.**
  - Back during a push ends on the screen under it.
  - Rotation in the middle of a transition restores the saved stack and shows its top without animation.
  - A plant opened from a notification starts with the plant on top, so it has no enter animation.
  - Deleting a plant from its own page pops like Back. The leaving page may lose its body as the plant goes.
  - Shared with a card that is partly scrolled off screen still grows from where the card is, including the part off screen; it looks right. A card scrolled fully out of the list is not composed, so there is nothing to grow from; the plant screen then crossfades in alone.
