# SANRO POS Tunas Baru — Android TESTING Build

**Public Android build workspace only. Not the SANRO POS application repository.**

This repository contains **only** the reviewed Android TESTING project required to build a non-production APK. The original application repository `arioguswara-oss/Sanro-Pos-TunasBaru` remains **PRIVATE**.

## Provenance and limits

- Source snapshot: Android project from private repository branch `feature/test-cash-drawer-20261031`.
- Only Android build files, Java sources, required app icons/resources, and Cash Drawer source guard are included.
- No backend, POS frontend, database, backups, release signing key, or SSH credentials.
- The original Android TESTING debug variant keeps package `com.sanrotech.pos.testing`. Public CI builds only a separately installable `drawerLab` variant with package `com.sanrotech.pos.testing.drawerlab`, launcher label **SANRO Drawer Lab**, and URL `https://test-pos.sanrotech.com/`.
- Cash Drawer testing is **disabled** in source by `CASH_DRAWER_KICK_ENABLED=false` pending verified electrical compatibility and owner authorization.
- Auto Cash Drawer is **not enabled**. Do not use this build for live sales.

## Build

GitHub Actions > **Build Android TESTING APK**; triggered by source changes to `main` or manually. The workflow runs source guards and Android debug unit tests, then assembles and inspects the debug APK.

The artifact contains **app-drawerLab.apk** and signing/identity inspection results. Workflow does **not** deploy, sign with a release key, or install an app.

## Critical installer warning

Drawer Lab uses a separate application ID, data, and Bluetooth pairing. Install beside the working TESTING APK, not over it. **Never uninstall the existing TESTING app**. Cash Drawer pulses remain disabled. An artifact showing SUCCESS is not evidence that cash drawer hardware works.

## Scope isolation

Nothing in this repository may deploy to LIVE (`pos.sanrotech.com`) or interact with SANRO POS Public (`pos2.sanrotech.com`). Owner Rio must explicitly authorize any device test and any deployment separately.
