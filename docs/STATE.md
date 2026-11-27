# State

Handover for the next coordinator. Read this, then run `nonslop status` in BOTH repos. The brief is `docs/BRIEF.md` (gates: section 10). `docs/DESIGN.md` is the UI spec; `docs/DECISIONS.md` records every choice and its reason. The advisor's answers (`docs/reports/advisor-status.md`) were accepted by the owner; they are summarised under Decisions below.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown` (its main checkout is a stale detached HEAD: the session-start hook reads that STATE.md, so coordinators read `git show origin/main:docs/STATE.md`; build in worktrees from origin/main). Submodule `overgrown-data` pinned at `fd05404`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): 12 reviewed archetypes; species basil, boxwood, lemon tree, impatiens, Lawson cypress, snake plant reviewed; name table `names/` (D17) with 99 reviewed rows on data main dec2e85 (app pinned at ab7d73a = 50 rows). Plant queue `queue.md`; skills `.claude/skills/next-entry` (one batch, max 5, one runner) and `.claude/skills/data-review` (fresh reviewer). Data PRs: the owner merges them in the browser (he works over Remote Control, where `!` lines arrive as text and do not run): give him the PR URL and "Create a merge commit".
- CI: runs on pushes to main, `v*` tags and manual dispatch only (D50); the double build of the foss release APK was made reproducible by an R8 keep rule (D61), green at 606cbd0. PRs have no CI: the local full test command is the PR gate (`.nonslop.toml allow_no_ci = true`). Agents cannot edit CI config (slopstop gate): the owner applies such changes by merging.
- Merging app PRs: the coordinator merges after `nonslop slice verify` passes at the head, a fresh review (clean/minor) is recorded at that head, the branch contains main, and the coordinator's OWN full test run with `--rerun-tasks` at the head is green. Builders' "passed" is not proof.
- Review rule: delegated (agent) review is recorded as `reviewed_by: agent (delegated by owner, <date>)`. Toxicity other than `unknown` always needs two references and the owner's own review.
- Builders put decision text in the PR body ("Decision entry"); the coordinator appends it to DECISIONS after merge, so parallel slices never share DECISIONS.md.
- Scratch: every brief must say "delete only a new folder you created under .claude/tmp/". One builder deleted the coordinator's whole scratch folder once.
- Test APKs: `:app:assembleFossBeta` from a worktree at origin/main, send `app/build/outputs/apk/foss/beta/app-foss-beta.apk` with SendUserFile; never ask the owner to uninstall.

## Done (this round)

App: #73 pot history (D65); #74 pin c337cdc (D66); #75 name rows in add-plant search (D67); #76 pin 4d3196f (D68); #77 pin ab7d73a + asset task sees `names/**` and app tests depend on it (D69). Data: #14 cypress + snake plant reviewed; #16 name format (D17, quote check decodes JSON escapes only in JSON snapshots, Wikidata names checked per language); #17, #18, #19 name rows reviewed (99). Last APK sent: f3de6ac.

## In flight

Nothing.

## Next, in order

1. App pin to data dec2e85 (99 names): copy `.claude/tmp/fffc12d9/briefs/data-pin-names-2.md`, update ids test to 99 and a batch-3 search (e.g. "African violet"), D70. Then send an APK.
2. Name batch 4 (about 50; brief template `.claude/tmp/fffc12d9/briefs/names-batch-3.md`), then 5 until about 300. Each batch: builder -> fresh data-review -> fix -> marking commit in the same PR -> haiku check of the marking diff -> CI -> owner merges -> app pin slice. Known gaps with no source: pothos, moth orchid, Boston fern, en dumb cane. Lemongrass needs a species entry.
3. Data docs: D17/PIPELINE describe the WFO-synonym item tie and genus-item names via P171.
4. Polish backlog `.claude/tmp/fffc12d9/polish-backlog.md` (PR #64 leftovers, data-review cold-null wording, validator misses null cold fields inside in_ground/outdoor_container, PR #12/#15 minors, careAsset input path sensitivity, sim report "90 days").
5. Species next-entry batch (tomato, chilli, sweet pepper, rosemary, lavender) when full care data is wanted beyond archetypes.

## Waiting on the owner

- Test the APK at f3de6ac: Room v3 migration kept Boris; pot edit re-arms repot advice; search "mint", "geranium", "мушк".
- Optional CI changes: upload both APKs and mapping.txt when the double-build compare fails (snippet in the PR #63 body); run `:engine-sim:archetypeReport` in the "Simulation report is current" step.
- Boris: cold nights 30 Nov to 2 Dec (drop after 2 Dec): insulate, lift off the floor, against the wall; below -5 °C into an unheated room. Weigh daily (nursery pot only, same time, note rain); first full mark at the next real watering.
- Before release: trademark, lawyer, developer verification, signing key, contact address, privacy policy, Open-Meteo letter, repo visibility, Boris art. Re-enable PR CI before the first beta to others.

## Decisions (do not re-argue)

Earlier owner answers A–E, W1–W2, P1–P2, S1–S3, gauge A, Today look: see D31–D45. Advisor answers (owner-accepted): keep interval likelihood (D18); keep engine constants and their AHDB/UMass/UF citations as PRIORs, add an open source for -5 °C when found; Open-Meteo per-address default; winter floor stays "dry"; Chamaecyparis pot keeps dry_top_part and 50 %; FixtureTest table is BRIEF section 11; reviewed_by names the agent; species overrides as normal drafts. Boris: default voice, hard rules 1/2/6/7, approved sample lines in the advisor report are the reference for rule 6, no image until the owner's art.

This round: pot history option A (D65). Name table option A (data D17): separate `names/` rows, no care data, picking one stores only the archetype (app D67). Data `.nonslop.toml` line 35: the agent marks reviewed after a fresh data-review; toxicity other than `unknown` stays owner-only.

## Boris (the owner's plant)

*Chamaecyparis lawsoniana* in a black 0.5 L nursery pot inside a ceramic outer pot, covered balcony, Belgrade. Watering point 50 % (D25). Repot in spring.

## Hard-won facts

- The merge guard looks the PR up from the current directory: run `gh pr merge` with `-R stcksmsh/overgrown` from the app checkout, never from overgrown-data.
- Reviewers and builders cannot always delete scratch clones (recursive delete denied); the coordinator removes them.
- `gh pr edit` fails: `gh api -X PATCH repos/stcksmsh/overgrown/pulls/<n> -f body=...`.
- Worktrees need `git submodule update --init`, `ANDROID_HOME=~/Android/Sdk` and a copy of `local.properties`. Worktrees with the submodule need `--force` to remove; leave them.
- `nonslop slice new` parses parentheses in Scope lines as paths: keep Scope lines to plain comma-separated paths.
- Robolectric: wait with `compose.waitUntil`, never sleeps.
- Full test runs fail intermittently under heavy parallel load (two builders plus a coordinator run): re-run once before blaming the PR.
- The merge guard can fail to look up a PR inside a compound command: run `gh pr ready` and `gh pr merge` as separate single commands.
- Builders can report edits that never landed (PR #77): check the diff yourself before trusting a report.
- Name rows inherit archetype cold fields with no override: a frost-tender plant must not map to a hardy archetype (lemongrass, PR #19).
