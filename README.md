# GeoКоваль — Android Java + Firebase

Java Android project (XML views) for Android Studio and VS Code. It uses OpenStreetMap/osmdroid, foreground GPS, Firebase Anonymous Authentication, Firestore realtime member/location documents, and a callable Cloud Function to create or join password-protected rooms.

## Run immediately in Android Studio (demo mode)

1. Install Android Studio with Android SDK Platform 34 and a JDK 17 (Android Studio can use its bundled JDK).
2. Open the repository folder in Android Studio and wait for Gradle sync. The project uses Android Gradle Plugin 8.5.2, which requires Gradle 8.7 and JDK 17.
3. Create/start an emulator (or connect an Android phone with USB debugging) and run the `app` configuration.
4. Without `app/google-services.json`, the app runs in local demo mode: the map and foreground GPS work, but rooms are not shared across phones. Internet is needed for OpenStreetMap tiles.

## Enable shared Firebase rooms

1. Create a Firebase project and register an Android app with package name `com.koval.app`.
2. Download `google-services.json` and place it at `app/google-services.json` (do not commit credentials/config if you prefer to keep the Firebase project private).
3. Enable **Authentication → Sign-in method → Anonymous** and create a Cloud Firestore database.
4. Install Node.js 20 and Firebase CLI. From the repository root run `firebase login`, `firebase use --add`, then `firebase deploy --only functions,firestore:rules`.
5. Sync Gradle again and run the app. Both phones must install the same Firebase-configured build and use the exact same room name/password.

The callable function `createOrJoinRoom` is the trusted authority for room creation and password checks. It stores a salted scrypt hash, not the room password. Firestore rules restrict reads to room members and allow each authenticated user to write only their own location. Anonymous accounts are device/app identities, not verified people; enable App Check and add account recovery/rate-limiting before production use.

## Features and boundaries

- Validates input and creates or joins a room using its name/password.
- Realtime room member/location listeners and map markers for members sharing their location.
- Foreground GPS updates only while the map screen is open; no background tracking permission is requested.
- Users may disable sharing (their location is removed) or leave a room.
- Radius setting is saved, but proximity notifications and background tracking are not implemented yet.

For shared rooms, Firebase setup/deployment is required. The demo mode is only local on one device and is not an authentication/security substitute.
