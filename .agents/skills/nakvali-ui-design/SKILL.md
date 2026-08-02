---
name: nakvali-ui-design
description: Design, implement, review, or polish Nakvali's native Android UI in Kotlin and Jetpack Compose. Use for Nakvali screens, components, navigation surfaces, visual hierarchy, interaction states, accessibility, screenshot review, or UI refactors, especially the recorder, activity archive/detail, save flow, and settings.
---

# Nakvali UI design

Build a dark-first field instrument for downhill riders, not a generic fitness dashboard. Preserve recording reliability and the architecture in `AGENTS.md`; keep UI logic out of Rust fusion behavior and avoid changing product semantics merely to simplify a screen.

## Establish context

1. Read the latest entries in `docs/WORKLOG.md` and the relevant part of `docs/VISION.md` or `docs/ROADMAP.md`.
2. Inspect the screen, its state model, and shared tokens in `android/core/ui` before proposing changes.
3. Run the current screen on a device or emulator and capture it when the tooling is available. Do not judge an Android layout only from source.
4. Identify the rider's primary question and primary action for every state before editing.

## Preserve the visual identity

- Treat the map as the record screen's working surface. Overlay only information and controls that must stay visible; do not veil it with a full-screen translucent sheet.
- Use the dirt-red brand palette and `NakvaliTheme`. Keep dynamic color off unless the product decision changes. Design dark mode first, then verify light mode.
- Make live speed, elapsed time, readiness, and recording state readable at a glance outdoors. Prefer large tabular-looking numerals and short labels to dense prose.
- Use Material 3 Expressive selectively for meaningful state change, hierarchy, and tactile motion. Avoid playful motion during recording or any animation that delays control feedback.
- Prefer a few strong surfaces over nested cards. Use whitespace, typography, alignment, and contrast before adding containers, outlines, shadows, or color.
- Keep corners and shapes purposeful. Do not make every element a rounded rectangle.
- Use icons from the established Material icon set when available. Do not use Unicode glyphs as production control icons.
- Avoid generic AI styling: purple/blue gradients, glassmorphism, decorative glow, arbitrary accent colors, equal-weight metric cards, slogans without product value, and dashboards copied from web layouts.

## Design for the ride

- Assume sun, forest shade, vibration, gloves, rain, fatigue, and one-handed interaction.
- Give primary ride controls at least a 64 dp target; retain the established 88 dp targets for start, pause, resume, and finish unless device evidence supports a change.
- Separate destructive actions spatially and visually. Require confirmation for finishing or discarding when data could be lost.
- Never encode state by color alone. Pair color with text, icon, shape, or position.
- Keep active controls above gesture-navigation insets and keep forms usable with the IME open.
- Prefer honest missing data (`—`) over fabricated zeroes. Distinguish preparing, unavailable, stale, still, paused, saving, failed, and complete states.
- Preserve map gestures: user pan or zoom must suspend follow mode, and an obvious recenter action must restore it.
- Keep navigation secondary while recording; never let chrome compete with the live instrument panel.

## Keep map-led details progressive

- For a map-led detail screen, prefer a persistent standard bottom sheet over a
  fixed card or modal sheet. Keep the map full-bleed and interactive outside the
  sheet; do not add a scrim.
- Start partially expanded with a compact peek that contains identity, status and
  frequent actions. Do not spend the collapsed height on verbose metrics.
- Keep the sheet header pinned and put longer metrics, quality explanations and
  secondary actions in a nested vertical scroll region. A large font or compact
  screen must not clip the last item.
- Leave a deliberate strip of map visible when expanded and keep top map controls
  outside that extent. Account for gesture-navigation insets inside the sheet.
- Verify all three gesture paths on a device: drag the sheet from its handle/header,
  scroll its expanded content, and pan/zoom the exposed map without moving the sheet.
  If the detail identity or its actions are essential, do not allow the sheet to
  become fully hidden.

## Cover the complete state model

For changed surfaces, handle the applicable states explicitly:

- Record: idle, permission blocked, storage blocked, preparing, GPS/IMU readiness, recording while moving, recording while still, paused, confirmation, and service failure.
- Save: processing, editable metadata, keyboard open, validation, saving, retryable failure, discard confirmation, and complete.
- Activities: empty, populated, partially processed, failed processing, long titles, and deletion/export actions.
- Settings: defaults, disabled dependencies, explanatory copy, persistence feedback, and permission/system-setting handoffs.

Do not collapse technically distinct states into one visual treatment when the rider needs a different action or expectation.

## Implement cohesively

1. Reuse or extend shared color, typography, shape, spacing, icon, and component primitives in `:core:ui`; avoid feature-local token drift.
2. Keep feature modules dependent only on core modules, never on other features.
3. Keep composables focused and previewable. Hoist state and callbacks; avoid embedding service or persistence work in presentation components.
4. Add semantics and stable test tags only where they improve accessibility or robust automation.
5. Use concise English UI copy consistent with the existing product voice.
6. Preserve edge-to-edge behavior and apply insets once at the correct ownership boundary.

## Verify visually and behaviorally

1. Build the narrowest relevant Gradle target, then assemble the debug app for cross-module changes.
2. Exercise the changed flow on a connected device or emulator using `android-cli` or ADB.
3. Capture screenshots at the important states, not only the happy path. Inspect hierarchy, clipping, contrast, touch reach, map visibility, system bars, and IME interaction.
4. Verify at minimum a compact phone and the physical OnePlus form factor when available. Add larger-window coverage only when the surface is intended to adapt.
5. Add or update Compose UI/screenshot tests when the state can regress. Prefer deterministic fake state over live sensor dependencies.
6. Check font scaling, long text, dark/light schemes, navigation gestures, and TalkBack semantics in proportion to the change.
7. Iterate after inspecting rendered output. Treat a successful build as necessary but insufficient for UI completion.

After significant work, append the outcome, visual decisions, verification, and open questions to `docs/WORKLOG.md`. Record durable product or architecture choices in `docs/DECISIONS.md`.
