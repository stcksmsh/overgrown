# State

Handover for the next coordinator. Read this, then run `nonslop status`. The owner's brief is not in the repo. `docs/DESIGN.md` is the UI spec (owner's, wins over mockups); `docs/DECISIONS.md` records every choice and its reason.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown`. Submodule `overgrown-data` pinned at `7756c25`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database. Reviewed archetypes: hardy-potted-conifer and the four indoor ones (data D9). Species entries are drafts.
- CI is OFF until close to V1 (D37): the `CI` workflow is disabled in GitHub (`gh workflow disable CI`; the file `.github/workflows/ci.yml` is unchanged, `gh workflow enable CI` restores it) and `.nonslop.toml [merge] allow_no_ci = true`. The local test command is the gate; every brief must require it. Re-enable both before the first beta.
- Merging: the owner said "merge each time" and "You're always allowed to merge". Merge app PRs yourself once `nonslop slice verify` passes at the head, a fresh review (clean or minor) is recorded at that head (record it BEFORE calling merge), and the branch contains current main. If the permission check refuses, ask the owner once for a standing "merge each time". Data-repo PRs: the owner runs `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- Care data: the agent decides (owner is not a botanist; never ask him to approve plant data). Fresh-context source check, then mark reviewed.
- Speed: the owner wants fast iteration. Two builders in parallel on non-overlapping scopes; each slice its own `res/values/strings_<slice>.xml`; send back only real bugs (must-fix), batch the rest into one round or a later polish slice.
- Test builds: build `:app:assembleFossDebug` and send `app/build/outputs/apk/foss/debug/app-foss-debug.apk` with SendUserFile; he installs over the existing app. Never ask him to uninstall. If the main checkout is busy, build in a worktree from origin/main.
- Agents must touch only scratch they created under `.claude/tmp/`.
- `nonslop slice verify` runs no tests here (no gate configured): after verify, run the full test command yourself with `--rerun-tasks` in a clean worktree at the PR head. A cached or single green run is not proof; #32 merged with a flaky hang.

## Done (this round)

#17 quick Dry answer, #18 design foundation (Theme.kt, Bricolage, Roborazzi exact goldens), #20 glyphs, #21 navigation, #22 alert events data, #23 Today cards + forecast loading, #24 data pin (indoor archetypes), #25 plant screen (DayStrip, LearningDots, history by week, edit/delete), #27 alert cards (Done/Dismiss) + notifications (Wet/OK/Dry, one alert per event). #29 add-plant wizard (4 steps, edit opens the same steps, D38). #31 "Use my location" (D40), #32 Settings (D39), #33 Today matches the mockup (D41; PR #26 closed as superseded) plus fixes for three test flakes. Last APK sent: main 3786ac2.

## In flight (check `nonslop status`)

- Nothing. The main checkout is a detached, stale HEAD left by builders; do not build from it. Build APKs in a worktree from origin/main.

## Next, in order

1. Polish slice. Wizard minors: uncaught exception on save, stored width >300 blocks save, tiny double-save window; Common.kt FilterChip labels wrap at 1.3x; unused EditPlantScreen strings. Leading-zero dates in history ("04 Nov"); unused strings in strings.xml (plant_state, plant_confidence_*, plant_learned, notif_alert_title, refreshing); dark-mode empty learning dots; Delete as brick per DESIGN; minors in PR bodies of #21, #25, #27 (#27: unlocked read-modify-write of the posted-events pref; overlap split case). #31: no test for "location off / no fix"; drop GPS_PROVIDER (needs FINE). #32: a prompt whose post fails (notifications off) is not re-armed until refresh/resume; an import clears shown-today records so a prompt can repeat once. #33: no deterministic test for the PlacesScreen delete-before-load race.
2. Data: ~12 archetypes, then ~300 common names mapped to them (agent decides, source-checked).

## Waiting on the owner

- Nothing now.
- Later (he said not now): trademark check, lawyer review, contact address, privacy policy, a 1 g scale, more plants.

## Decisions (do not re-argue)

Owner answers: A level line "Getting dry · 20 %"; B alerts first, then Do now, then All fine; C "Check Thu." within 6 days else "Check 6 Dec."; D Done hides the event's range, Dismiss hides today only (comes back if worse); E show "Water about N ml". W1 Natural Earth city list; W2 keep a typed name; P1 Wet/OK/Dry/Bone dry on the plant screen; P2 history editable; S1 metric only; S2 one reminder 08:00; S3 theme follows system. Gauge variant A (D32). Today look: "whichever is closer to the mockup" (D41): white buttons on state blocks in both themes, kind icons, solid moss chips; kept main's alert sentence and Dismiss.

## Boris (the owner's plant)

Potted *Chamaecyparis* on a covered balcony. Nursery pot about 0.5 L, plastic with holes, in a ceramic outer pot. Cold nights forecast 30 Nov-2 Dec: insulate, lift off the floor, against the wall; below -5 °C into an unheated room. Repot in spring. Watering point 50 % (D25).

## Hard-won facts

- `gh pr edit` fails here: set PR bodies with `gh api -X PATCH repos/stcksmsh/overgrown/pulls/<n> -f body=...`.
- Worktrees need `git submodule update --init` and `ANDROID_HOME=~/Android/Sdk`; test tasks are flavoured (`:app:testFossDebugUnitTest`).
- Worktrees containing the submodule cannot be removed without `--force`; leave them.
- `nonslop slice new <brief> --force` replaces a slice's own record (to widen scope); do not use it to override overlap.
- Robolectric: wait with `compose.waitUntil`, never sleeps; in-memory DB via `useTestDatabase` in NavigationTest.kt.
