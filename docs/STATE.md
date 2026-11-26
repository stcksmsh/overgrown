# State

Handover for the next coordinator. Read this, then run `nonslop status` in BOTH repos. The brief is `docs/BRIEF.md` (gates: section 10). `docs/DESIGN.md` is the UI spec; `docs/DECISIONS.md` records every choice and its reason. The advisor's answers to the status report (`docs/reports/advisor-status.md`) were accepted by the owner; they are summarised under Decisions below.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown` (its main checkout is a stale detached HEAD; build in worktrees from origin/main). Submodule `overgrown-data` pinned at `f708cbf`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): 12 reviewed archetypes; species basil, boxwood, lemon tree, impatiens reviewed on data main `fd05404` (not yet pinned in the app). Plant queue `queue.md`; skills `.claude/skills/next-entry` (one batch, max 5, one runner) and `.claude/skills/data-review` (fresh reviewer). Data PRs: the owner merges with `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- CI: runs on pushes to main, `v*` tags and manual dispatch only (D50); last run on main green including the reproducible double build. PRs have no CI: the local full test command is the PR gate (`.nonslop.toml allow_no_ci = true`). Agents cannot edit CI config (slopstop gate): the owner applies such changes by merging.
- Merging app PRs: the coordinator merges after `nonslop slice verify` passes at the head, a fresh review (clean/minor) is recorded at that head, the branch contains main, and the coordinator's OWN full test run with `--rerun-tasks` at the head is green. Builders' "passed" is not proof.
- Review rule: delegated (agent) review is recorded as `reviewed_by: agent (delegated by owner, <date>)`. Toxicity other than `unknown` always needs two references and the owner's own review.
- Builders put decision text in the PR body ("Decision entry"); the coordinator appends it to DECISIONS after merge, so parallel slices never share DECISIONS.md.
- Scratch: every brief must say "delete only a new folder you created under .claude/tmp/". One builder deleted the coordinator's whole scratch folder once.
- Test APKs: `:app:assembleFossBeta` from a worktree at origin/main, send `app/build/outputs/apk/foss/beta/app-foss-beta.apk` with SendUserFile; never ask the owner to uninstall.

## Done (this round)

#45 #48 #52 data pins (12 archetypes, reviewed_by names the agent); #47 polish; #49 advisor report; #50 brief + CI on main (D50); #51 repot advice once per pot + still-wet headline in the sim report (D51); #53 Boris voice: Boris/Plain switch, default Boris, `_boris` twins, tests for the hard rules (D52). Data: #10 #11 round-2 archetypes, #12 queue + skills + review rules, #13 first four species. Last APK sent: 87912cc (before Boris).

## In flight

- Nothing running. Two slices are registered but NOT dispatched; briefs in `.claude/tmp/fffc12d9/briefs/` (keep until dispatched):
  - `claude/data-pin-species-1` (data-pin-species-1.md): pin fd05404, species in the asset, wizard finds species by common name, CareDataPinTest with 4 species.
  - `claude/flake-ui-tests` (flake-talkback-transitions.md): TalkBackTest (Cancel node not found, play) and TransitionsTest.aPushShowsBothScreensHalfwayAndOnlyThePlantAtTheEnd (foss) each failed once under full-suite load at 6f9227a; green 3/3 alone and on the next full run.

## Next, in order

1. Dispatch the two slices above (scopes do not overlap); then a beta APK.
2. Docs slice: gate mapping note in DECISIONS (repo labels vs BRIEF section 10: G4 open until the owner logs several dry-down cycles per weighed pot; G5 is "Proof": baseline comparison + archetype test, published whatever they show); redo section 1 of the advisor report against BRIEF section 10; PRIVACY.md inputs: approximate location (D40), photos on device and in exports (D30), the city list.
3. App: Open-Meteo link next to every place weather data is shown (Today has it; check plant screen and any other).
4. Data/Planner: repot advice re-arm on pot edit and backdated acquisition (D51 follow-up); harness per-plant median dry-out error.
5. Data: next next-entry batch (queue top: tomato, chilli pepper, sweet pepper, rosemary, lavender), then data-review, then pin. PR #12 review minors: two runners starting at once both pass the one-runner check; diff-merge note should quote both drafts; PIPELINE lacks the 5-plant cap; data-review scratch wording.
6. G5 archetype test once a few species are reviewed (BRIEF section 10).

## Waiting on the owner

- Change the data repo's `.nonslop.toml` line "Only the owner sets reviewed" to the new rule (agent marks reviewed after a fresh data-review; toxicity other than unknown needs the owner).
- Boris: cold nights 30 Nov to 2 Dec: insulate, lift off the floor, against the wall; below -5 °C into an unheated room.
- Buy a 1 g scale and start weighing (G4 exit condition, G5 input).
- Before the first public beta: write to Open-Meteo (non-commercial, limit scope), trademark, lawyer, contact address, privacy policy, repo visibility (public now; brief said private), Boris art (asset list in the advisor report section 7).

## Decisions (do not re-argue)

Earlier owner answers A–E, W1–W2, P1–P2, S1–S3, gauge A, Today look: see D31–D45. Advisor answers (owner-accepted): keep interval likelihood (D18); keep engine constants and their AHDB/UMass/UF citations as PRIORs, add an open source for -5 °C when found; Open-Meteo per-address default; winter floor stays "dry"; Chamaecyparis pot keeps dry_top_part and 50 %; FixtureTest table is BRIEF section 11; reviewed_by names the agent; species overrides as normal drafts. Boris: default voice, hard rules 1/2/6/7, approved sample lines in the advisor report are the reference for rule 6, no image until the owner's art.

## Boris (the owner's plant)

*Chamaecyparis lawsoniana* in a black 0.5 L nursery pot inside a ceramic outer pot, covered balcony, Belgrade. Watering point 50 % (D25). Repot in spring.

## Hard-won facts

- `gh pr edit` fails: `gh api -X PATCH repos/stcksmsh/overgrown/pulls/<n> -f body=...`.
- Worktrees need `git submodule update --init`, `ANDROID_HOME=~/Android/Sdk` and a copy of `local.properties`. Worktrees with the submodule need `--force` to remove; leave them.
- `nonslop slice new` parses parentheses in Scope lines as paths: keep Scope lines to plain comma-separated paths.
- Robolectric: wait with `compose.waitUntil`, never sleeps.
