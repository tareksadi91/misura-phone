# Misura Phone

register: product

## Product purpose
An agent-first Android phone (prototype on Nothing Phone (3a)) for people who want to spend less time on their phone. You say what you want; the agent works across your apps and shows only the small interactive card you need, then you put the phone down. Source of truth: `docs/superpowers/specs/2026-11-09-misura-demo1-design.md`.

## Users
the maintainer first (secondary phone, iPhone stays primary). Demo audience: people watching a short phone-in-hand video who should understand each step at a glance.

## Brand and tone
Calm, physical, precise. The screen continues the hardware: the (3a)'s transparent back (frosted plastic, visible internals, printed labels) carried into the UI. Orange is the agent's light and "needs you", nothing else. Hanken Grotesk on the phone. The particle sun is the agent.

## Principles
- Cards, not chat: the agent talks through interactive cards; plain text is allowed inside a card when the answer is text.
- Buttons commit, voice prepares. Sends, bookings and other commits are meant to go through a tap on a card that shows the exact content (a spoken "go" can also approve, and Undo exists). The gate is label-based and has known gaps; see README.md and docs/OSS_AUDIT.md.
- Glance and put down. Every card says what's happening and whether it needs you.

## Anti-references
- Chat logs or terminal-style text UI.
- Mono-label editorial look, Newsreader italics ("AI tropes", rejected).
- Generic glassmorphism; copying Nothing's own fonts, logo or dot-matrix type.
- A virus-like surface-dot agent (rejected); the agent stays volumetric, faint, dense.
