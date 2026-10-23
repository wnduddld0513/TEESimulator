# ROM integration (branch `seventeen`)

Fork of [JingMatrix/TEESimulator](https://github.com/JingMatrix/TEESimulator), licensed GPL-3.0 (see
`LICENSE`). TEESimulator is a root module that intercepts the keystore from a native injector. This
directory is the same idea inside the ROM: a service in `system_server` signs the attestation, so
there is no root, no module and no injector. The framework files keep their Apache-2.0 headers.

| Path | What it is |
| --- | --- |
| `rom/framework/**/KeyboxAttestationService.java` | the keybox vault and the signing service (`keybox_attestation`) in `system_server`. The keybox lives in `/data/system/keybox/` and is readable only there; apps get signatures through the binder, never the key |
| `rom/framework/**/IKeyboxManager.aidl`, `KeyboxSignResult.aidl` | the binder interface |
| `rom/framework/**/KeyboxImitationHooks.java`, `KeyProviderManager.java` | the keystore hook of an app process; it asks the service to sign for the target apps |
| `rom/framework/**/KeyboxChainGenerator.java` | builds the certificate chain; the root of trust reports the real `ro.boot.vbmeta.digest` as boot hash and a boot key derived from it |
| `rom/patches/SystemServer.patch`, `rom/patches/sepolicy.patch` | starts the service, and its SELinux type and context |
| `rom/app/**` | the TEESimulator settings page (keybox, target apps, security patch, revocation check) of the Play Integrity Fix app of the ROM; `KeyboxStore.java` is shared with the Specter page |

Settings key: `Settings.Global lenovo_tee_simulator` (on unless 0). No keybox is shipped; it comes from
the user or from the online catalog of the Specter page.

The files are from the ROM sources: `frameworks/base` and `device/lenovo/lapis` of
[lenovo-sm8650](https://github.com/lenovo-sm8650), branch `seventeen`.
