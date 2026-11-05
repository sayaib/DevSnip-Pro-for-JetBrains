# DevSnip Pro for JetBrains 1.2.1

Release date: 2026-11-04.

## Changes

- Require `com.intellij.modules.jcef` so compatible Android Studio installations install Web Browser (JCEF) alongside DevSnip Pro.
- Require IDE build 253.29346.138 (2025.3.1) or newer and a compatible JCEF module.
- Replace the reduced native tool window with recovery instructions and an Open Plugins Settings button when JCEF is unavailable.
- Align the development and test IDE with the new minimum supported version.

## Validation

- `test buildPlugin` on IDE 2025.3.1 with Java 21: passed; all 153 tests passed.
- Plugin Verifier: compatible with IU-253.29346.138, IU-262.10968.63 and IU-263.7307.44.
- Updated the browser termination callback and modified-state notification for the supported API baseline.
- TLS tests isolate the JVM default SSL context and restore it afterwards, avoiding the headless IDE's automatic certificate acceptance.
- Live Android Studio dependency installation and restart: not yet tested.

## Deployment

Expected artifact: `build/distributions/devsnip-pro-jetbrains-1.2.1.zip`.
Automated release checks passed. Signing and Marketplace publication have not been performed.
