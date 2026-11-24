# State

Handover for the next coordinator. The owner's brief is not in the repo (section numbers refer to it; G7 is unknown here). `docs/DESIGN.md` is the UI spec (owner's, wins over mockups); `docs/DECISIONS.md` records every choice and its reason.

## Repositories and process

- `stcksmsh/overgrown` (private, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown`. Submodule `overgrown-data` pinned at `06d0079`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database. Local `/secondary/Programming/Github/overgrown-data`. Only `status: reviewed` entries reach the app (only `hardy-potted-conifer` so far).
- Both wired to nonslop. The agent merges app PRs after `nonslop slice verify <branch>`, a fresh-context review recorded with `nonslop slice review <branch> clean|minor|must-fix --sha <sha>`, and green CI. CI reads the data repo with the deploy key in secret `DATA_REPO_SSH_KEY`. The nonslop merge hook checks PR numbers against the app repo, so it refuses data-repo merges: ask the owner to run `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- Test builds: send the owner `app/build/outputs/apk/foss/debug/app-foss-debug.apk` (built here, same debug key) with SendUserFile; he installs over the existing app. Never ask him to uninstall (deletes his data).
- Reviewers once deleted the shared scratch `.claude/tmp/<job>/`; tell every agent to touch only scratch it created.

## Gates

| Gate | State |
|---|---|
| G0, G1 | Done (CI, engine, sim report; D18 interval likelihood). |
| G2 | Done for the owner's plant: he approved `hardy-potted-conifer` (data PR #5). |
| G3 | Done: the app runs on his phone with Boris (installed over ADB, updated by APK). |
| G4 Indoor | App merged (PRs #7, #8). Data drafts, not yet approved: four indoor archetypes, snake plant (owner question: winter rest; no cold limit). Left: owner review of archetypes, more species, device export/import round-trip. |
| Extra, merged | Feeding/repotting reminders (PR #10, D29), plant photos + zip export (PR #13, D30). |
| G5-G7 | Not started. G5 needs 1 g scale data (owner to buy a scale; his has 50 g steps). |

## In flight

- PR #15 `claude/design-spec`: DESIGN.md, mockups in `docs/design/mockups/`, D31 (owner's 13 UI decisions). Verified and reviewed clean at 772990b; CI was running. Merge when green.

## Next work, in order (DESIGN.md)

1. **Foundation** `claude/design-foundation`: one theme file with all tokens, bundled Bricolage Grotesque (OFL, official source, notices), Roborazzi screenshot tests (light/dark, 1.0x/1.3x) in `./gradlew build`, dark contrast test, PotGauge. Brief to rewrite from DESIGN.md sections 2-5, 8.
2. Glyphs (6, one per archetype; shapes in the mockups' SVG).
3. Today: ActionCard (Watered; Wet/OK/Dry), RestingTile, AlertCard grouped per event, empty Today, the words table. Needs the quick "Dry" answer in the engine (D31.4: new answer value, band from the Dry lower edge open above, open-above keyed to the band, tests).
4. Navigation: tabs Today/Plants; Places and Settings from a top-bar icon; Plants and Places lists with edit and delete + Undo.
5. Plant screen: moss name block, DayStrip, LearningDots, photo, history by week, editable and deletable.
6. Add-plant wizard (4 steps): name search + pick by look; where (tiles, city search, "Use my location": approximate, on tap, offline city list, licence check of the list first); pot (width + shape, litres under Advanced); exposure (Yes/A little/No/Not sure).
7. Settings (notification times, theme, units, export/import with confirmation, weighing moved here) and notifications (soil answers in the notification).
8. Data: about 12 archetypes for owner review, then a ~300 common-name table mapped to them.
Known bugs the redesign must cover: "No forecast yet" after a fetch; "No match" after picking a search result; no acquired-on date; snooze hides the repot warning; "Bone dry" still gives "Check the soil".

## Waiting on the owner

1. Trademark check of "Overgrown" and lawyer review of the licences (start now, D31.12); contact address (D9); privacy policy `docs/PRIVACY.md`. Repos go public at the first beta.
2. A 1 g kitchen scale; 2-3 more plants of different types (snake plant, pothos, a non-conifer outdoor plant).
3. The four draft indoor archetypes (data PR #4) feed the ~12-archetype review in item 8 of the work order; the snake plant entry waits for that.

## Boris (the owner's plant)

Potted *Chamaecyparis* (label names the genus only), 5th-floor balcony, inner corner of a U-shaped building, balcony above (rain: Covered), faces south/south-east. 38 cm tall, 13 cm wide; nursery pot 9 cm tall, about 0.5 L, plastic with holes, inside a ceramic outer pot. Cold nights forecast 30 Nov-2 Dec: insulate the gap, lift off the floor, against the wall; below -5 °C into an unheated room. Repot in spring. Watering point kept at 50 % (D25). Watered 24 Nov: 0.20 kg before, 0.30 kg drained (50 g scale). The *C. lawsoniana* species entry stays draft; no usable source gives a *Chamaecyparis* root-kill temperature.

## Known limits

- Four answer bands cannot pin K closer than the band width (D15, D18); weights are logged but unused until after V1 (D31.11).
- Open minors: every prompt uses BigTextStyle; a Doze delay past midnight posts the next day's reminder at night (PR #10); the import free-space check counts photos already present; photos beyond the newest 30 are not viewable (PR #13). Plants in the ground need a soil model: out of v1.
- Unused sources (terms or 403): AHDB, Oregon State, UF/IFAS EDIS, UMass, FEIS (data D4).
