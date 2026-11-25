# State

Handover for the next coordinator. Read this, then run `nonslop status`. The owner's brief is not in the repo. `docs/DESIGN.md` is the UI spec (owner's, wins over mockups); `docs/DECISIONS.md` records every choice and its reason.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown`. Submodule `overgrown-data` pinned at `7756c25`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database. Reviewed archetypes: hardy-potted-conifer and the four indoor ones (data D9). Species entries are drafts.
- CI is OFF until close to V1 (D37): the `CI` workflow is disabled in GitHub (`gh workflow disable CI`; the file `.github/workflows/ci.yml` is unchanged, `gh workflow enable CI` restores it) and `.nonslop.toml [merge] allow_no_ci = true`. The local test command is the gate; every brief must require it. Re-enable both before the first beta.
- Merging: the owner said "merge each time" and "You're always allowed to merge". Merge app PRs yourself once `nonslop slice verify` passes at the head, a fresh review (clean or minor) is recorded at that head (record it BEFORE calling merge), and the branch contains current main. If the permission check refuses, ask the owner once for a standing "merge each time". Data-repo PRs: the owner runs `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- Care data: the agent decides (owner is not a botanist; never ask him to approve plant data). Fresh-context source check, then mark reviewed.
- Speed: the owner wants fast iteration. Two builders in parallel on non-overlapping scopes; each slice its own `res/values/strings_<slice>.xml`; send back only real bugs (must-fix), batch the rest into one round or a later polish slice.
- Test builds: build `:app:assembleFossBeta` and send `app/build/outputs/apk/foss/beta/app-foss-beta.apk` with SendUserFile; he installs over the existing app. Beta is release optimised (R8) but debug-signed (D45); debug builds are janky on his phone, so never send one. Never ask him to uninstall. If the main checkout is busy, build in a worktree from origin/main.
- Agents must touch only scratch they created under `.claude/tmp/`.
- `nonslop slice verify` runs no tests here (no gate configured): after verify, run the full test command yourself with `--rerun-tasks` in a clean worktree at the PR head. A cached or single green run is not proof; #32 merged with a flaky hang.

## Done (this round)

#17 quick Dry answer, #18 design foundation (Theme.kt, Bricolage, Roborazzi exact goldens), #20 glyphs, #21 navigation, #22 alert events data, #23 Today cards + forecast loading, #24 data pin (indoor archetypes), #25 plant screen (DayStrip, LearningDots, history by week, edit/delete), #27 alert cards (Done/Dismiss) + notifications (Wet/OK/Dry, one alert per event). #29 add-plant wizard (4 steps, edit opens the same steps, D38). #31 "Use my location" (D40), #32 Settings (D39), #33 Today matches the mockup (D41; PR #26 closed as superseded) plus fixes for three test flakes. #35 notification minors (D42), #36 UI minors (D43; plant delete now confirms, history dates without leading zero). #38 screen transitions (D44); the owner kept Shared, the switch and the other two variants went (D45). #39 small leftovers (wizard save error clears on change, frost days as ranges, a test for "Use my location" with no fix). #41 TalkBack: Choice chips and entry-dialog answers announce radio role and state, with a guard test (D46). Last APK sent: release build of dad93d4 (debug builds are janky; test APKs now from :app:assembleFossBeta).

## In flight (check `nonslop status`)

- Nothing. The main checkout is a detached, stale HEAD left by builders; do not build from it. Build APKs in a worktree from origin/main.

## Next, in order

1. Small polish left: #36 a WizardTest fakes the location fix inside its wait loop; #35 a late reminder job is named for the wrong day (harmless), and the night rule has no test for feed/repot reminders; #21 recheck place emptiness inside the delete transaction (data/**). Owner to judge on the phone: a solid brick Delete on every history row (DESIGN 5) may look heavy.
2. Data round 2: outdoor archetypes 4-7 after overgrown-data #8; mark the round-1 archetypes reviewed and bump the app's data pin; then the ~300 common-name table (agent decides, source-checked).
3. The overgrown-data tools test does not declare the data files as inputs, so a data-only change can report UP-TO-DATE.

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
