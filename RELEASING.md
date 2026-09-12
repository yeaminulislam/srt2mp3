# Releasing BalaSpeak

এই repo-তে APK বিল্ড ও release পুরোটাই GitHub Actions দিয়ে হয় — লোকাল machine-এ Android Studio ছাড়াও কাজ হবে।
All APK builds and releases are automated through GitHub Actions.

## Pipeline (`.github/workflows/android-release.yml`)

| Trigger | What happens |
| --- | --- |
| Push to `main` / PR to `main` | Build only — APK uploaded as a workflow **artifact** |
| Push a tag like `v1.1` | Build + **GitHub Release** created with the signed APK attached |
| Manual run (Actions → Run workflow) with `create_release` | Build + create/update the release |
| Push to `arena/**` branches | Build + create/update the release (used by the Arena agent session) |

## নতুন version release করার নিয়ম

1. `app/build.gradle.kts`-এ `versionCode` ও `versionName` বাড়িয়ে দিন
2. `main`-এ merge করুন
3. Tag push করুন:

   ```bash
   git tag v1.1
   git push origin v1.1
   ```

4. Actions বিল্ড শেষ হলে **Releases** পেজে `BalaSpeak-<version>-release.apk` যুক্ত হয়ে যাবে

## Signing

- Release APK `my-upload-key.jks` (repo root-এ, PKCS#12 format) দিয়ে সাইন হয়
- Key alias: `upload`
- Keystore/key password: workflow ফাইলে (`STORE_PASSWORD` / `KEY_PASSWORD`) দেওয়া আছে
- ⚠️ এই repo **private** থাকতে হবে — repo public করলে আগে keystore সরিয়ে secret (base64) হিসেবে রাখুন এবং keystore rotate করুন
- **Keystore ফাইলটি হারাবেন না** — এটি ছাড়া ভবিষ্যতের আপডেট আগের অ্যাপের উপরে ইনস্টল হবে না (নতুন signature-এ install করতে হলে আগে পুরনো অ্যাপ uninstall করতে হবে)

## Toolchain

- Gradle **9.3.1** (wrapper) — AGP 9.1.1-এর minimum requirement
- JDK 17, Android SDK Build-Tools 36.0.0, compileSdk 36.1
