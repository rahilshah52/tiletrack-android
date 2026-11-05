# TileTrack Android app

The TileTrack website (https://stock.uniglobelifestyles.com) opened full screen as an app (Trusted Web Activity),
plus native alerts through Firebase: a 10-second ring for new orders and order alerts, also when the app is closed.

- Package `com.uniglobe.tilestock`, signed with the existing TileTrack key, so it installs as an update.
- Built by GitHub Actions (`.github/workflows/build.yml`) on every push to `main`; the APK is under the run's Artifacts.
- Secrets needed: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_PASSWORD`.
- `app/google-services.json` comes from the Firebase project (not secret).
- The server sends the alerts with the Firebase service-account key, which lives only on the server (`backend/firebase-key.json`).
