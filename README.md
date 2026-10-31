# SANRO POS Tunas Baru — Android TESTING Build

**PUBLIC BUILD WORKSPACE — NOT THE APPLICATION SOURCE REPOSITORY**

Repository khusus untuk membangun dan menguji APK Android **SANRO POS Tunas Baru TESTING** setelah source Android yang akan dipublikasikan diaudit dan disetujui Owner.

## Boundary

- Private application source: `arioguswara-oss/Sanro-Pos-TunasBaru` (must remain PRIVATE).
- This repository must **not** include the private frontend/backend application, database, SSH keys, Android signing keys, credentials, backups, or production configuration.
- Not related to SANRO POS Public and must not deploy to any production system.
- Never deploy or install APK automatically.
- Test Cash Drawer must remain disabled pending electrical compatibility verification and explicit owner approval.
- Bluetooth print, test-print, and auto-cutter baselines must be preserved.

## State

Repository initialized. Android project and build workflow **not yet published**. No APK has been built here.

## APK safety

A debug APK may have a different signing certificate from the existing TESTING app. Verify package ID, signature fingerprint, and version before attempting an update. Never uninstall an existing app just to bypass a signing mismatch.
