# Traccar Client SDK

A Kotlin Multiplatform background location tracking SDK for [Traccar](https://www.traccar.org) - and any other server that accepts the same simple HTTP protocol. Runs on Android and iOS, persists positions in a local SQLite queue, and uploads them with network-aware retry.

This repository publishes two artifacts:

- **Native SDK** - Maven Central (`org.traccar:traccar-client-sdk`) and an XCFramework distributed via Swift Package Manager.
- **Flutter plugin** - pub.dev (`traccar_client_sdk`).

## Install

### Android (Gradle)

```kotlin
dependencies {
    implementation("org.traccar:traccar-client-sdk:1.0.4")
}
```

### iOS (Swift Package Manager)

```swift
.package(url: "https://github.com/traccar/traccar-client-sdk.git", from: "1.0.4")
```

### Flutter

```yaml
dependencies:
  traccar_client_sdk: ^1.0.4
```

## Quick start

### Android (Kotlin)

`sharedTracker(config)` builds (and persists) the tracker; `startTracking` is a suspend extension that requests any missing permissions and then starts the engine.

```kotlin
val config = Config(
    serverUrl = "https://demo.traccar.org",
    deviceId = "123456",
)
val tracker = sharedTracker(config)

// suspend; requests permissions then starts tracking.
// throws IllegalStateException if location permission is denied.
tracker.startTracking(activity)

// later
tracker.stop()
```

`startTracking` requires a `ComponentActivity` because it launches a transparent permission activity if location, notification, or activity-recognition permissions are not yet granted. On a later launch, `sharedTracker()` (no arguments) rebuilds the tracker from the persisted config.

### iOS (Swift)

The tracker is created from the Kotlin/Native framework via `TrackerKt.sharedTracker(config:)`, then started with `start()`.

```swift
import TraccarClientSDK

let config = Config(
    serverUrl: "https://demo.traccar.org",
    deviceId: "123456"
)
let tracker = try await TrackerKt.sharedTracker(config: config)
try await tracker?.start()
```

On a background wake (significant location change or region exit), reconstruct the tracker with `TrackerKt.sharedTracker()` (no arguments) — it reloads the saved config and re-attaches the OS signals. Do this from your app launch path so those background wakes aren't silent.

### Flutter

```dart
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

final tracker = TraccarClientSdk();

// seed the config once (idempotent), then start.
await tracker.init(Config(
  serverUrl: 'https://demo.traccar.org',
  deviceId: '123456',
));
await tracker.start();

// later
await tracker.stop();
```

Use `setConfig` to change settings on an already-initialized tracker.

## Configuration

### `Config`

| Field | Type | Default | Description |
|---|---|---|---|
| `serverUrl` | `String` | - | Traccar server endpoint (`https://demo.traccar.org`). |
| `deviceId` | `String` | - | Device identifier reported to the server. |
| `location` | `LocationConfig` | defaults | Tuning parameters for the location pipeline. |
| `wakeLock` | `Boolean` | `false` | Hold a partial CPU wakelock while tracking (Android only). |
| `buffer` | `Boolean` | `true` | When `true`, persist positions to a local SQLite queue and retry on failure. When `false`, attempt direct upload per position and drop on failure (real-time only). |
| `preferPlatformProviders` | `Boolean` | `false` | Android only. When `true`, use the platform `LocationManager` directly even if Google Play Services is available. Default picks the Fused provider when Play Services is present. Ignored on iOS. |
| `notification` | `NotificationConfig` | defaults | Foreground-service notification text (Android only). |

### `LocationConfig`

| Field | Type | Default | Description |
|---|---|---|---|
| `accuracy` | `Accuracy` | `MEDIUM` | `HIGHEST`, `HIGH`, `MEDIUM`, or `LOW`. See accuracy mapping below. |
| `distanceMeters` | `Int` | `75` | Minimum displacement between accepted positions. |
| `intervalSeconds` | `Int` | `300` | Time-based heartbeat between accepted positions. |
| `angleDegrees` | `Int` | `0` | Heading-change threshold for additional acceptance. `0` disables. |
| `stopDetection` | `Boolean` | `true` | Pause GPS while the user is stationary (motion-aware). |
| `stopTimeoutSeconds` | `Int` | `60` | How long the user must be detected as STILL before location updates pause. |
| `stationaryRadiusMeters` | `Int` | `100` | iOS only - radius of the geofence monitored around the stationary point. |
| `heartbeatIntervalSeconds` | `Int` | `0` | Background heartbeat interval. `0` disables. See the iOS background-task setup below. |

`Accuracy.HIGHEST` is a special mode: it zeroes `distanceMeters` and `intervalSeconds` for the OS request, asking for the maximum-rate stream. Use it for navigation-style scenarios where battery is not a concern. (Set `stopDetection = false` yourself if you also want to keep GPS running while stationary.)

| Accuracy | Android (Fused) | Android (Plain) | iOS |
|---|---|---|---|
| `HIGHEST` | `PRIORITY_HIGH_ACCURACY` | `GPS_PROVIDER` | `kCLLocationAccuracyBestForNavigation` |
| `HIGH` | `PRIORITY_HIGH_ACCURACY` | `GPS_PROVIDER` | `kCLLocationAccuracyBest` |
| `MEDIUM` | `PRIORITY_BALANCED_POWER_ACCURACY` | `NETWORK_PROVIDER` | `kCLLocationAccuracyHundredMeters` |
| `LOW` | `PRIORITY_LOW_POWER` | `PASSIVE_PROVIDER` | `kCLLocationAccuracyKilometer` |

### `NotificationConfig` (Android)

| Field | Type | Default | Description |
|---|---|---|---|
| `text` | `String` | `"Location tracking"` | Body text of the foreground-service notification. |

## API

All native (Kotlin/Swift) calls are suspend/async. `Tracker` is obtained from `sharedTracker(...)`; the Flutter plugin wraps a single `TraccarClientSdk` instance.

| Method | Kotlin (Android) | Swift (iOS) | Dart (Flutter) | Notes |
|---|---|---|---|---|
| Create tracker | `sharedTracker(config): Tracker` | `TrackerKt.sharedTracker(config:)` | `tracker.init(config)` | Persists the config. `sharedTracker()` with no argument rebuilds from the saved config. Flutter `init` is idempotent. |
| Start tracking | `tracker.startTracking(activity)` | `tracker.start()` | `tracker.start()` | Android `startTracking` requests permissions and throws `IllegalStateException` if location is denied; Flutter `start` throws a `PlatformException` on denial. |
| Stop tracking | `tracker.stop()` | `tracker.stop()` | `tracker.stop()` | Stops the engine. The persisted config is retained. |
| Update config | `tracker.updateConfig(config): Tracker` | `tracker.updateConfig(newConfig:)` | `tracker.setConfig(config)` | Rebuilds the engine with the new config. Kotlin/Swift return a new `Tracker`. |
| Query state | `tracker.state` (`StateFlow<State>`, `.enabled`) | `tracker.state` | `tracker.isTracking(): Boolean` | Native exposes a `State` flow whose `enabled` field is the tracking flag; Flutter exposes a boolean getter. |
| One-off fix and upload | `tracker.requestPosition(context, alarm)` | `tracker.requestPosition(alarm:)` | `tracker.requestPosition(alarm:)` | Independent of `start` / `stop`. Returns whether the upload succeeded. Optional `alarm` tags the upload with the Traccar `alarm` field. The one-off path does not buffer. |
| Read diagnostic log | `tracker.getLogs(): List<LogEntry>` | `tracker.getLogs()` | `tracker.getLogs()` | Returns recent entries with `time` (epoch ms) and `message`. |
| Clear diagnostic log | `tracker.clearLogs()` | `tracker.clearLogs()` | `tracker.clearLogs()` | |

## How it works

The pipeline is the same on both platforms:

```
LocationSource → LocationFilter → TrackerEngine → PositionQueue → HttpUploader → server
```

- **LocationSource** - wraps the platform location API. On Android, `FusedLocationSource` is preferred when Google Play Services is available, otherwise `AndroidLocationSource` (plain `LocationManager`); set `Config.preferPlatformProviders = true` to force the latter. On iOS, `IosLocationSource` wraps `CLLocationManager`. Each source also subscribes to activity recognition so the engine can pause GPS while the user is stationary.
- **LocationFilter** - application-level OR filter: a position is accepted if it satisfies any of the time, distance, or angle thresholds.
- **TrackerEngine** - collects accepted positions and, depending on `Config.buffer`, either enqueues them for retry-on-failure upload or attempts a direct upload per position. Sync loop uses exponential backoff (5s → 5min) on upload failure and waits on `NetworkMonitor` when offline.
- **PositionQueue / DatabaseQueue** - SQLite-backed FIFO queue (via SQLDelight). Survives app and OS restarts.
- **HttpUploader** - Ktor client; sends each position as an HTTP POST with form parameters (`id`, `timestamp` in seconds, and, when present, `lat`, `lon`, `accuracy`, `altitude`, `speed` in knots, `bearing`, `batt`, `charge`, `alarm`). This is the OsmAnd-style protocol Traccar consumes; any server that accepts the same params can be the endpoint. Returns success on any 2xx.
- **NetworkMonitor** - platform-specific connectivity observer used by the sync loop to wait for the network before retrying.

### Filters and OS request shape (Android)

`LocationFilter` is OR (any trigger accepts). The OS request is single-criterion to avoid the AND deadlock that produces silent "stationary forever" behavior: if `distanceMeters > 0`, the OS is asked to deliver on distance only; otherwise it delivers on time only. `Accuracy.HIGHEST` zeroes both and requests the max rate.

### Stop detection

Both platforms use the OS's activity recognition to pause GPS when the user is sitting still:

- **Android** - `ActivityRecognitionClient.requestActivityTransitionUpdates` (transitions) **and** a one-shot `requestActivityUpdates` snapshot at start so that already-stationary devices are correctly classified rather than waiting for a transition that never fires.
- **iOS** - `CMMotionActivityManager.startActivityUpdates` (live updates) **and** a `queryActivityStarting` historical query at start (24h window) for the same already-stationary case. When confirmed stationary, the SDK starts monitoring a `CLCircularRegion` around the device so iOS can wake the app on exit.

### Fast first fix

To avoid a silent initial period when the configured interval is large, both Android providers issue a one-shot `getCurrentLocation` alongside the periodic stream. iOS does not need this - `startUpdatingLocation` delivers within seconds.

### Persistence and recovery

- **`ConfigStore`** - persists the active config so background-launched services know what to do.
- **`PositionQueue`** - persists positions across restarts.
- **`LogStore`** - persists the diagnostic log retrievable via `getLogs`.

Both platforms hold a small, self-contained SQLite database (`tracker.db`).

## Reliability

### Android

- **Foreground service** (`TrackerService`) with `FOREGROUND_SERVICE_TYPE_LOCATION` keeps the process alive and visible to the user.
- **`START_REDELIVER_INTENT`** restarts the service with the original intent if the OS kills it (e.g., memory pressure).
- **`BootReceiver`** restarts the service after `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

Realistic limits: aggressive OEM task killers (Xiaomi/Huawei) can ignore Android's contract; the user can force-stop the app from settings; the *restricted* App Standby Bucket disables most background work. These are platform limitations, not bugs.

### iOS

- **Significant Location Changes** (`startMonitoringSignificantLocationChanges`) - the key API that wakes the app from a terminated state on roughly ~500m shifts.
- **Region monitoring** - when the SDK detects the user is stationary it registers a `CLCircularRegion` around the spot so iOS wakes the app on exit.
- **Reconstruct on launch** - rebuild the tracker with `TrackerKt.sharedTracker()` from your app launch path (`application(_:didFinishLaunchingWithOptions:)` or equivalent) so SLC / region wakes reload the persisted config and re-attach the OS signals.

Realistic limits: user-initiated force-quit from the App Switcher disables SLC until the user reopens the app; phone reboot requires the user to open the app once before tracking resumes (iOS has no `BootReceiver` equivalent); Low Power Mode can reduce wake frequency.

## Permissions

### Android (in your `AndroidManifest.xml`)

The SDK manifest already declares:

- `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`
- `ACTIVITY_RECOGNITION` (Android 10+)
- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION`
- `POST_NOTIFICATIONS` (Android 13+)
- `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK`, `INTERNET`, `ACCESS_NETWORK_STATE`

Runtime permission prompts (location, notifications, activity recognition, background location) are launched automatically by `startTracking`. On first start, the SDK also opens the *Ignore Battery Optimization* settings screen once.

### iOS (in your `Info.plist`)

Add both:

- `NSLocationAlwaysAndWhenInUseUsageDescription`
- `NSLocationWhenInUseUsageDescription`
- `NSMotionUsageDescription` (for activity-based stop detection)

And enable the **Location updates** background mode in your target capabilities.

If you use `heartbeatIntervalSeconds`, also add `fetch` to `UIBackgroundModes` and register the background task identifier:

```xml
<key>BGTaskSchedulerPermittedIdentifiers</key>
<array>
    <string>org.traccar.client.heartbeat</string>
</array>
```

Note: iOS schedules `BGAppRefreshTask` at its discretion — the interval is a "no sooner than" hint, not a guarantee.

## Diagnostic log

`getLogs()` returns the SDK's internal log entries, oldest first. Each `LogEntry` carries `time` (epoch ms) and `message`. Useful for surfacing tracker state in a debug screen. `clearLogs()` empties the store.

## License

Apache License 2.0. See [LICENSE](LICENSE).
