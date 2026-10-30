# Verification — Android 0.4.0 / OpenCode 2.1.4 beta

Date: 2026-10-27. Experimental Android port; this is not an upstream Android release.

## Passed

- Clean Android APK compilation and three host JVM tests against the actual EnginePayload installer: successful verified replacement, checksum-failure preservation of the previous executable, and truncated-payload preservation. JUnit reports 3 tests, 0 failures/errors.
- GUI production build with Bun 1.4.4 from upstream 4b83402a293c3d018216bbf75815175e32f3a16c plus the included Android patch. Changed-file lint passes with zero problems across six files; GUI extension typecheck reports no errors in 1,166 files.
- The patch applies cleanly to the hash-pinned source archive and reproduces all six modified source files byte for byte.
- Eight Python packaging/recovery tests passed. Recovery-launcher tests use a mock executable; they do not execute Android code.
- Six actual Linux/Bun source-backend checks passed: authenticated info, unauthorized rejection, a real temporary-project session, empty active context, executing a shell command that writes a project file, reading the shell interaction in context, and active-session reporting. The six recorded groups combine info/auth into one check. No LLM or paid model requests were sent. This does not execute the Android ELF.
- Final APK package com.termux, Android version 0.4.0, code 10004 and the graphical activity launcher were inspected with aapt.
- APK signing and alignment verified. Signer SHA-256 is e2b4233a21dc155dcd31fca3c1197ca64561444a617229088ad71912dd5b06eb, matching the previous APK. ZIP integrity passed.
- Every one of the 2,864 UI assets inside the APK matches its manifest hash. The final APK contains exactly those UI files and the manifest. Unreferenced files left from the previous UI bundle were removed during final packaging, then the APK was aligned and signed again with the existing key.
- Engine payload decompression yields exactly 178,129,160 bytes with SHA-256 066eedd9e883d243d4da66d2495d68f7329185df64883acbe3e3389437e6c2de. It is the same pinned Android 2.1.4 engine as the previous release.
- Final DEX contains the new native metadata/recovery controls. Final JavaScript contains the new mobile side-question labels. These are packaging checks, not interaction tests.

APK-VERIFICATION.json records the final APK size, checksum and inspection results.

## Not verified

- Physical Android installation/update, screen-off execution, Doze, Realme battery policies, memory pressure, thermal behavior and long programming jobs. No phone or emulator was connected.
- The mobile /btw browser keeper test was added to the existing upstream suite. An attempted Chromium run was blocked before application interaction by the environment's denied Unix socket operation. It is not counted as a passing test. Mobile layout, copy, cancellation, answer restoration and command discovery still require runtime verification.
- Actual paid/provider-backed /btw generation. The implementation reuses the upstream generation flow; no model credentials were used to exercise it here.
- Complete TUI/desktop command parity, OAuth, file attachment flows, MCP integrations, V1 migration and restore on a physical device. A full upstream monorepo check is not claimed.

## Practical limits

The engine still requires an ARM64 4 KB-page device. ZIP alignment does not add 16 KB engine support. Android force-stop, low memory and vendor/network restrictions can still stop work. A wake lock consumes battery and cannot override all policies.

Health and active-session counts do not prove tool progress or identify every waiting state. No automatic prompt replay, task restart, model fallback or competing engine is added. Explicit UI reload or renderer termination can interrupt a pending side question; the question remains retryable rather than being silently resubmitted.

Updating from 0.3.0 does not change the pinned engine or database format. Earlier-version migration keeps its previous backup flow, but device migration/rollback is not certified here. Keep an independent copy of important projects and install over the existing app without uninstalling.
