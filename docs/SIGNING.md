# Release signing

Android only installs an APK as an update over an installed app if both are signed with the same key.

## What went wrong up to 0.6.0

No `signingConfig` existed, so CI APKs were signed with the auto-generated `debug.keystore` of the GitHub runner.
A fresh runner creates a new key every run. The v0.5.5 APK carries certificate
`CN=Android Debug, O=Android, C=US`, serial 1, valid from 2026-10-21, SHA-256
`bac3895da611bae70c470e1a837077e50bb6db4e9f86bcf267c430634885beb1`.
Its private key existed only on that runner and was discarded, so it cannot be recovered or reused.

## One-time setup (you generate and keep the key)

```bash
keytool -genkeypair -v -keystore bmoe-release.jks -alias bmoe -keyalg RSA -keysize 4096 \
  -validity 36500 -dname "CN=BigMoeOnEdge+, O=<you>, C=<cc>"
base64 -w0 bmoe-release.jks > bmoe-release.jks.b64        # macOS: base64 -i bmoe-release.jks
keytool -list -v -keystore bmoe-release.jks -alias bmoe   # note the SHA-256 fingerprint
```

Back up `bmoe-release.jks` and its passwords somewhere safe (password manager plus offline copy). If the key is
lost, every user has to uninstall again.

## GitHub Actions secrets (Settings > Secrets and variables > Actions)

| Secret | Value |
|---|---|
| `BMOE_KEYSTORE_BASE64` | contents of `bmoe-release.jks.b64` |
| `BMOE_KEYSTORE_PASSWORD` | keystore password |
| `BMOE_KEY_ALIAS` | `bmoe` (the alias you used) |
| `BMOE_KEY_PASSWORD` | key password (optional; defaults to the keystore password) |
| `BMOE_EXPECTED_CERT_SHA256` | optional: SHA-256 fingerprint of the key, pins CI to that exact key |

The workflows fail if the keystore secrets are missing (`-PbmoeRequireStableSigning=true`) and if the APK
carries the old throwaway certificate. Local builds without these variables still use the default debug key.

## Local signed build

```bash
BMOE_KEYSTORE_FILE=/path/bmoe-release.jks BMOE_KEYSTORE_PASSWORD=... BMOE_KEY_ALIAS=bmoe ./gradlew :app:assembleDebug
```

## Verify

```bash
apksigner verify --print-certs app-debug.apk   # SHA-256 must equal your key's fingerprint, every release
```

## No PC? Generate the key on GitHub

Run the "Generate signing key (run once)" workflow from the Actions tab. It refuses to run if the secrets already
exist. Download the `signing-key-DOWNLOAD-AND-DELETE` artifact (expires in 1 day), copy the values from
`SECRETS-README.txt` and the one-line `bmoe-release.jks.b64` into repository secrets, then back both files up.
