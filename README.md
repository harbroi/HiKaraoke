# HIKaraoke

HIKaraoke is an Android karaoke queue app with **two experiences in one project**: a **mobile app** for searching and managing songs, and a **TV app** for playing the shared queue on a big screen.

The app uses **Google Sign-In**, **Firebase Authentication**, and **Firebase Realtime Database** to keep each user's karaoke queue available across devices.

## Features

### Mobile version

- **Google sign-in** for account access
- **YouTube karaoke search** directly inside the app
- **Embeddable-video filtering** to avoid non-playable results
- **Song queue management** with add-to-queue flow
- **Live queue view** backed by Firebase Realtime Database
- **Remove individual songs** with long press
- **Clear the full queue**
- **TV access code** shown in the account screen for linking a TV
- **Bottom navigation** for queue, search, and account screens
- **Firebase offline persistence** for better resilience

### TV version

- **Automatic TV mode detection** on Android TV / Leanback devices
- **Manual TV mode entry** from the mode chooser on non-TV devices
- **8-character access code linking** to open a user's queue on TV
- **Full-screen YouTube playback**
- **Live queue syncing** while songs are added from mobile
- **Auto-play next song** when the current video ends
- **Skip blocked embedded videos** automatically
- **Playback controls** for previous, pause/play, and next
- **Remote/media key support** for TV navigation
- **On-screen overlay** with current title, next song, queue position, and playback timer

## How it works

1. On **mobile**, the user signs in with Google.
2. The user searches for karaoke videos and adds songs to a personal queue.
3. HIKaraoke generates or loads an **8-character TV code** for that account.
4. On **TV**, the user enters the code to connect to the same Firebase-backed queue.
5. The TV player keeps listening for queue updates and plays songs in order.

## Tech stack

- **Android (Java)**
- **Firebase Authentication**
- **Firebase Realtime Database**
- **Google Sign-In**
- **YouTube Android Player library**
- **Picasso** for thumbnails
- **Gradle** build system

## Project structure

```text
app/src/main/java/net/harbroi/hikaraoke/     # Mobile app code
app/src/main/java/net/harbroi/hikaraoketv/   # TV app code
app/src/main/res/                            # Shared app resources
.github/workflows/build-apk.yml              # GitHub Actions APK build
```

## Build

### Local

```bash
./gradlew assembleDebug
```

On Windows:

```powershell
.\gradlew.bat assembleDebug
```

### GitHub Actions

This repository includes a workflow at **`.github/workflows/build-apk.yml`** that builds the debug APK and uploads it as an artifact after pushes and pull requests.

## Notes

- The project supports both **phone/tablet** and **Android TV** usage.
- The TV flow depends on the mobile user's Firebase queue and access code.
