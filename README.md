# Paperless — personal offline document scanner + PDF toolkit (Android)

Working name: **Paperless**. You can rename it at the planning gate (session S1).

Native Android app for the Pixel 8 Pro. It replaces CamScanner: no watermark, no Pro pop-ups, no account, no cloud, no AI APIs.
- **Scanner:** batch and single-page capture, auto edge detection, auto-capture, shadow and glare cleanup, ID card, book and whiteboard modes, two-sided batches, auto-split of a pile at blank sheets, pen-ink removal, auto-deskew, share-to-scan, and a Quick Settings tile.
- **Document library:** folders, favourites, name and in-document search, adding pages to an old scan, biometric lock, and plain-file backup/restore.
- **PDF reader:** multiple tabs, outline and bookmark editor, auto chapter suggestions, find/select/copy, links, crop-margins mode, reading progress, side-by-side.
- **PDF toolkit:** merge, split, page edits, compress, image↔PDF, annotations, "same page width" (the GoodNotes fix), password, N-up print layouts with duplex cut orders, page numbers, metadata, sanitize, grayscale, and batch runs on folders.

This folder holds **only the plan**. Code gets written on the desktop.

## How to start on the desktop

1. Copy this whole folder to a **short path with no spaces**, for example `D:\dev\paperless`. Gradle and the Android NDK break on long Windows paths or paths with spaces, and `E:\4-1 Course Works\...` has both.
2. Work through [docs/08-desktop-setup.md](docs/08-desktop-setup.md): Android Studio, SDK, adb, Pixel USB debugging, Python, Codex, and Claude Code.
3. Open a terminal in the folder and run `claude` (model: Opus 5.5).
4. Paste the **S1 kickoff prompt** from [docs/09-session-prompts.md](docs/09-session-prompts.md).
5. After each session, Claude updates [tracker.md](tracker.md). Start the next session with the matching prompt from doc 09.

## What's in here

| File | What it is |
|---|---|
| [CLAUDE.md](CLAUDE.md) | Project rules Claude Code loads automatically: orchestration (Opus + Sonnet + Codex), stack, hard constraints |
| [tracker.md](tracker.md) | Session-by-session progress checklist |
| [docs/00-chatgpt-brainstorm-transcript.md](docs/00-chatgpt-brainstorm-transcript.md) | The original ChatGPT conversation, saved verbatim |
| [docs/00b-chatgpt-pdf-tools-transcript.md](docs/00b-chatgpt-pdf-tools-transcript.md) | Second ChatGPT chat (why PDF tools exist), the source of the 2026-11-15 additions |
| [docs/01-PRD.md](docs/01-PRD.md) | Consolidated requirements: v1 scope, excluded features, future features |
| [docs/02-architecture.md](docs/02-architecture.md) | Modules, data model, image pipeline, reader design |
| [docs/03-dependencies-licensing.md](docs/03-dependencies-licensing.md) | Free/Apache-licensed stack, plus what to avoid (AGPL, commercial SDKs) |
| [docs/04-screens-navigation.md](docs/04-screens-navigation.md) | Screen-by-screen spec and navigation flow |
| [docs/05-roadmap-sessions.md](docs/05-roadmap-sessions.md) | Phased roadmap with **session estimates** and worker split |
| [docs/06-testing-strategy.md](docs/06-testing-strategy.md) | Test dataset, automated tests, on-device checks |
| [docs/07-risks-open-questions.md](docs/07-risks-open-questions.md) | Technical risks, plus decisions for the S1 gate (each has a recommended default) |
| [docs/08-desktop-setup.md](docs/08-desktop-setup.md) | One-time desktop setup |
| [docs/09-session-prompts.md](docs/09-session-prompts.md) | Copy-paste prompts for each session |
| [tools/codex-usage.sh](tools/codex-usage.sh) | Prints Codex 5-hour and weekly usage; used to decide how many workers to run |
| [tools/pdf-fixwidth/](tools/pdf-fixwidth/) | Working desktop tool that makes every PDF page the same width (the GoodNotes fix). It's usable today, and it's the reference and test oracle for the app's PT-9 feature |

## Estimate in one line

About **26–29 Opus-orchestrated sessions** for the full v1 (16–19 before the 2026-11-15 additions). The minimal version (batch scan → auto edges → white enhancement → export PDF/images, plus adding pages to an old scan) is a **daily driver after about 6 sessions**. One session means one Claude Code 5-hour usage window, roughly 2–4 hours of wall-clock time. Details are in [docs/05-roadmap-sessions.md](docs/05-roadmap-sessions.md).

**Total extra cost: ৳0.** Every dependency is free. A Play Store listing is optional, at US$25 one time, and isn't planned.
