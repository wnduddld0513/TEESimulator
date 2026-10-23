# TEESimulator — ROM port for PixelOS 17.0 (branch `seventeen`)

This branch is a fork of [JingMatrix/TEESimulator](https://github.com/JingMatrix/TEESimulator) with the
idea ported to **PixelOS 17.0** (Android 17) as a service inside the ROM, **without root, KernelSU, a
module or a native injector**. The module itself is kept as upstream; the port is in [`rom/`](rom),
described in [`rom/NOTICE.md`](rom/NOTICE.md).

## What the port does

- `KeyboxAttestationService` (service `keybox_attestation`) runs in `system_server`. It holds the keybox in
  `/data/system/keybox/` and signs attestation requests of the target apps. Apps receive signatures
  through the binder (`IKeyboxManager`), never the private key.
- The keystore hook `KeyboxImitationHooks` in the app process asks the service to sign when a target
  app generates an attested key or reads a key entry.
- The root of trust of the generated chain reports the real `ro.boot.vbmeta.digest` as boot hash and a
  boot key derived from it.
- *Settings > Advanced features > Experimental features > Integrity > TEESimulator* manages the
  keybox, the target apps and the security patch; the main switch is
  `Settings.Global lenovo_tee_simulator` (on unless 0). No keybox is shipped with the ROM.

## Requirements on the ROM

| Where | What |
| --- | --- |
| `frameworks/base` ([`seventeen`](https://github.com/lenovo-sm8650/android_frameworks_base/tree/seventeen)) | the files under [`rom/framework`](rom/framework), and [`rom/patches/SystemServer.patch`](rom/patches/SystemServer.patch) to start the service after `AttestationService`. Call `KeyboxImitationHooks` from `KeyStoreSecurityLevel.generateKey` and `KeyStore2.getKeyEntry` |
| `device/lenovo/lapis` ([`seventeen`](https://github.com/lenovo-sm8650/android_device_lenovo_lapis/tree/seventeen)) | [`rom/patches/sepolicy.patch`](rom/patches/sepolicy.patch): the `keybox_attestation_service` type (`app_api_service`, `system_server_service`) and its `service_contexts` entry. `/data/system/keybox/` stays `system_data_file`, which only `system_server` can access |
| `packages/apps/PlayIntegrityFix` ([`seventeen`](https://github.com/wnduddld0513/PlayIntegrityFix/tree/seventeen)) | the settings page; `PRODUCT_PACKAGES += PlayIntegrityFixSettings` |

Build the ROM as usual; the AIDL files under `rom/framework/core/java/android/security/keybox` are compiled
with the framework. The sources under [`rom/app`](rom/app) are the page in the Play Integrity Fix app and
are built there (see its README).


## License

GPL-3.0, like upstream (see [`LICENSE`](LICENSE)). The framework files keep their Apache-2.0 headers.
