# @traccar/react-native-client-sdk

React Native module for background location tracking. Wraps the
[Traccar Client SDK](https://github.com/traccar/traccar-client-sdk) for Android
and iOS.

## Installation

```sh
npm install @traccar/react-native-client-sdk
cd ios && pod install
```

The Android side pulls `org.traccar:traccar-client-sdk` from Maven Central
automatically via autolinking.

The SDK is built with a newer Kotlin than React Native's Gradle toolchain
currently pins, and it drags a newer `kotlin-stdlib` into the whole build.
Until React Native catches up, the host app must relax the metadata check
build-wide. Add to the app's root `android/build.gradle`:

```gradle
allprojects {
    tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile).configureEach {
        compilerOptions {
            freeCompilerArgs.add("-Xskip-metadata-version-check")
        }
    }
}
```

The module itself already compiles with this flag; the block above covers the
app's own Kotlin sources and any other Kotlin libraries in the build.

On iOS the module links against the Kotlin/Native `TraccarClientSDK`
XCFramework. Build it from the core module and place it at
`ios/TraccarClientSDK.xcframework` before running `pod install`:

```sh
./gradlew :core:assembleTraccarClientSDKReleaseXCFramework
cp -R core/build/XCFrameworks/release/TraccarClientSDK.xcframework \
  react-native/ios/TraccarClientSDK.xcframework
```

## Usage

```ts
import * as Traccar from '@traccar/react-native-client-sdk';

await Traccar.init({
  serverUrl: 'https://demo.traccar.org',
  deviceId: '123456',
  location: { accuracy: 'HIGH', distanceMeters: 50 },
});

await Traccar.start();

const tracking = await Traccar.isTracking();

const uploaded = await Traccar.requestPosition('sos');

await Traccar.stop();
```

## API

| Function | Description |
| --- | --- |
| `init(config)` | Initialize the tracker (idempotent). |
| `setConfig(config)` | Replace the running tracker's configuration. |
| `start()` | Start background tracking. |
| `stop()` | Stop tracking. |
| `requestPosition(alarm?)` | Upload a single fix; resolves `true` on success. |
| `isTracking()` | Whether tracking is active. |
| `getLogs()` | Recent diagnostic entries, oldest first. |
| `clearLogs()` | Clear stored diagnostics. |

State is polled (`isTracking`, `getLogs`) — the SDK does not push events to JS.
