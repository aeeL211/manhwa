# AGENTS.md

## Project
Android WebView app, 100% Kotlin, UI still XML. It loads a website and
modifies web responses (request interception, JS/CSS injection, URL
handling, header/content rewriting). Solo developer. Goal: modern,
well-organized, easy to maintain.

## Core principle
The app and all its functions must keep working exactly as before.
You may refactor, move, rename, and restructure anything, including the
WebView logic, build config, and workflows, as long as behavior stays
identical and you can show it.

## Hard constraints (breaking these breaks user updates)
- Never change applicationId or the signing config values/secrets names.
- versionCode/versionName: do not change unless asked; never decrease.
- Keep manifest-declared components working (same exported behavior,
  permissions, intent filters) unless the task says otherwise.
- Never rename/remove a @JavascriptInterface method name or anything
  called from injected JS or by reflection/serialization.

## WebView/response-modifying logic
Refactoring is allowed, but:
- Preserve the exact logic: conditions, order of operations, regexes,
  URLs, headers, injected JS/CSS content, return values.
- Any change in this area must be listed separately in the PR description
  with a before/after explanation of why behavior is unchanged.

## Dependencies
- Upgrading is allowed only when the task asks for it. One concern per PR.
- Keep R8/ProGuard rules working: if you move/rename classes, update
  rules so nothing used via reflection/JS interface gets stripped.

## Workflows (.github/workflows)
- May be reorganized/cleaned (reusable workflows, caching, naming).
- The outputs must stay equivalent: same triggers for release, same
  artifact names/signing, same release behavior.
- You cannot run workflows. Add `workflow_dispatch` to new/changed
  workflows and describe in the PR how the developer should test it.

## Verification before finishing
./gradlew assembleDebug, ./gradlew lint, ./gradlew test (if present),
and assembleRelease if possible. If something fails and you can't fix it
within scope, revert and explain.

## PR description must include
What changed and why, files touched, any change in WebView/response logic
(or "none"), verification results, and manual test steps for the developer.

## Conventions
Kotlin, Jetpack Compose for UI, ViewModel + StateFlow, version catalog,
Material 3. One concern per PR, small diffs.
