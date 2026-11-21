# 09 — Session prompts (copy and paste)

Run `claude` in the project folder with **Opus 5.5** as the model. `CLAUDE.md` loads automatically, and it holds the orchestration rules.

---

## S0 — Verify the setup
```
Verify my dev environment for this project (see docs/08-desktop-setup.md):
adb devices shows the Pixel, java/python/node/git/codex are on PATH,
bash tools/codex-usage.sh works, and ANDROID_HOME is set.
Report a pass/fail table and how to fix anything that failed. Don't create the Android project yet.
```

## S1 — Planning gate + skeleton + test data
```
Read CLAUDE.md, every file in docs/ (including 00-chatgpt-brainstorm-transcript.md), and tracker.md.

Then:
1. Give me a short consolidated summary of the PRD and architecture, and flag anything
   inconsistent, risky or over-engineered.
2. Walk me through the decisions in docs/07-risks-open-questions.md section B
   (one compact table; I'll accept or change the defaults).
3. Have a Sonnet subagent check the latest STABLE versions of every dependency in
   docs/03-dependencies-licensing.md and verify the licences and maintenance status.
4. Post the S1 plan (tasks, Codex/Sonnet/Opus split, worker count from codex-usage.sh,
   acceptance checks) and WAIT for my "go".

After my go: Gradle multi-module skeleton + design system + 4-tab shell, .gitignore,
gradle.properties, tools/check.sh, and the fixture generators producing test-data/
with ground truth. Install on the Pixel, screenshot it and check the result. Update docs if decisions changed,
update tracker.md, commit, ntfy.
```

## Phase sessions (P1 … P8), the same template every time
```
Read CLAUDE.md and tracker.md. We're on <PHASE NAME> from docs/05-roadmap-sessions.md
(<session k of n> for this phase).

1. Confirm `tools/check.sh` is green on main. If it isn't, fix that first.
2. Run tools/codex-usage.sh and choose the worker count per CLAUDE.md.
3. Post a plan: tasks, who does each (Opus / Sonnet / Codex), worktree/branch per worker,
   acceptance checks from the PRD and 06-testing-strategy. Wait for my go.
4. Execute: briefs on disk, dispatch workers, review diffs, merge only on green check.sh,
   then verify on the Pixel with screenshots. Give me a short manual checklist for anything
   camera/UX-related that you can't verify yourself.
5. ntfy after each milestone. At the end: update tracker.md (done / partial / next / surprises),
   commit, final ntfy.
```

## Phase 2 extra: real-photo tuning round
```
I've copied my real Pixel scans to test-data/scanner/real/. Label the corners for ~10 of
them with the labelling script, run the imaging-lab regression on synthetic + real, show me
contact sheets of the worst 8 cases, propose parameter/algorithm changes, re-run, and
port the improvements to Kotlin with parity tests.
```

## Bug-fix / daily-use feedback session
```
Read CLAUDE.md and tracker.md. Here are the problems I found using the app daily:
<list, with screenshots if any>
Triage them (severity, root-cause guess, owner Opus/Sonnet/Codex), post a fix plan, wait for go.
```

## When a session runs out of Claude usage mid-way
```
Resume: read tracker.md and `git log -5` and `git worktree list`, report what state each
worker branch is in, then continue the plan from where it stopped.
```
