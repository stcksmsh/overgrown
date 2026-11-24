# State

Handover for the next coordinator. Read this, then run `nonslop status`. The owner's brief is not in the repo. `docs/DESIGN.md` is the UI spec (owner's, wins over mockups); `docs/DECISIONS.md` records every choice and its reason.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown`. Submodule `overgrown-data` pinned at `7756c25`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database. Reviewed archetypes: hardy-potted-conifer and the four indoor ones (data D9). Species entries are drafts.
- CI is OFF until close to V1 (D37): workflow disabled, `.nonslop.toml [merge] allow_no_ci = true`. The local test command is the gate; every brief must require it. Re-enable both before the first beta.
- Merging: the owner said "merge each time". Merge app PRs yourself once `nonslop slice verify` passes at the head, a fresh review (clean or minor) is recorded at that head (record it BEFORE calling merge), and the branch contains current main. If the permission check refuses, ask the owner once for a standing "merge each time". Data-repo PRs: the owner runs `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- Care data: the agent decides (owner is not a botanist; never ask him to approve plant data). Fresh-context source check, then mark reviewed.
- Speed: the owner wants fast iteration. Two builders in parallel on non-overlapping scopes; each slice its own `res/values/strings_<slice>.xml`; send back only real bugs (must-fix), batch the rest into one round or a later polish slice.
- Test builds: build `:app:assembleFossDebug` and send `app/build/outputs/apk/foss/debug/app-foss-debug.apk` with SendUserFile; he installs over the existing app. Never ask him to uninstall. If the main checkout is busy, build in a worktree from origin/main.
- Agents must touch only scratch they created under `.claude/tmp/`.

## Done (this round)

#17 quick Dry answer, #18 design foundation (Theme.kt, Bricolage, Roborazzi exact goldens), #20 glyphs, #21 navigation, #22 alert events data, #23 Today cards + forecast loading, #24 data pin (indoor archetypes), #25 plant screen (DayStrip, LearningDots, history by week, edit/delete), #27 alert cards (Done/Dismiss) + notifications (Wet/OK/Dry, one alert per event). Last APK sent: main b435718.

## In flight (check `nonslop status`)

- `claude/add-plant-wizard`: 4-step wizard + acquiredOn + "No match" bug. Brief was in coordinator scratch; slice record has scope.
- PR #26 `claude/today-look` ("Today: look like the mockup"): opened by ANOTHER session, not a slice; the main checkout was left on that branch. Conflicts with #27 on Today files. Owner has not said adopt or ignore. Do not touch without his word.

## Next, in order

1. Settings (D31.11: notification time 08:00, theme follow-system/light/dark, metric only, export/import with brick confirmation, weighing moved here) in `strings_settings.xml`.
2. "Use my location": Natural Earth populated places (public domain), approximate permission on tap, rounded to ~1 km.
3. Polish slice: leading-zero dates in history ("04 Nov"); unused strings in strings.xml (plant_state, plant_confidence_*, plant_learned, notif_alert_title, refreshing); dark-mode empty learning dots; Delete as brick per DESIGN; minors in PR bodies of #21, #25, #27 (#27: unlocked read-modify-write of the posted-events pref; overlap split case).
4. Data: ~12 archetypes, then ~300 common names mapped to them (agent decides, source-checked).

## Waiting on the owner

- PR #26: adopt or ignore.
- Later (he said not now): trademark check, lawyer review, contact address, privacy policy, a 1 g scale, more plants.

## Decisions (do not re-argue)

Owner answers: A level line "Getting dry · 20 %"; B alerts first, then Do now, then All fine; C "Check Thu." within 6 days else "Check 6 Dec."; D Done hides the event's range, Dismiss hides today only (comes back if worse); E show "Water about N ml". W1 Natural Earth city list; W2 keep a typed name; P1 Wet/OK/Dry/Bone dry on the plant screen; P2 history editable; S1 metric only; S2 one reminder 08:00; S3 theme follows system. Gauge variant A (D32).

## Boris (the owner's plant)

Potted *Chamaecyparis* on a covered balcony. Nursery pot about 0.5 L, plastic with holes, in a ceramic outer pot. Cold nights forecast 30 Nov-2 Dec: insulate, lift off the floor, against the wall; below -5 °C into an unheated room. Repot in spring. Watering point 50 % (D25).

## Hard-won facts

- `gh pr edit` fails here: set PR bodies with `gh api -X PATCH repos/stcksmsh/overgrown/pulls/<n> -f body=...`.
- Worktrees need `git submodule update --init` and `ANDROID_HOME=~/Android/Sdk`; test tasks are flavoured (`:app:testFossDebugUnitTest`).
- Worktrees containing the submodule cannot be removed without `--force`; leave them.
- `nonslop slice new <brief> --force` replaces a slice's own record (to widen scope); do not use it to override overlap.
- Robolectric: wait with `compose.waitUntil`, never sleeps; in-memory DB via `useTestDatabase` in NavigationTest.kt.
