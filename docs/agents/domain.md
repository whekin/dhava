# Domain Docs

How engineering skills should consume Nakvali's domain documentation while exploring the codebase.

## Before exploring, read these

- `CONTEXT.md` at the repository root, when it exists.
- `docs/DECISIONS.md` for the existing chronological architecture and product decision log.
- Relevant ADRs under `docs/adr/`, when that directory exists.
- Follow the session protocol in `AGENTS.md` for `docs/VISION.md`, `docs/ROADMAP.md`, and `docs/WORKLOG.md`.

If `CONTEXT.md` or `docs/adr/` does not exist, proceed silently. Do not propose creating empty documentation upfront. Create or expand it only when domain terminology or a focused architectural decision is actually resolved.

## Layout

Nakvali uses a single-context domain documentation layout across its Android, backend, fusion, protocol, and deployment components:

```text
/
├── CONTEXT.md
├── docs/
│   ├── DECISIONS.md
│   └── adr/
├── android/
├── backend/
├── fusion/
├── proto/
└── deploy/
```

`docs/DECISIONS.md` remains the chronological project-wide decision log. Use `docs/adr/` for focused decisions that benefit from explicit context, alternatives, consequences, or a stable identifier. Do not duplicate or migrate existing decisions without a specific reason.

## Use the glossary's vocabulary

When output names a domain concept—in an issue title, refactor proposal, hypothesis, or test—use the term defined in `CONTEXT.md`. Do not drift toward synonyms the glossary explicitly avoids.

If a required concept is absent, reconsider whether the new language is necessary or note the gap for the domain-modeling workflow.

## Flag decision conflicts

If proposed work contradicts `docs/DECISIONS.md` or an ADR, surface the conflict explicitly instead of silently overriding it.
