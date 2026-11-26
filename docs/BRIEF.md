# Overgrown: project brief for a new build

You are starting this project from nothing. No code, no repository and no prior notes exist. This document is the complete brief: what to build, the rules you work under, the technical specification, the order of work, and what you must leave to the owner. Read all of it before writing code.

The owner is Kosta, a professional Android engineer in Belgrade, Serbia. He directs the work and reviews it; you write the code. Ask him when this brief is silent on something that matters. Do not invent requirements.

Labels used below: **FACT** means a linked source states it (checked November 2026). **PRIOR** means a starting value that is a reasoned guess and must be treated as tunable. **UNVERIFIED** means nobody has checked it yet and you must check before relying on it.

---

## 1. What Overgrown is

Overgrown is a free, open-source Android app that tells a person when and how much to water each of their plants, and when to protect them from weather. It works from three things: care data for the species, the plant's actual situation (pot, place, light, local weather), and the person's own feedback, from which it learns each plant.

It runs entirely on the phone. There is no account, no server, no analytics and no advertising.

The product is the **engine** (the model that turns inputs into a schedule) and the **care database** (structured species data). The app itself is a thin shell around them. The project's credibility rests on measured proof that the engine beats a simple reminder interval, so that proof is built before the interface.

**What makes it different.** Other apps offer weather-aware reminders, or free basics, or learning from check-ins, each on its own and all closed-source. Existing open-source plant apps are manual trackers that do not recommend anything. No open-source Android app combines an open care database, a weather-aware engine that learns, and no account. That combination is the claim; do not claim more.

### Version 1 scope

In:

- Plants, places (indoor room or outdoor spot), pots.
- Watering schedule with dose in millilitres, from the engine.
- Check-ins: the app asks about soil moisture only when it is unsure, and less often as it learns a plant.
- Local notifications with "Watered" and "Snooze" actions.
- Outdoor alerts: frost, heat, heavy rain, wind.
- Fertilizing and repotting reminders from simple rules.
- Light-level check using the phone's light sensor, reported as a class (low, medium, high, direct), not a precise number.
- Choosing a plant by species from the database, or by **archetype** (a care profile such as "hardy potted conifer") when the species is not in it.
- JSON export and import; Android Auto Backup.
- English only, all strings externalised.

Out, and not to be built: accounts, cloud sync, any backend, social features, AI chat, disease diagnosis, plant identification from photos, advertising, analytics, crash reporting, billing, donations UI, widgets, iOS.

**Outdoor container plants come first.** The owner's only plant today is a false cypress (Chamaecyparis) in its nursery pot on a Belgrade balcony, bought in November 2026. An evergreen conifer in a pot through winter is the first real test case. Indoor plants are the second path.

---

## 2. Rules you work under

1. **No schedules.** Work is ordered by the gates in section 10. Never write dates, deadlines or time estimates into plans, issues or commit messages.
2. **Engine before interface.** Build and test the engine and its simulation harness before any screen.
3. **A gate closes on evidence** (passing tests, a generated report, a logged measurement), never on your say-so.
4. **Nothing proprietary.** No Google Play Services, no Firebase, no Google Gradle plugin, no analytics or crash SDK. Dependencies only from Google Maven and Maven Central.
5. **Engine purity.** The engine module is Kotlin Multiplatform `commonMain` only: no `java.*`, no Android types, `kotlinx-datetime` for time, and only permissively licensed dependencies (Apache-2.0, MIT, BSD). This keeps a later iOS port possible.
6. **Reproducible builds from the first commit** (section 9).
7. **Every model constant is labelled.** All coefficients live in one file, each with a comment giving its status (FACT, PRIOR) and source.
8. **Evidence or null in the care database.** A field without a verifiable quoted source is `null`. Never fill care data, names or identifiers from your own memory.
9. **Sites you must never fetch or copy from:** rhs.org.uk, aspca.org, missouribotanicalgarden.org, perenual.com. Their terms forbid automated access or reuse. Never copy anything from Planta, Greg or PictureThis.
10. **Toxicity is never shown as "safe".** See section 6.
11. **Say how it is built.** The README states plainly that the code is written by AI coding agents under the owner's direction.
12. **Stop and ask the owner before** you: make a repository public, publish a release, submit to any store or directory, post anywhere, send any email, accept any terms of service, create any account, spend money, change a licence, or depart from this brief.

Record every decision you make, with its reason, in `docs/DECISIONS.md`.

---

## 3. Repositories and licences

Create both repositories **private** under the owner's GitHub account.

| Repository | Contents | Licence |
|---|---|---|
| `stcksmsh/overgrown` | App, engine, weather client, tests | `GPL-3.0-only` |
| `stcksmsh/overgrown-data` | Care database: YAML per species, archetypes, schema, tooling | `AGPL-3.0-only` |

- Application ID: `com.kostavukicevic.plants`. It is deliberately brand-neutral because "Overgrown" is a working name that has not been trademark-checked. Keep the display name in one resource so it can change.
- Each repository gets `LICENSE`, SPDX headers on source files, `README.md`, `CONTRIBUTING.md`, and `docs/DECISIONS.md`.
- **Contributor Licence Agreement.** Draft a CLA as a file in each repository: a licence grant (not copyright assignment) modelled on the [Apache Individual CLA](https://www.apache.org/licenses/icla.pdf), plus an explicit clause letting the owner offer contributions under other terms, including commercial ones, and covering data files as well as code. The owner will have a lawyer review it. No outside contribution is merged without a signed CLA.
- **`COMMERCIAL.md` in the data repository.** State that anyone shipping the data, or data derived from it, in an app or service that is not under AGPL-compatible terms needs a commercial licence from the owner, including free closed-source apps; that open-source projects need no permission; and that the name is not licensed. Contact by GitHub issue or the owner's address.
- The data repository's README states that the YAML files, schema and build scripts are the source form and the compiled asset shipped in the app is the object form.
- The app consumes the data repository as a git submodule pinned to a commit, and compiles it into an asset during the Gradle build.

---

## 4. Architecture

Kotlin, Jetpack Compose, Room, DataStore. Target API 36. No backend.

| Module | Contents | Constraints |
|---|---|---|
| `:engine` | Water-balance model, demand models, winter and alert rules, learning filter, baseline model, schedule and dose output | `commonMain` only; deterministic for given inputs |
| `:engine-sim` | Simulated-year harness, truth generator, metrics, report generator, property tests | JVM test tooling; never shipped |
| `:weather` | Provider interface, Open-Meteo client, MET Norway client, daily aggregation, cache, gap filling | Owns all network rules in section 7 |
| `:caredb` | Loader and data classes for the compiled care asset | Engine never parses YAML |
| `:data` | Room database, DataStore, JSON export and import | Multiplatform-compatible DAOs; bundled SQLite driver |
| `:app` | Compose UI, notification scheduler, light-class check, calibration mode | `foss` and `play` flavors (section 9) |

Enumerations in the care data map to numbers inside `:engine`, not in the data, so tuning the engine never rewrites species files.

**FACT:** Room 2.8.5, SQLite 2.7.1 and DataStore 1.3.0 are listed as stable Kotlin Multiplatform libraries; WorkManager is not multiplatform ([Android Developers](https://developer.android.com/kotlin/multiplatform)). **UNVERIFIED:** current `kotlinx-datetime` stability; check before pinning.

---

## 5. Engine specification

### 5.1 The idea

Each pot is a bucket of plant-available water. Each day the plant and the weather draw it down; rain and watering refill it. When the bucket is drawn down to the level that species tolerates, the app prompts. A per-plant correction factor, learned from the person's feedback, scales the draw-down so the model converges on the real plant.

### 5.2 Inputs

- **Place:** indoor or outdoor; latitude and longitude rounded to 2 decimals; altitude. Outdoor: rain exposure (open, partial, covered), sun-hours class, wind exposure. Indoor: light class (optionally the raw lux reading), heating-season dry-air toggle.
- **Pot:** substrate volume in litres; height in cm or shape (shallow, standard, tall); material (plastic, glazed ceramic, unglazed terracotta); drainage holes yes or no; "still in nursery pot" and "root-bound" flags.
- **Plant:** species or archetype; canopy width in cm (or a size class); acquisition date; last repot date.
- **Weather, per day:** minimum and maximum temperature, precipitation in mm, mean wind, mean relative humidity, reference evapotranspiration if the provider supplies it, and a quality flag (forecast, stored forecast, climatology fill).
- **Events:** watered (with "water ran out of the bottom" yes or no); check-in (too wet, ok, dry, bone dry); snooze; moved; repotted; optional pot mass in grams.

### 5.3 The bucket

- `capacity_ml = substrate_volume_l x 1000 x available_water_fraction`.
- State is depletion `d` from 0 (full) to 1 (empty). Each day: `d += use_ml / capacity_ml`, then subtract rain refill, clamp to 0..1.
- Prompt when `d` reaches the plant's `dry_fraction` (from the care data, by water strategy).
- **Dose** when prompting: `d x capacity_ml`, plus about 15 % if the pot has drainage holes (so some runs through), reduced by about 20 % if it has none. PRIOR.
- **Drainage is not a multiplier on use.** A pot without holes gets a smaller dose and an overwatering-risk flag that makes "too wet" feedback act faster.
- A "watered" event sets `d` to 0 only if the person confirms water ran out of the bottom; otherwise to a partial level (PRIOR: 0.2).
- If daily use exceeds capacity (a large plant in a small pot in summer), output "repot, or expect to water daily" instead of a schedule.

### 5.4 Outdoor demand

`use_ml_per_day = ET0_mm x coefficient x canopy_area_m2 x 1000 x pot_material_factor x K`

- `ET0` is reference evapotranspiration, a depth in mm per day. Use the weather provider's value when it supplies one. Otherwise compute **Hargreaves-Samani** (FAO-56 equation 52): `ET0 = 0.0023 x (Tmean + 17.8) x sqrt(Tmax - Tmin) x Ra x 0.408`, with `Ra` the extraterrestrial radiation in MJ per m2 per day from latitude and day of year ([FAO-56 chapter 3](https://www.fao.org/4/x0490e/x0490e07.htm)).
- Supporting formulas, all FACT from FAO-56: solar constant 0.0820 MJ m-2 min-1; `dr = 1 + 0.033 cos(2 pi J / 365)`; declination `= 0.409 sin(2 pi J / 365 - 1.39)`; sunset hour angle `= arccos(-tan(lat) tan(decl))`; day length `= (24 / pi) x hour angle`.
- **Use canopy area, not pot area.** `canopy_area = pi x (width / 2)^2`. FACT: published coefficients for container ornamentals are about 0.5 to 0.6 on canopy area but 1 to 5 on pot-top area ([Beeson 2010](https://journals.ashs.org/downloadpdf/view/journals/hortsci/45/8/article-p1260.pdf)), so a formula without canopy size is wrong several-fold.
- `K` is the learned per-plant correction (section 5.7).
- **Rain refill:** `rain_mm x pot_top_area_m2 x 1000 x exposure_factor`, capped at the current deficit. Exposure factor: open 1.0, partial 0.5, covered 0. Ignore days under 2 mm. PRIOR; no study of rain capture on balconies was found.
- FACT: Hargreaves is reported to overestimate in Serbia and to misbehave in high wind or humidity (FAO-56, same chapter). That error is smaller than the coefficient uncertainty, so it is acceptable as a fallback.

### 5.5 Indoor demand

`use_ml_per_day = reference_use x (canopy_area / reference_canopy_area) x light_term x temp_term x humidity_term x pot_material_factor x K`

- `light_term = max(floor, (DLI / DLI_ref) ^ 0.8)`, where DLI (daily light integral) is derived from the light class or lux reading and day length.
- The only quantitative anchor found: a pothos in a 15 cm pot used about 73 ml per day at about 2150 lux, and the days between waterings rose from 6-8 at about 2150 lux to 16-22 at about 540 lux ([UF/IFAS](https://mrec.ifas.ufl.edu/foliage/resrpts/rh_93_15.htm)). Use it as the reference point: `reference_use` = 73 ml per day at a DLI corresponding to 2150 lux for 12 hours, for a plant with `reference_canopy_area` = 0.07 m2 (about 30 cm wide; the study does not give canopy size, so this figure is a PRIOR). It is one study; everything here is a PRIOR that the learning loop corrects.
- Do **not** add a separate season factor for indoor plants; day length and temperature already carry the season.
- Do **not** drive demand continuously from the phone's light sensor. FACT: one study measured about 39 % error uncalibrated, worse in dim light ([Gutierrez-Martinez et al. 2017](https://mdpi.com/2076-3417/7/6/616)). Use the sensor only to pick a light class.

### 5.6 Winter and alert rules

The water balance is unreliable in cold, low-demand periods, so for outdoor evergreen containers a rule set runs on top of it:

- **Frost hold:** never prompt "water now" when the coming night's minimum is at or below 1 degC.
- **Winter watering window:** prompt a soil check only when the air is above about 4-5 degC, around midday, soil not frozen ([Colorado State Extension](https://extension.colostate.edu/topic-areas/yard-garden/fall-and-winter-watering-7-211); written for in-ground plants, so a PRIOR here).
- **Winter floor:** in a dry, windy, sunny spell, prompt a soil check about every two weeks regardless of the balance ([UMass Extension](https://www.umass.edu/agriculture-food-environment/greenhouse-floriculture/fact-sheets/overwintering-container-grown-ornamentals)).
- **Root-freeze alert for potted conifers:** caution when the forecast minimum is at or below -5 degC, strong alert at or below -10 degC, stricter for small pots and exposed places, with advice to move or insulate the pot. FACT: -5 degC is the level a UK nursery body treats as serious for container roots ([AHDB](https://archive.ahdb.org.uk/knowledge-library/causes-of-winter-injury-in-container-grown-nursery-stock)); no figure specific to Chamaecyparis was found.
- **Waterlogging caution:** species flagged `waterlogging_sensitive` (Chamaecyparis is) get a warning after prolonged rain on an open, undrained pot.
- **Heat, heavy rain, wind alerts:** thresholds as named constants. PRIOR: heat at maximum 32 degC and above; heavy rain at 20 mm a day and above; wind at mean 10 m/s and above.

### 5.7 Learning

- One learned quantity per plant: `K`, the multiplier on predicted use. Track `logK` with mean and variance.
- Start: mean 0 (K = 1), standard deviation 0.5. Clamp K to 0.3 to 3. PRIOR.
- Use a scalar Kalman-style update. A check-in maps to a depletion band: too wet below 0.3; ok 0.3 to 0.7; dry 0.7 to 0.95; bone dry above 0.95. If the model's predicted depletion already lies inside the reported band, do not update. Otherwise update `logK` toward the nearest band edge, with observation noise 0.4 to 0.5 log units.
- Add small daily process noise. Increase variance after a repot, a move, a long gap in weather data, or a surprising answer.
- **Asymmetry:** act on "too wet" immediately; require two consistent answers before increasing water. Reverse this for archetypes where drought kills faster (ferns; conifers in a heat wave).
- **A "watered" tap with no moisture answer never changes K.** It is evidence of habit, not of dryness.
- **Uncertainty drives the prompt type:** when variance is high or the crossing day is uncertain, say "check the soil"; when confident, say "water now" with the dose. As variance falls, ask less often.

### 5.8 Baseline model

Build a naive comparison model in the same module, with the same inputs and the **same learning rule**, so the comparison measures the model and not the learning:

- Interval `= winter + (summer - winter) x monthly_weight`, weights Jan 0, Feb 0, Mar 0.25, Apr 0.5, May 0.8, Jun to Aug 1.0, Sep 0.6, Oct 0.35, Nov 0.1, Dec 0.
- Pot-size multipliers 0.85, 1.0, 1.2; light multipliers 1.15, 1.0, 0.9; winter interval defaults to three times summer.
- A day with at least 4.0 mm of rain counts as a watering for open-sky plants; hold when at least 4.0 mm is forecast or the night minimum is at or below 1.0 degC; "still damp" defers by the larger of 2 days or 35 % of the interval.

These constants come from an MIT-licensed project, [ahaverty/plants](https://github.com/ahaverty/plants); credit it in the source.

### 5.9 Starting coefficients

| Parameter | Start | Bounds | Status |
|---|---|---|---|
| Available water fraction of substrate volume | 0.30 | 0.20-0.35 | FACT range 25-35 % ([Bilderback et al. 2005](https://www.ars.usda.gov/ARSUserFiles/11847/bilderback_etal_2005_Hort%20Tech%2015(4)%20pp%20747-751.pdf)) |
| Pot-height effect | taller pots hold less per litre: about -1.5 to -2.5 percentage points of capacity per cm, between 4 and 15 cm | n/a | FACT ([Owen & Altland 2008](https://journals.ashs.org/view/journals/hortsci/43/2/article-p505.xml)) |
| Pot material factor | terracotta 1.25; plastic and glazed 1.0 | n/a | PRIOR; no measured ratio found |
| Outdoor coefficient on canopy area | 0.75 | 0.5-1.0 before K | PRIOR from Beeson's 0.50-0.63 |
| Indoor light exponent | 0.8 | 0.75-0.95 | PRIOR, one study |
| Light classes, lux | low 270-1080; medium 1080-5400; high 5400-10 800; direct above 10 800 | n/a | FACT, extension rule of thumb ([Maryland Extension](https://extension.umd.edu/resource/natural-lighting-indoor-plants)) |
| Lux to light flux, sunlight | 0.0185 micromol m-2 s-1 per lux | 0.013-0.020 | FACT as prior art |
| Indoor temperature and humidity terms | bounded at plus or minus 20 % | n/a | PRIOR |
| Dry fraction by water strategy | dry out fully 0.95; dry top part 0.6; evenly moist 0.4; conifer in pot 0.5 | n/a | PRIOR |

---

## 6. Care database specification

### 6.1 Layout

```
overgrown-data/
  species/<slug>.yaml        one file per species
  archetypes/<id>.yaml       care profiles with defaults and allowed ranges
  schema/                    JSON Schema (draft 2020-12), versioned
  tools/                     validator, compiler, drafting pipeline
  provenance/                cached source snapshots and quotes (not shipped)
  redirects.yaml             retired slug -> current slug
  ATTRIBUTION.md  COMMERCIAL.md  CLA.md  LICENSE
```

A species entry is an archetype plus overrides. A missing field inherits from the archetype; an explicit `null` means unknown and does not inherit. The compiler merges archetype defaults, keeps only `reviewed` entries, and writes one asset with `schema_version`, `data_version` and the source commit.

### 6.2 Fields

| Group | Fields |
|---|---|
| Identity | `slug` (immutable, equals file name); `schema_version`; `archetype`; `status` (draft, checked, reviewed); `taxon.accepted_name`, `authority`, `rank`, `powo_id`, `wfo_id`, `wikidata_qid`, `gbif_key`, `synonyms[]`; `common_names{lang: [{name, source}]}`; `sold_as[]` (trade names); `cultivars[]` |
| Light | `light_class`; `light_min_lux`, `light_ideal_min_lux`, `light_ideal_max_lux` |
| Water | `water_strategy` (dry out fully, dry top part, evenly moist, wet feet, epiphyte soak); `waterlogging_sensitive`. **No "water every N days" field, ever** |
| Temperature | `min_temp_c`, `ideal_min_c`, `ideal_max_c`, `tolerated_max_c`; `frost_class`; `root_kill_temp_c` with `basis` |
| Dormancy and feeding | `dormancy` (none, winter rest, summer dormancy, deciduous); feeding and repotting as growth-phase triggers, or months tagged `northern_hemisphere` that the engine shifts by latitude; `repot_interval_months`; `humidity_class` |
| Context | optional `indoor:` and `outdoor_container:` override blocks |
| Engine | `engine: {dry_fraction, water_use_factor, drought_asymmetry, basis}`, `basis` one of archetype_default, estimated, fitted |
| Toxicity | `toxicity.{cats, dogs, horses, humans}`, each one of toxic, mild_irritant, no_known_toxicity, unknown; `agents[]`; `parts[]`; its own `status` |
| Provenance, per group | `sources[] {url, title, publisher, retrieved, quote, licence}`; `confidence`; `reviewed_by`; `reviewed_on`; `drafted_by` |

Put units in field names. Write ranges as explicit pairs.

### 6.3 Sources you may use

- **Names and identifiers:** World Checklist of Vascular Plants (CC BY), World Flora Online plant list (CC0), Wikidata (CC0).
- **Attributes:** USDA material (public domain); university extension pages, one fact at a time with a quoted source, where the site's terms do not forbid it. Check each site's terms before first use and record the result in `docs/DECISIONS.md`.
- **Forbidden:** the four sites in rule 9; any commercial plant app; OpenPlantbook until its data licence is confirmed in writing.
- No openly licensed houseplant care dataset exists, so most care values are original work derived from cited facts. Keep any third-party CC BY data in separate fields and credit it in `ATTRIBUTION.md`.

### 6.4 Drafting pipeline

1. Fetch only allowed sources; cache a snapshot.
2. The drafting step sees the fetched text and the schema only. Names and identifiers come from the name-authority lookups.
3. Every field group carries a source with a short verbatim quote. CI checks each quote against the cached snapshot. No verifiable quote means the field is `null`.
4. Produce two independent drafts and diff them field by field; disagreements become review items.
5. Validate: schema, archetype ranges (out-of-range needs an `override_reason` with its own source), cross-field rules (a desert succulent cannot be "evenly moist").
6. Open a pull request with status `draft`. The owner reviews; only he sets `reviewed`.
7. Keep a small gold set of hand-checked entries; any change to the pipeline must still reproduce them.

FACT, and the reason for this strictness: a published check of an AI plant assistant found invented references, five of six synonyms wrong, and eating advice for poisonous plants ([tradescantia.uk](https://tradescantia.uk/article/dont-ask-an-ai-for-plant-advice/)).

### 6.5 Toxicity

Default is `unknown`. A value other than `unknown` requires two independent references that agree and the owner's review. The interface shows `unknown` and `no_known_toxicity` differently and never uses the word "safe". If a reviewed close relative is toxic, show a caution. Every toxicity display carries the disclaimer in section 9.

### 6.6 Archetypes and seed list

Archetypes are points on five axes: water strategy, light class, dormancy pattern, frost class, root sensitivity. Starting set: desert succulent, jungle cactus, tropical aroid, fern, high-humidity foliage, orchid epiphyte, palm, woody tropical, Mediterranean woody herb, tender bedding annual, hardy potted conifer, hardy deciduous shrub, bulb, carnivorous or bog plant, citrus and olive overwintered cool.

Write an archetype only when a plant on the seed list needs it. First entries: **hardy potted conifer** and **Chamaecyparis** (the owner's plant). Then the most common houseplants, a few per archetype. Do not chase a large count; the owner's review time is the limit.

---

## 7. Weather specification

One provider interface in `:weather`, two implementations.

**Default: Open-Meteo.** No API key. FACT ([terms](https://open-meteo.com/en/terms)): free for non-commercial apps without subscriptions or advertising; 10,000 calls a day, 5,000 an hour, 600 a minute; data under CC BY 4.0; its terms say nothing against background requests. One background refresh per day per install is allowed in this design, at a randomised time unique to each install.

**Before coding against it (UNVERIFIED):** read the Open-Meteo API documentation and record whether it provides (a) daily reference evapotranspiration, (b) solar radiation, (c) a historical weather archive, and the licence of each. If (a) exists, use it as `ET0`. If (c) exists under an open licence, use it for the simulation harness.

**Fallback: MET Norway Locationforecast.** The owner accepted its terms on 2026-11-23. Rules, all FACT from the [terms](https://docs.api.met.no/doc/TermsOfService) and [FAQ](https://docs.api.met.no/doc/FAQ):

- **Never request from MET Norway unless the app is open in the foreground.** No background requests to this provider, ever.
- Send `User-Agent: Overgrown/<version> https://github.com/stcksmsh/overgrown`.
- Never repeat a request before the `Expires` time; send `If-Modified-Since`; cache every response.
- Coordinates with at most 4 decimals (use 2).
- Stay far below 20 requests per second across all installs; back off on 429; treat 403 as a bug to fix.
- It has no history and no solar radiation, and the app must aggregate daily minimum and maximum itself.

**Switching:** fall back to MET Norway automatically when Open-Meteo returns sustained errors or rate limits. If the app ever gains subscriptions or advertising, Open-Meteo's free tier no longer applies.

**Notifications without background network.** At each fetch, compute the coming days from the cached forecast and schedule local notifications ahead (watering, frost, heat, rain hold). If the cached forecast is older than a threshold, schedule one notification asking the person to open the app. Any background work besides the single daily Open-Meteo refresh is local recomputation only.

**Gaps.** Store each day's forecast as that day's pseudo-observation. Fill missing days from monthly climatology and mark them with the quality flag; widen the learning variance after long gaps.

**Credit.** An About screen lists the weather provider in use and its licence with a link.

**Privacy consequence.** Requests go straight from the phone, so the provider sees the device's IP address and rounded coordinates. The privacy policy must say so.

---

## 8. App specification

- **Add a plant:** search species, or pick an archetype; enter pot (volume, material, drainage, height), canopy width, place. Keep it short; offer size classes with pictures for people who will not measure.
- **Places:** indoor (light class, with the light-sensor check) or outdoor (rain exposure, sun, wind; location by manual city entry or approximate location permission).
- **Today view:** what needs attention, with the dose and the reason ("dry spell, 4 days without rain").
- **Plant view:** state, history, next expected action, confidence.
- **Check-in:** four choices (too wet, ok, dry, bone dry).
- **Notifications:** actions "Watered" and "Snooze", implemented with broadcast receivers. No exact alarms. Reschedule on boot. Request the notification permission with a clear reason.
- **Calibration mode** (developer option): log pot mass in grams with timestamps, exportable as CSV. This is how the engine gets validated.
- **Export and import:** one JSON file, versioned. Round-trip tested.
- **Auto Backup:** enabled with `dataExtractionRules`; exclude the bundled care asset and photos.
- **About:** version, licences, weather credit, data credit, build-method statement, privacy policy link, "report wrong data" opening a prefilled GitHub issue.
- Show a "forecast fetched" timestamp so stale data is visible.
- Edge-to-edge and predictive back, as target API 36 requires.

---

## 9. Build, release and legal engineering

**Reproducible builds** (FACT, from [F-Droid's documentation](https://f-droid.org/en/docs/Reproducible_Builds/)):

- Pin the Android Gradle Plugin, Kotlin, JDK and build-tools versions.
- Version code and name as static literals in one parseable file.
- No NDK. No embedded timestamps.
- Baseline profiles are non-deterministic: commit them as source or disable the profile tasks.
- `dependenciesInfo { includeInApk = false; includeInBundle = false }`.
- LF line endings.
- CI builds each tagged commit twice and fails if the two APKs differ.

**Flavors.** One dimension `store` with `foss` (default) and `play`. Both are identical now. The split exists so that anything store-specific later stays out of the `foss` build. Never apply a Google plugin at project level.

**Signing.** One developer key signs every non-Play APK. The owner holds it; you never generate, store or commit signing keys or passwords.

**Fastlane-structure store metadata** in the app repository (`fastlane/metadata/android/en-US/`): title, short description, full description, changelogs, screenshots. Short description leads with watering reminders.

**Privacy policy** as a Markdown or HTML page in the repository, later hosted on the owner's site. It states: publisher and contact; no account, analytics, advertising or developer server; all data stays on the device; Auto Backup goes to the person's own Google account if enabled; the weather provider receives IP address and rounded coordinates; permissions and why; how to export and delete.

**Disclaimers.** On every toxicity display: this is general information and not veterinary or medical advice; the data is incomplete; the absence of a warning does not mean a plant is harmless; contact a vet or poison centre if a pet or person has eaten a plant. A general care notice in About.

**Distribution, in this order, each only when the owner says so:** GitHub Releases; F-Droid main repository; Google Play. No other repositories. F-Droid requires a build entirely from source with free tooling, which rules 4 and 6 already ensure.

---

## 10. Order of work

Each gate closes only when every exit condition holds.

| Gate | What gets built | Exit conditions |
|---|---|---|
| **G0 Foundations** | Both private repositories with licence files, CLA draft, `COMMERCIAL.md`, `docs/DECISIONS.md`; module skeleton; flavors; CI | CI runs build, tests, dependency-licence check, a compile of `:engine` for an iOS target, and the double build; `docs/acceptance/met-norway-2026-11-23.md` records the owner's acceptance of MET Norway's terms and the foreground-only rule |
| **G1 Engine** | `:engine` complete (sections 5.3 to 5.8); `:engine-sim` | All fixtures and property tests in section 11 pass; the harness produces a metric report for both models |
| **G2 Care data** | Schema v1, validator, compiler, drafting pipeline; hardy-potted-conifer archetype; Chamaecyparis entry | Validator runs in CI; the asset builds inside Gradle from the pinned submodule; the owner has reviewed the Chamaecyparis entry |
| **G3 Personal use, outdoor** | `:weather`, `:data`, minimal `:app` for one outdoor plant | The owner's cypress runs on the app installed by ADB: forecast fetch, scheduled notifications with actions, frost, heat, rain and wind alerts, rain-exposure setting, export, calibration mode |
| **G4 Indoor** | Indoor demand path, light-class check, more archetypes and species | Indoor plants run on the app; each weighed pot has several complete dry-down cycles logged; export and import round-trip |
| **G5 Proof** | Baseline comparison and archetype test, written up | Results published in the repository whatever they show |
| **G6 Hardening** | Onboarding for strangers, accessibility pass, battery-saver guidance, privacy policy, disclaimers, store metadata | Reproducible build verified; everything in section 12 marked "before release" is done |
| **G7 onward** | Release steps | Only on the owner's instruction |

G1 and G2 can proceed side by side after G0. G4 needs the owner to have indoor plants.

**G5, the proof.** Fix the metrics before running: days per year the plant sat below its dry threshold; days above a wet threshold after a prompt; error in predicted dry-out date; prompts per plant per year and the share answered "still wet"; check-ins needed until dry-out is predicted within a day or 20 %; water-use error against the scale measurements. Report all of them for both models. If the engine does not beat the baseline, narrow the claim; do not hide the result.

**Archetype test, part of G5.** For every reviewed species, run the harness twice on the same simulated weather: once with the full species entry, once with only its archetype defaults. Pass if the archetype-only run adds no extra stress days beyond a small allowance and keeps dry-out error within 20 % of the species run for at least four in five species. Species that fail are marked as needing their own entry; an archetype whose members all fail the same way gets split.

---

## 11. Tests

**Hargreaves fixture.** Belgrade, latitude 44.8 degN, 1981-2010 climate normals ([Serbian hydrometeorological service](https://www.hidmet.gov.rs/eng/meteorologija/stanica_sr.php?moss_id=13274)). Assert each monthly `ET0` within 0.1 mm per day and each `Ra` within 0.1.

| Month | Day of year | Mean max degC | Mean min degC | Ra, MJ m-2 d-1 | Day length, h | ET0, mm/day |
|---|---|---|---|---|---|---|
| Jan | 15 | 4.6 | -1.1 | 12.05 | 8.98 | 0.53 |
| Feb | 46 | 7.0 | -0.1 | 17.57 | 10.20 | 0.93 |
| Mar | 74 | 12.4 | 3.7 | 24.94 | 11.64 | 1.78 |
| Apr | 105 | 18.0 | 8.3 | 33.28 | 13.28 | 3.01 |
| May | 135 | 23.5 | 13.0 | 39.23 | 14.64 | 4.30 |
| Jun | 166 | 26.2 | 15.8 | 41.86 | 15.38 | 4.92 |
| Jul | 196 | 28.6 | 17.5 | 40.61 | 15.06 | 5.19 |
| Aug | 227 | 28.7 | 17.6 | 35.65 | 13.87 | 4.56 |
| Sep | 258 | 23.9 | 13.5 | 27.95 | 12.28 | 3.09 |
| Oct | 288 | 18.4 | 9.0 | 19.83 | 10.70 | 1.80 |
| Nov | 319 | 11.2 | 4.2 | 13.33 | 9.30 | 0.84 |
| Dec | 349 | 5.8 | 0.2 | 10.62 | 8.62 | 0.49 |

Belgrade extremes for alert tests: record low -18.2 degC, record high 43.6 degC.

**Summer fixture.** July `ET0` 5.19 mm on a canopy 40 cm wide (0.126 m2) with coefficient 1 is about 0.65 litres a day. A 2 litre pot holds about 0.6 litres of available water. The engine must answer "repot, or expect to water daily".

**Indoor fixture.** The reference plant (canopy 0.07 m2, 2150 lux for 12 hours, K = 1, plastic pot, neutral temperature and humidity) must use 73 ml per day, and with 1.5 to 2 litres of substrate its full bucket must empty in 6 to 8 days. At 540 lux the same plant must take roughly two to three times as long.

**Property tests.**

- Water is conserved in the bucket.
- More light or heat never lengthens the interval.
- No "water now" on a frost-hold day.
- `K` stays inside its clamps under hostile feedback.
- A plant mis-specified by a factor of two converges within a bounded number of check-ins.
- A "watered" event without a moisture answer leaves `K` unchanged.
- The engine is deterministic: same inputs, same outputs.

**Weather client tests.** No request before `Expires`; conditional requests sent; coordinates rounded; per-install jitter applied; back-off on 429; no MET Norway request when the app is not in the foreground.

**Simulation harness.** The simulated "truth" must be richer than the engine, or the test proves nothing: hourly demand with wind and humidity, a plant coefficient drawn at random from a wide range, a bucket drawn from 25-35 %, random rain capture, and noisy, biased check-in answers. Run Belgrade and two or three contrasting climates. Use real historical weather if an open archive is confirmed (section 7); otherwise generate synthetic years from monthly normals. The harness shows robustness, not real-world accuracy; say so in any write-up.

**Real-world validation** is the owner's kitchen scale: pot mass after watering and draining is the full mark, daily mass loss is measured water use. This is the same method nursery research uses ([UF/IFAS](https://edis.ifas.ufl.edu/publication/EP458)). Calibration mode exists to capture it.

---

## 12. What the owner does

You cannot do these, and none of them blocks G0 to G2.

- Weigh the cypress daily, starting now: water until it runs out of the bottom, drain about an hour, weigh, then weigh at the same time each day and note rain.
- Review the Chamaecyparis entry and every later data entry before it becomes `reviewed`.
- Buy indoor test plants (needed for G4).
- Hold the signing key.
- **Before release:** check the name "Overgrown" against trademark registers; have a lawyer review the licences, CLA and `COMMERCIAL.md`; decide whether to register the app under Android developer verification.
- All publishing, posting, emailing and account creation.

---

## 13. After the app works (context only; do not build now)

- **Later features, in rough priority:** plant identification with the person's own API key; home-screen widget; household sharing through a shared file in a folder the person chooses; translations through Weblate; soil-sensor input; iOS.
- **iOS** would reuse the engine, data layer and interface through Kotlin Multiplatform. That is why rule 5 exists and why shared modules must never depend on copyleft third-party libraries.
- **Money:** none planned. Donations may be added much later.
- **A server** is only ever needed for a weather cache at large scale, a shared identification key, or live sync. None is in scope.

---

## 14. Known unknowns

Things nobody has verified. Do not present them as settled, and check the ones you depend on.

- Whether Open-Meteo provides reference evapotranspiration, radiation and a history archive, and their licences.
- How Open-Meteo counts its daily limit (per device address, or per application as a whole).
- Water loss through terracotta compared with plastic.
- How much rain actually enters a pot on a balcony or under a canopy.
- The root-damage temperature for Chamaecyparis.
- Accuracy of current phone light sensors.
- Current stability of `kotlinx-datetime`.
- Whether "Overgrown" is free as a trademark for software.
- Whether a court would treat a closed app bundling AGPL-licensed data as bound by that licence. The licence is a deterrent with a paid alternative, not a guarantee.

---

## 15. Start here

1. Create the two private repositories with the files in section 3.
2. Write `docs/DECISIONS.md` and `docs/acceptance/met-norway-2026-11-23.md`.
3. Set up modules, flavors and CI (G0).
4. Build the engine and its tests (G1), and the care-data schema and pipeline (G2).
5. Tell the owner when the Chamaecyparis entry is ready for his review, then continue to G3.
