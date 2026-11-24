# State

Handover for the next session. The brief (owner's project brief, section numbers below refer to it) is the spec; `docs/DECISIONS.md` records every choice and its reason.

## Repositories

- `stcksmsh/overgrown` (private, GPL-3.0-only): app, engine, weather, storage. Local: `/secondary/Programming/Github/Overgrown`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database, schema, tools. Local: `/secondary/Programming/Github/overgrown-data`. Pinned in the app as the `overgrown-data` submodule, currently at its merge commit `06d0079`.
- Both are wired to nonslop (`.nonslop.toml`). Merging needs `nonslop slice verify <branch>` and a recorded fresh-context review (`nonslop slice review <branch> clean|minor|must-fix --sha <sha>`). The owner has allowed the agent to merge once both pass and CI is green.
- CI reads the private data repo with the read-only deploy key in secret `DATA_REPO_SSH_KEY`.

## Gates

| Gate | State |
|---|---|
| G0 Foundations | Done. CI: build, tests, licence check, iOS compile of `:engine`, double build compared byte for byte. |
| G1 Engine | Done. 20 engine tests, 9 property tests, simulation report `docs/reports/sim-report.md` (CI checks it is current). Learning uses an interval likelihood instead of the brief's nearest band edge, approved by the owner (D18). |
| G2 Care data | Done for the owner's plant. His pot label names only the genus *Chamaecyparis*, so he approved the `hardy-potted-conifer` archetype (data PR #5, reviewed by him); the *C. lawsoniana* species entry stays draft until a plant needs it (identification needs the underside of a spray). Data merges: the nonslop merge hook resolves PR numbers against this repo and refuses every data-repo merge; the owner merges data PRs himself with `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`. |
| G3 Outdoor app | Code merged (PR #4). Weather (Open-Meteo, MET Norway foreground only), Room storage with engine replay, export/import, Compose screens, notifications through WorkManager, calibration mode. **Exit condition left: the owner's cypress running on his phone over ADB.** |
| G4 Indoor | App merged: indoor places with light class and dry-air (PR #7), light-sensor check that sets a class and stores no lux (PR #8, D28). Data (all draft, not in the app until the owner approves): four indoor archetypes (data PR #4) and *Dracaena trifasciata*, the snake plant (data PR #6; open question for the owner: winter_rest or not; it has no cold limit, so no frost alert). Owner has no indoor plants. Left: more indoor species, export/import round-trip on a device, owner to confirm the light-class labels. |
| G5-G7 | Not started. |

## Open

- Nothing open in either repo besides this handover.

## Waiting on the owner

1. **Boris**, the owner's potted *Chamaecyparis* (pot label names only the genus): outdoors on his 5th-floor balcony in the inner corner of a U-shaped building, a balcony above (rain sheltered), facing south/south-east. 38 cm tall, 13 cm wide; nursery pot 9 cm tall, 30.5 cm round the top (about 0.5 L), plastic with holes, inside a decorative outer pot. Watered 2026-11-24: 0.20 kg before, 0.30 kg after draining, on a scale with 50 g steps (too coarse for calibration; a 1 g kitchen scale was suggested). Spring repot advised.
2. **His phone over USB** with debugging on, to install the debug build and set up Boris with the above (G3's last exit condition).
3. Pot watering point for the cypress: he did not choose; kept at 50 % (D25). Revisit with his kitchen-scale data.
4. Before any release (brief section 12): trademark check of "Overgrown", lawyer review of the licences, CLA and `COMMERCIAL.md`, a contact address to publish (D9), the privacy policy draft `docs/PRIVACY.md`.

## Next work, in order

1. Set up Boris on the owner's phone (closes G3).
2. **G4 rest**: more indoor species through the pipeline; export/import round-trip on a device.
3. Feeding and repotting reminders: merged (PR #10, D29). FED event in the export; reminders on Today and in the notification at 09:00 or inside that day's watering prompt. Nothing shows on a device until a reviewed care entry has season data. Open minors: BigTextStyle on every prompt; a Doze delay past midnight posts the next day's reminder at night.
4. The owner asked about plants in the ground: the data side is ready (`in_ground` context); the engine needs a soil model. Out of v1 scope; raise after G3/G4.
5. G5 proof (baseline comparison with real scale data and the archetype test), G6 hardening.

## Known limits worth remembering

- Learning from four answer bands cannot pin K down closer than the band width (D15, D18); the scale can.
- Sources: AHDB, Oregon State, UF/IFAS EDIS, UMass and the Forest Service's FEIS are not used (terms or 403); see the data repo's D4. No root-kill temperature for *Chamaecyparis* has a usable source.
- The app module has no unit tests; a notification bug reached review because of it (fixed). Consider Robolectric or instrumented tests in G6.
