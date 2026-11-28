# State

Handover for the next coordinator. Read this, then run `nonslop status` in BOTH repos. The brief is `docs/BRIEF.md` (gates: section 10). `docs/DESIGN.md` is the UI spec; `docs/DECISIONS.md` records every choice and its reason. The advisor's answers (`docs/reports/advisor-status.md`) were accepted by the owner; they are summarised under Decisions below.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown` (its main checkout is a stale detached HEAD: the session-start hook reads that STATE.md, so coordinators read `git show origin/main:docs/STATE.md`; build in worktrees from origin/main). Submodule `overgrown-data` pinned at `e4366ba` (after PR #83).
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): 12 reviewed archetypes; species basil, boxwood, lemon tree, impatiens, Lawson cypress, snake plant reviewed; name table `names/` (D17) with 286 reviewed rows on data main e4366ba. Plant queue `queue.md`; skills `.claude/skills/next-entry` (one batch, max 5, one runner) and `.claude/skills/data-review` (fresh reviewer). Data PRs: the owner merges them in the browser (he works over Remote Control, where `!` lines arrive as text and do not run): give him the PR URL and "Create a merge commit".
- CI: runs on pushes to main, `v*` tags and manual dispatch only (D50); the double build of the foss release APK was made reproducible by an R8 keep rule (D61), green at 606cbd0. PRs have no CI: the local full test command is the PR gate (`.nonslop.toml allow_no_ci = true`). Agents cannot edit CI config (slopstop gate): the owner applies such changes by merging.
- Merging app PRs: the coordinator merges after `nonslop slice verify` passes at the head, a fresh review (clean/minor) is recorded at that head, the branch contains main, and the coordinator's OWN full test run with `--rerun-tasks` at the head is green. Builders' "passed" is not proof.
- Review rule: delegated (agent) review is recorded as `reviewed_by: agent (delegated by owner, <date>)`. Toxicity other than `unknown` always needs two references and the owner's own review.
- Builders put decision text in the PR body ("Decision entry"); the coordinator appends it to DECISIONS after merge, so parallel slices never share DECISIONS.md.
- Scratch: every brief must say "delete only a new folder you created under .claude/tmp/". One builder deleted the coordinator's whole scratch folder once.
- Test APKs: `:app:assembleFossBeta` from a worktree at origin/main, send `app/build/outputs/apk/foss/beta/app-foss-beta.apk` with SendUserFile; never ask the owner to uninstall.

## Done (this round)

App: #79-#83 pins to e4366ba, 286 names (D70-D74); #84 undo-delete survives leaving the screen (D75); #85 all writes and reschedules on the app scope `Graph.scope`/`g.outlive` (D76); #86 notification tests; #87 wizard forecast after leaving, Undo wins same-frame race, sim horizon constant (D77 appended by #88). Data: #20-#23 name batches 4-7. Last APK sent: e63ef3d.

## In flight
Data PR #24 (validator rejects null cold fields in species; citrus min_temp_c 10.0) and #25 (shared-name sweep: 8 names removed, 25 kept as clearly meant): both verified, reviewed clean, CI green; owner merging.

## Next, in order

1. After #24 and #25 merge: app pin slice to the new data head (template `.claude/tmp/fffc12d9/briefs/data-pin-names-7.md`; ids test stays 286; add a test that the lemon asset has min_temp_c 10.0; D78), then an APK.
2. Archetype cold values: 7 archetypes have min_temp_c null and 11 root_kill_temp_c null, so members without their own value get no COLD/root-kill alert (FROST works). Source cautious values, two publishers each, through the next-entry/data-review pipeline.
3. Re-run the sweep's second search pass (wbgetentities exact labels+aliases; the old xsearch2.py misses aliases hidden behind a matching label) over the 48 batch-7 rows.
4. Data docs: D17/PIPELINE: WFO-synonym item tie, genus-item names via P171, the shared-name rule with its "unless clearly meant" exception and the ancestor/descendant rule.
5. Polish backlog `.claude/tmp/fffc12d9/polish-backlog.md` (data-review/PR #12/#15/#21/#23 minors, 35 null cold fields in drafts/ that validate does not check, PR #87 minors).
6. Species next-entry batch (tomato, chilli, sweet pepper, rosemary, lavender) when full care data is wanted.

## Waiting on the owner

- Test the latest APK: Boris kept; pot edit re-arms repot advice; delete a history entry and leave at once (stays deleted); Watered then Back (event kept, reminder updated); search "wild thyme", "lilac".
- Optional CI changes: upload both APKs and mapping.txt when the double-build compare fails (snippet in the PR #63 body); run `:engine-sim:archetypeReport` in the "Simulation report is current" step.
- Boris: cold nights 30 Nov to 2 Dec (drop after 2 Dec): insulate, lift off the floor, against the wall; below -5 °C into an unheated room. Weigh daily on the gram scale only (about 18:00, note rain). The 24 Nov 300 g reading was on a 50 g-step scale: not a full mark. At the next real watering: weigh before (dry end), water, drain an hour, weigh (full end). Readings are in the coordinator's private memory, never in this repo.
- Before release: trademark, lawyer, developer verification, signing key, contact address, privacy policy, Open-Meteo letter, repo visibility, Boris art. Re-enable PR CI before the first beta to others.

## Decisions (do not re-argue)

Earlier owner answers A–E, W1–W2, P1–P2, S1–S3, gauge A, Today look: see D31–D45. Advisor answers (owner-accepted): keep interval likelihood (D18); keep engine constants and their AHDB/UMass/UF citations as PRIORs, add an open source for -5 °C when found; Open-Meteo per-address default; winter floor stays "dry"; Chamaecyparis pot keeps dry_top_part and 50 %; FixtureTest table is BRIEF section 11; reviewed_by names the agent; species overrides as normal drafts. Boris: default voice, hard rules 1/2/6/7, approved sample lines in the advisor report are the reference for rule 6, no image until the owner's art.

This round: pot history option A (D65). Name table option A (data D17): separate `names/` rows, no care data, picking one stores only the archetype (app D67). Data `.nonslop.toml` line 35: the agent marks reviewed after a fresh data-review; toxicity other than `unknown` stays owner-only.

## Boris (the owner's plant)

*Chamaecyparis lawsoniana* in a black 0.5 L nursery pot inside a ceramic outer pot, covered balcony, Belgrade. Watering point 50 % (D25). Repot in spring.

## Hard-won facts

- The merge guard looks the PR up from the current directory: run `gh pr merge` with `-R stcksmsh/overgrown` from the app checkout, never from overgrown-data, and as a single command (not inside a compound one).
- Reviewers and builders cannot always delete scratch clones (recursive delete denied); the coordinator removes them.
- Worktrees need `git submodule update --init`, `ANDROID_HOME=~/Android/Sdk` and a copy of `local.properties`. Worktrees with the submodule need `--force` to remove; leave them.
- `nonslop slice new` parses parentheses in Scope lines as paths: keep Scope lines to plain comma-separated paths.
- Robolectric: wait with `compose.waitUntil`, never sleeps.
- Full test runs fail intermittently under heavy parallel load: re-run once before blaming the PR.
- Builders can report edits that never landed (PR #77): check the diff yourself before trusting a report.
- Name rows inherit archetype cold fields: a frost-tender plant must not map to a hardy archetype (lemongrass). Other name-row must-fixes: unplaced WFO id (P7715 must equal the accepted wfo_id), names from a group item without a quoted P171/P225 tie, WFO placing the plant people mean in another genus (schefflera, hatiora), trees or climbers on the shrub archetype, Wikidata P225 vs WFO name disagreement, names Wikidata also gives an unrelated plant, unless this plant is clearly what growers here mean (genus/family or species-within-genus overlaps get a note only).
- Data builders can loop re-sending their report after DONE: once pushed and clean, TaskStop them. Dispatch each builder from its own repo's root (cd first): the worktree is made from the coordinator's current directory.
- `gradle build` does not regenerate docs/reports/archetype-test.md: pin slices run `:engine-sim:archetypeReport :engine-sim:simReport`.
