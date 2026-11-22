# ChatGPT chat 2: why PDF tools exist (saved 2026-11-15)

Source: https://chatgpt.com/share/6af951f2-764c-83ee-b6f3-143e2711119e
Saved verbatim. The features chosen from it are in 01-PRD.md (RD-*, PT-10..16, SC-12..16, DM-8..10). Not adopted: a Windows app / Kotlin Multiplatform, MuPDF (AGPL), and local-LLM summaries (see 07 §D).

---

This is a copy of a shared ChatGPT chat. It won't be added to memory.

Report conversation
Today 12:18 PM

recently using claude and found out that pymupdf and other pdf editing tools in cli has most of the features that some paid companies offer
like some pdf reader app in mobile doesn't allow you to create bookmark or chapter unless u pay, which claude can do from cli easily 
so why does they exist?

Bro, you've stumbled upon one of the most interesting realities of modern software development:

A surprising number of paid software features aren't technically difficult to implement. They're packaged, polished, and monetized versions of capabilities that already exist in open-source libraries.

And PDF software is an almost perfect example of this.

Your observation about PyMuPDF is absolutely valid. You can use Python scripts and CLI tools to do things that some mobile PDF applications put behind a premium subscription.

But there's an interesting distinction here:

Implementing a feature is not the same as delivering a complete product.

Let's break down why these companies exist.

1. The actual PDF ecosystem is surprisingly powerful

Examples of open-source tools and what they can do

 

PyMuPDF

Add, remove and modify bookmarks (table of contents).

Extract text and images.

Insert annotations and links.

Merge, split and rearrange pages.

Modify PDF metadata.

 

qpdf

Encrypt and decrypt PDFs.

Linearize PDFs for web viewing.

Transform PDF structures.

Inspect and repair certain structural issues.

 

Ghostscript

Compress PDFs.

Convert PDF formats.

Control image resolution.

Process and optimize documents.

 

OCRmyPDF

Add OCR text layers.

Make scanned PDFs searchable.

Preserve the original scanned appearance.

Process multiple documents in batches.

And that's not even the entire ecosystem.

You can combine these tools into a workflow that resembles a fairly expensive commercial PDF application.

2. So why do paid PDF applications exist?

There are several reasons.

What you pay for

	

What it actually means

User interface

	

Buttons, menus, drag-and-drop, visual page management

Convenience

	

No terminal, Python scripts or commands

Integration

	

Android storage, cloud drives, sharing, printing

Reliability

	

Handling thousands of weird PDF edge cases

Compatibility

	

Consistent behavior across different PDF viewers

Support

	

Customer service, updates and troubleshooting

Business features

	

Team collaboration, enterprise security, compliance

Consider your bookmark example.

PyMuPDF can create a complete hierarchical bookmark structure using set_toc(). That's a relatively straightforward programming operation. 
GitHub
+1

But imagine a regular student who:

Downloads a 900-page textbook.

Wants chapters and subchapters.

Doesn't know Python.

Doesn't want to install dependencies.

Wants to do everything from their phone.

The commercial app sells that convenience.

And there's another important factor: subscription economics.

Some apps deliberately reserve features for premium tiers because:

Free users generate little direct revenue.

Subscriptions create recurring revenue.

Users are often willing to pay small amounts to avoid inconvenience.

Feature gating encourages upgrades even when the underlying implementation is relatively inexpensive.

Not every premium feature is justified by its development cost.

3. The AI factor makes this even more interesting

Here's where your Claude experience becomes particularly relevant.

Previously, you needed three things:

Knowledge of the library.

Programming ability.

Time to write and debug scripts.

Now you can simply tell Claude:

"Open this PDF, inspect its chapter structure, identify all chapter headings, create hierarchical bookmarks, preserve the existing formatting, and save a modified copy."

Claude can write the PyMuPDF script, execute it, inspect the output and fix problems.

Essentially, AI has reduced the cost of operating powerful software libraries.

The libraries haven't suddenly become more powerful. Their capabilities have become more accessible.

4. This has a huge implication for your own Android PDF project

Remember your scanner + PDF reader project?

I'd actually reconsider how you approach its architecture.

Think of it as three separate layers.

1. User interface

Android / Material Design

Tabs, annotations, bookmarks, page navigation, document management

2. PDF processing engine

Native libraries / SDKs

Rendering, editing, merging, splitting, OCR, bookmarks, compression

3. Document storage

Local-first architecture

Files, annotations, reading positions, metadata, backups

The important thing is that you don't need to implement every PDF operation from scratch.

You can build a beautiful Android application around existing PDF engines.

One caveat: PyMuPDF itself isn't automatically the best choice for Android. You need to investigate native Android compatibility, licensing (including MuPDF's AGPL/commercial licensing implications), binary size and performance. A Python library that works perfectly on Windows isn't necessarily a good mobile dependency.

5. There's a bigger software industry trend here

I think we're entering an interesting period:

Previously

	

Increasingly possible now

Buy a PDF editor

	

Build a custom PDF workflow

Buy an image-processing application

	

Combine ImageMagick/OpenCV

Buy a transcription service

	

Run Whisper locally

Buy a document converter

	

Use LibreOffice CLI

Buy a web scraper

	

Use Playwright

Buy a data analysis tool

	

Use Python + pandas

Buy an automation service

	

Use scripts + AI agents

This doesn't mean commercial software will disappear.

It means the value proposition is shifting from having the functionality to making the functionality effortless, reliable and accessible.

My personal takeaway for your project: Don't try to compete with commercial PDF apps by implementing more PDF algorithms. Compete by combining existing capabilities into a much better user experience.

For example, imagine a free, offline-first Android PDF reader that has:

Unlimited hierarchical bookmarks.

Multiple PDF tabs with preserved reading positions.

Highlighting and annotations.

Automatic chapter detection using AI.

Page rearrangement and merging.

OCR, including Bangla.

No subscription locks.

Most of the underlying capabilities already exist in some form. The challenge is integrating them into one coherent, reliable application.

And honestly, that's a much more interesting engineering problem than reinventing a PDF processing engine.

Useful starting point: PyMuPDF feature comparison  and PyMuPDF document API .

what if
I have claude build my pdf app
which I can have for both my pc and mobile
that way, with all free tools available and implemented correctly
I no longer need to depend on them, like sharing data with then is still a risk of having malware byw

Honestly? That's a very reasonable long-term project, and potentially one of the most useful applications you could build with Claude Code.

You're essentially proposing to own your document infrastructure rather than depending on commercial PDF services.

Instead of paying for premium features, accepting advertisements, uploading documents to third-party servers, or trusting an app with your files, you'd have your own PDF ecosystem.

And there's a particularly attractive aspect: you could use the same application across your Windows PC and Android phone.

But I'd approach this slightly differently from simply asking Claude to build a PDF reader.

1. Build your own document ecosystem
Your personal PDF suite
 

Windows application

Full-featured PDF workspace

 

Android application

Portable document workspace

Shared capabilities

Same document organization

Bookmarks and chapter navigation

Annotations and highlights

Reading progress

PDF editing and conversion

Offline processing

Optional private synchronization

You'd essentially have one product with two interfaces.

And importantly, your desktop could have advanced features while the mobile version focuses on touch-friendly document reading and editing.

2. What I'd put into it

I'd divide the features into five modules.

Module

	

Features

Reader

	

Tabs, bookmarks, chapters, search, TOC, reading history

Editor

	

Merge, split, rotate, reorder, delete, insert pages

Annotation

	

Highlight, underline, comments, drawing, signatures

Scanner

	

Camera capture, edge detection, perspective correction, OCR

Utilities

	

Compress, encrypt, convert, extract images/text, metadata

A few features I'd particularly prioritize:

Automatic chapter detection: Detect headings and generate hierarchical bookmarks, even for PDFs that don't contain a table of contents.

Persistent tabs: Open 10 PDFs and retain each one's page, zoom, scroll position and annotation state.

Batch processing: Process entire folders without manually opening every document.

Bangla OCR: Particularly useful for your academic documents.

Document comparison: Compare two versions of a PDF and highlight differences.

3. How I'd build it technically

I wouldn't necessarily force both platforms to use exactly the same UI framework.

I'd consider this architecture:

Shared document core

Common business logic, metadata, bookmarks, annotations and document operations

Windows

Desktop UI

Native PDF rendering

Keyboard shortcuts

Multi-window support

Android

Native Material UI

Touch gestures

Camera integration

Android storage

Local document storage

SQLite + filesystem + optional encrypted backups

Some technologies worth investigating:

Kotlin Multiplatform: Sharing business logic between Android and desktop.

MuPDF: PDF rendering and manipulation.

PDFium: Another rendering option.

OpenCV: Scanner edge detection and image correction.

Tesseract: Offline OCR.

One important licensing warning: open source doesn't always mean unrestricted commercial redistribution. For example, MuPDF has AGPL and commercial licensing options. Claude needs to evaluate the licensing of every dependency before bundling it.

4. Your security argument is actually important

You mentioned malware and data sharing. There are two different risks here.

Risk A: Data exposure

Some commercial applications upload documents to cloud servers for OCR, conversion, AI processing or synchronization.

A local-first application can eliminate the need for those uploads.

Risk B: Malware and supply-chain attacks

Third-party applications and dependencies can contain vulnerabilities or malicious code. Even a malicious PDF can potentially exploit vulnerabilities in a PDF parser.

Building your own application doesn't automatically eliminate these risks.

I'd implement these security principles:

No mandatory account or cloud connection.

No telemetry or analytics by default.

All document processing happens locally.

Use reputable, maintained dependencies.

Pin dependency versions and audit updates.

Restrict access to files and Android permissions.

Keep the PDF processing engine updated against security vulnerabilities.

Never automatically execute embedded PDF JavaScript or attachments.

Provide encrypted backups.

One more thing: Claude Code itself will download packages, execute commands and modify files while developing. Don't blindly approve arbitrary commands or binaries. Review the dependency tree and build artifacts before trusting the finished application.

5. How I'd approach development

I would not ask Claude to build everything simultaneously.

I'd use four milestones:

Milestone

	

Deliverable

1

	

Windows PDF reader + editor

2

	

Android reader + editor

3

	

Scanner + OCR + advanced utilities

4

	

Cross-device synchronization and backup

Start with the Windows application because testing and debugging document-processing workflows is easier there. Then build the Android counterpart around the validated document core.

And use a substantial test corpus: textbooks, scanned documents, encrypted PDFs, large PDFs, malformed files, Bangla documents, PDFs with existing bookmarks, annotations and unusual fonts.

Don't just test whether the app opens a PDF. Test whether editing it preserves everything that should remain intact.

6. There's one feature I'd add that could make this particularly valuable
My recommendation

A personal document management system.

Imagine having:

A library of all your academic PDFs.

Automatic categorization.

Chapter and bookmark generation.

Full-text search across your entire library.

Offline OCR.

A reading-history dashboard.

Optional AI-powered document summaries using local models.

A desktop could even run local document-processing and AI workloads, while your phone handles everyday reading and scanning.

You wouldn't need to pay for a PDF subscription or send your academic documents to an external AI service.

My final opinion

I think this is worth building, particularly because you're not just building another portfolio project. You're building software you could genuinely use for years.

One thing I'd be careful about: don't turn it into a gigantic project before getting the reader and editor working reliably.

I'd aim for a polished, offline-first alternative to Adobe Acrobat, rather than trying to reproduce every feature of Acrobat immediately.

And I'd make one architectural decision from day one: your documents and their metadata must never depend on the application itself to remain accessible. If you uninstall your app, your PDFs, annotations and bookmarks should still be recoverable through standard formats or documented exports.

That's what owning your software should mean.

ChatGPT is AI and can make mistakes.