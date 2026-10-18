# Rubber Duck Assistant 🦆

A Clippy-style pixel-art buddy that floats over your Android apps. Tap it, or say "hey ducky", and just talk. It answers out loud in real time, can look at your screen, and remembers your day.

Work in progress. It's a personal project, built for and tried on one Pixel.

## Features

- **Floating, animated buddies:**
  - five characters: Sudo the rubber duck, Duckling, Cat, Frog and Bunny;
  - a candy-colour ring;
  - lip-sync, plus idle, listening, thinking, happy and sleepy animations.
- **Live voice conversation** with Gemini Live:
  - audio streams both ways;
  - you can talk over it to interrupt;
  - the mic is echo-cancelled;
  - the connection starts as soon as your finger touches the buddy.
- **Screen reading:** on a tap it takes a screenshot through an Accessibility service and sends it with that question only. The screenshot is never stored.
- **On-device memory:**
  - today's conversation, with a rolling summary;
  - a diary of past days;
  - facts you can edit;
  - a forget-everything button.
- **Offline wake word**, "hey ducky", using Vosk.
- **Latency readout** on the setup screen: tap → mic, end of speech → reply.
- **Graceful fallbacks:** if Live is unavailable it uses Android speech recognition plus text chat. When the free quota runs out, it goes into a sleepy "tired" mode instead of showing errors.

## How it works

- **App:**
  - Kotlin, with Jetpack Compose for the setup screen;
  - classic views for the overlay;
  - a foreground service with the microphone type;
  - OkHttp for the Live WebSocket.
- **AI:** every call goes through a separate gateway, a Cloudflare Worker. The gateway holds the real API key and gives this app its own capped key.
  - The gateway URL is in `app/build.gradle.kts`.
  - The app key is injected at build time from the `ROXIE_GATEWAY_KEY` environment variable and is never committed.
- **Art:** drawn in code with Python/PIL in `art/`. Run `python3 art/buddies.py` to re-export the sprites into `app/src/main/assets/buddies/`.

## Building

- **CI:** `.github/workflows/build.yml` runs on manual dispatch. It needs these repo secrets:
  - `ROXIE_KEYSTORE_B64`
  - `ROXIE_KEYSTORE_PASSWORD`
  - `ROXIE_GATEWAY_KEY`
  - `ROXIE_ARTIFACT_PASSWORD`

  The uploaded APK is AES-encrypted, because it contains the app's gateway key.

- **Locally:** set `ROXIE_GATEWAY_KEY`, `ROXIE_KEYSTORE` and `ROXIE_KEYSTORE_PASSWORD`, then run `gradle :app:assembleRelease`. You need JDK 17 and Android SDK 37.
- **Device support:** minSdk is 26. Screen reading needs Android 11 or newer.
