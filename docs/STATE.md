# State

Handover for the next session. The brief (owner's project brief, section numbers below refer to it) is the spec; `docs/DECISIONS.md` records every choice and its reason.

## Repositories

- `stcksmsh/overgrown` (private, GPL-3.0-only): app, engine, weather, storage. Local: `/secondary/Programming/Github/Overgrown`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): care database, schema, tools. Local: `/secondary/Programming/Github/overgrown-data`. Pinned in the app as the `overgrown-data` submodule, currently at its merge commit `645deca`.
- Both are wired to nonslop (`.nonslop.toml`). Merging needs `nonslop slice verify <branch>` and a recorded fresh-context review (`nonslop slice review <branch> clean|minor|must-fix --sha <sha>`). The owner has allowed the agent to merge once both pass and CI is green.
- CI reads the private data repo with the read-only deploy key in secret `DATA_REPO_SSH_KEY`.

## Gates

| Gate | State |
|---|---|
| G0 Foundations | Done. CI: build, tests, licence check, iOS compile of `:engine`, double build compared byte for byte. |
| G1 Engine | Done. 20 engine tests, 9 property tests, simulation report `docs/reports/sim-report.md` (CI checks it is current). Learning uses an interval likelihood instead of the brief's nearest band edge, approved by the owner (D18). |
| G2 Care data | Waiting on the owner. Schema v1 with `in_ground`, `outdoor_container`, `indoor` contexts and `site.wind_sensitive`; validator, compiler, two-draft pipeline (`docs/PIPELINE.md` in the data repo); `hardy-potted-conifer` archetype; `species/chamaecyparis-lawsoniana.yaml` from seven sources. **Exit condition left: the owner reviews the entry.** |
| G3 Outdoor app | Code merged (PR #4). Weather (Open-Meteo, MET Norway foreground only), Room storage with engine replay, export/import, Compose screens, notifications through WorkManager, calibration mode. **Exit condition left: the owner's cypress running on his phone over ADB.** |
| G4 Indoor | App merged: indoor places with light class and dry-air (PR #7), light-sensor check that sets a class and stores no lux (PR #8, D28). Data: four indoor archetypes (succulent, tropical foliage, moisture-loving, orchid) in overgrown-data PR #4, reviewed clean, waiting for the owner to merge (the nonslop merge hook checks PR numbers against this repo, so it refuses data-repo merges). Owner has no indoor plants: nothing to test on his phone. Left: indoor species entries (snake plant first; it does not fit the succulent archetype on light), export/import round-trip on a device. |
| G5-G7 | Not started. |

## Open

- overgrown-data PR #4 (indoor archetypes): owner to merge; then re-pin the `overgrown-data` submodule here. Archetypes stay draft until the owner reviews them.

## Waiting on the owner

1. **The cypress label.** He believes it is *Chamaecyparis lawsoniana*; he will check the species and cultivar. Then: set `status: reviewed`, `reviewed_by` and `reviewed_on` in every provenance block of the species entry and the archetype (only with his explicit approval), merge in the data repo, re-pin the submodule in the app.
2. **His phone over USB** with debugging on, to install the debug build and set up the cypress (G3's last exit condition). Emulator runs die when the session restarts; prefer his phone.
3. Pot watering point for the cypress: he did not choose; kept at 50 % (D25). Revisit with his kitchen-scale data.
4. Before any release (brief section 12): trademark check of "Overgrown", lawyer review of the licences, CLA and `COMMERCIAL.md`, a contact address to publish (D9), the privacy policy draft `docs/PRIVACY.md`.

## Next work, in order

1. Close G2 and G3 with the owner (above).
2. **G4 rest**: re-pin the data submodule after data PR #4; indoor species through the pipeline (a few per archetype); export/import round-trip on a device. Light-class labels in the place editor are the builder's wording: owner to confirm.
3. Feeding and repotting reminders from simple rules (D17). Feeding data now exists in the draft indoor archetypes (not the tropical-foliage one); none reaches the app until the owner reviews.
4. The owner asked about plants in the ground: the data side is ready (`in_ground` context); the engine needs a soil model. Out of v1 scope; raise after G3/G4.
5. G5 proof (baseline comparison with real scale data and the archetype test), G6 hardening.

## Known limits worth remembering

- Learning from four answer bands cannot pin K down closer than the band width (D15, D18); the scale can.
- Sources: AHDB, Oregon State, UF/IFAS EDIS, UMass and the Forest Service's FEIS are not used (terms or 403); see the data repo's D4. No root-kill temperature for *Chamaecyparis* has a usable source.
- The app module has no unit tests; a notification bug reached review because of it (fixed). Consider Robolectric or instrumented tests in G6.
