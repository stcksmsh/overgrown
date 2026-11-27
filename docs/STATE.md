# State

Handover for the next coordinator. Read this, then run `nonslop status` in BOTH repos. The brief is `docs/BRIEF.md` (gates: section 10). `docs/DESIGN.md` is the UI spec; `docs/DECISIONS.md` records every choice and its reason. The advisor's answers (`docs/reports/advisor-status.md`) were accepted by the owner; they are summarised under Decisions below.

## Repositories and process

- `stcksmsh/overgrown` (public, GPL-3.0-only): app, engine, weather, storage. Local `/secondary/Programming/Github/Overgrown` (its main checkout is a stale detached HEAD: the session-start hook reads that STATE.md, so coordinators read `git show origin/main:docs/STATE.md`; build in worktrees from origin/main). Submodule `overgrown-data` pinned at `fd05404`.
- `stcksmsh/overgrown-data` (private, AGPL-3.0-only): 12 reviewed archetypes; species basil, boxwood, lemon tree, impatiens reviewed (pinned, D53). Plant queue `queue.md`; skills `.claude/skills/next-entry` (one batch, max 5, one runner) and `.claude/skills/data-review` (fresh reviewer). Data PRs: the owner merges with `! gh pr merge <n> -R stcksmsh/overgrown-data --merge`.
- CI: runs on pushes to main, `v*` tags and manual dispatch only (D50); the double build of the foss release APK was made reproducible by an R8 keep rule (D61), green at 606cbd0. PRs have no CI: the local full test command is the PR gate (`.nonslop.toml allow_no_ci = true`). Agents cannot edit CI config (slopstop gate): the owner applies such changes by merging.
- Merging app PRs: the coordinator merges after `nonslop slice verify` passes at the head, a fresh review (clean/minor) is recorded at that head, the branch contains main, and the coordinator's OWN full test run with `--rerun-tasks` at the head is green. Builders' "passed" is not proof.
- Review rule: delegated (agent) review is recorded as `reviewed_by: agent (delegated by owner, <date>)`. Toxicity other than `unknown` always needs two references and the owner's own review.
- Builders put decision text in the PR body ("Decision entry"); the coordinator appends it to DECISIONS after merge, so parallel slices never share DECISIONS.md.
- Scratch: every brief must say "delete only a new folder you created under .claude/tmp/". One builder deleted the coordinator's whole scratch folder once.
- Test APKs: `:app:assembleFossBeta` from a worktree at origin/main, send `app/build/outputs/apk/foss/beta/app-foss-beta.apk` with SendUserFile; never ask the owner to uninstall.

## Done (this round)

#55 four species; #56 flake fixes; #57 docs D53/D54 + PRIVACY; #58 weather credit + herb glyph; #59 unknown root_freeze_alerts = on outdoors (D57); #60 per-place credit (D55/D56); #61 cold/frost alerts for tender plants (D58); #62 toxicity display + disclaimers (D59); #63 reproducible build: R8 keep rule (D61), CI double build green at 606cbd0; #64 onboarding + battery guidance (D60). Last APK sent: 1566ce9.

## In flight

- G5 archetype test (BRIEF section 10; gate status in D54).
- Data repo PR #14 (settle Lawson cypress + snake plant) passed data-review (minor) at 30746d6. The marking commit was blocked by the auto-mode classifier because the data repo's `.nonslop.toml` still says "Only the owner sets reviewed". The owner has been asked to choose A (update that line), B (commit the prepared marking himself; edits in overgrown-data/.claude/tmp/mark14/data) or C (review it himself).

## Next, in order

1. G5 archetype test (in flight).
2. Accessibility pass.
3. Store metadata (fastlane, BRIEF section 9).
4. Data: next-entry batch (tomato, chilli, sweet pepper, rosemary, lavender) once #14 merges.
5. Data-review skill wording: an explicit null on a cold field turns alerts off, it does not fall back.
6. Polish: per-channel notifications off shows no reminders-off line; PR #12 data minors.

## Waiting on the owner

- The A/B/C decision on data PR #14.
- Optional CI step to upload both APKs and mapping.txt when the double-build compare fails (in the PR #63 body).
- Weigh Boris daily (nursery pot only, same time, note rain); first full mark at the next real watering (G4 exit condition, G5 input). More plants are coming: he reports gaps.
- Before release: trademark, lawyer, developer verification, signing key, contact address, privacy policy, Open-Meteo letter, repo visibility (public now; brief said private), Boris art (asset list in the advisor report section 7). Re-enable PR CI before the first beta to others.

## Decisions (do not re-argue)

Earlier owner answers A–E, W1–W2, P1–P2, S1–S3, gauge A, Today look: see D31–D45. Advisor answers (owner-accepted): keep interval likelihood (D18); keep engine constants and their AHDB/UMass/UF citations as PRIORs, add an open source for -5 °C when found; Open-Meteo per-address default; winter floor stays "dry"; Chamaecyparis pot keeps dry_top_part and 50 %; FixtureTest table is BRIEF section 11; reviewed_by names the agent; species overrides as normal drafts. Boris: default voice, hard rules 1/2/6/7, approved sample lines in the advisor report are the reference for rule 6, no image until the owner's art.

## Boris (the owner's plant)

*Chamaecyparis lawsoniana* in a black 0.5 L nursery pot inside a ceramic outer pot, covered balcony, Belgrade. Watering point 50 % (D25). Repot in spring.

## Hard-won facts

- `gh pr edit` fails: `gh api -X PATCH repos/stcksmsh/overgrown/pulls/<n> -f body=...`.
- Worktrees need `git submodule update --init`, `ANDROID_HOME=~/Android/Sdk` and a copy of `local.properties`. Worktrees with the submodule need `--force` to remove; leave them.
- `nonslop slice new` parses parentheses in Scope lines as paths: keep Scope lines to plain comma-separated paths.
- Robolectric: wait with `compose.waitUntil`, never sleeps.
- Full test runs fail intermittently under heavy parallel load (two builders plus a coordinator run): re-run once before blaming the PR.
- Worktree scratch clones under `.claude/tmp` may need manual removal.
