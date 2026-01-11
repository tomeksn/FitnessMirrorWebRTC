# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build Commands

### Development Commands
```bash
# Build the project
./gradlew build

# Run debug build on connected device/emulator
./gradlew installDebug

# Clean build
./gradlew clean

# Run tests
./gradlew test

# Run Android instrumented tests
./gradlew connectedAndroidTest
```

### Common Development Tasks
```bash
# Check for compilation errors
./gradlew compileDebugKotlin

# Generate APK for testing
./gradlew assembleDebug

# Check dependencies
./gradlew dependencies
```

## Architecture Overview

This is **FitnessMirror Native** - a native Android app that combines YouTube workout playback with camera streaming to Smart TVs. The app migrates from an Expo-based solution to native Android with integrated camera streaming capabilities from a proven CastApp solution.

### Key Architecture Components

**Main Activity Pattern**: Single-activity architecture using Jetpack Compose with state management via `mutableStateOf`. MainActivity implements both `CameraManager.CameraCallback` and `StreamingServer.StreamingCallback` interfaces.

**Dual Camera Usage**: Uses single CameraX instance with dual output - Preview use case for local PIP display and ImageAnalysis use case for TV streaming. Both share the same camera resource efficiently.

**Core Integration**: Integrates three main systems:
- **CameraManager**: Handles camera preview and frame capture (320x240 @ 10fps JPEG)
- **StreamingServer**: WebSocket server (port 8080) with multiple TV compatibility endpoints
- **NetworkUtils**: Local IP detection and network interface management

**TV Streaming Architecture**: Hybrid web client approach where TV displays both YouTube video (70% screen) and live camera stream (30% corner overlay) simultaneously via WebSocket binary JPEG frames.

### Package Structure
```
com.fitnessmirror.app/
├── ui/screens/          # Compose screens (Home, Workout)
├── ui/components/       # Reusable UI components (DraggablePIP, etc.)
├── ui/theme/           # Material3 theme configuration
├── camera/             # CameraManager (from CastApp integration)
├── streaming/          # StreamingServer with WebSocket support
├── network/            # NetworkUtils for IP detection
└── MainActivity.kt     # Main activity with state management
```

### TV Compatibility Strategy
Multiple endpoints for different TV browser capabilities:
- `/` - Main client (YouTube + Camera with WebSocket)
- `/test` - Connection diagnostics
- `/fallback` - SSE fallback for limited browsers
- `/debug` - Troubleshooting information

### Performance Targets
- Camera streaming: <150ms latency to TV
- Resolution: 320x240 JPEG at 45% quality for bandwidth optimization
- Memory usage: <100MB during operation
- Supports Android 7.0+ (API 24+)

### State Management
Uses Compose state with these key variables in MainActivity:
- `isStreaming`: WebSocket server and camera streaming status
- `hasConnectedClient`: TV client connection status
- `serverAddress`: Local IP and port for TV connection
- `currentScreen`: Navigation state (Home/Workout)
- `hasCameraPermission`: Camera permission status

### Development Environment
- **Primary**: WSL2 for source development
- **Build/Test**: Windows Android Studio for compilation and device testing
- **Sync**: Git workflow between environments
- **Target**: Native Android development with Kotlin + Jetpack Compose

### Important Technical Details
- **Network Security**: Uses `usesCleartextTraffic="true"` for local HTTP streaming
- **Permissions**: Requires CAMERA, INTERNET, ACCESS_NETWORK_STATE, ACCESS_WIFI_STATE, WAKE_LOCK
- **Camera Features**: Requires back camera, front camera optional
- **Screen Orientation**: Supports both portrait and landscape modes with configuration changes
- Zawsze rób remote commit, abym mógł testować zmiany na innym komputerze z Android Studio.
- Test przeprowadzam na innym komputerze w Android Studio. Wypchnij zmiany do github, a ja to ściągnę na innym komputerze i przeprowadzę testy.
- Nie kompiluj lokalnie. Zawsze wypychaj do github, a ja będę robił testy w Android Studio.

---

## Camera Architecture (Updated 2025-10-08)

### Native Camera Implementation

**NativeCameraView** - Direct port of Expo Camera architecture for zero-latency preview:
- Uses native FrameLayout + PreviewView hierarchy (not Compose AndroidView wrapper)
- Implements UseCaseGroup binding like Expo for optimal performance
- Self-managed lifecycle and camera switching
- Located at: `app/src/main/java/com/fitnessmirror/app/camera/NativeCameraView.kt`

### Camera Instance Separation

**CRITICAL**: Only ONE camera instance should be active for UI preview to avoid resource conflicts:

1. **UI Preview (Not Streaming)**:
   - ✅ Uses: `NativeCameraView` directly in DraggableCameraPIP
   - ✅ Zero CPU processing, GPU rendering only
   - ✅ 60fps smooth preview, zero latency
   - ❌ Do NOT initialize MainActivity.previewCameraManager (causes conflict)

2. **TV Streaming Mode**:
   - ✅ Uses: StreamingService with its own CameraManager instance
   - ✅ STREAMING mode with JPEG processing enabled
   - ✅ 320x240 @ 10fps @ 45% JPEG quality
   - ✅ Independent lifecycle managed by foreground service

### Camera Switching

**Without Streaming** (preview only):
- Handled by `surfaceRecreationTrigger` increment in MainActivity
- Triggers `NativeCameraView.switchCamera()` via LaunchedEffect
- Updates `isFrontCamera` state for UI

**During Streaming**:
- Handled by StreamingService.switchCamera()
- StreamingService has its own CameraManager
- Notifies MainActivity via callback

### Performance Regression Fix (Commit ac248ee)

**Problem Identified (2025-10-08)**:
- Two camera instances competing for same resource:
  1. MainActivity.previewCameraManager (PREVIEW_ONLY mode)
  2. NativeCameraView in DraggableCameraPIP
- This caused race condition and slow PIP preview

**Solution Applied**:
- Disabled `initializeCameraComponents()` in MainActivity (lines 139, 227)
- NativeCameraView is now the ONLY camera interface for UI
- CameraManager only used in StreamingService for TV streaming
- Camera switching works via NativeCameraView.switchCamera()

**Files Modified**:
- `MainActivity.kt` - Removed previewCameraManager initialization, updated switchCamera()
- `DraggableCameraPIP.kt` - Added hasCameraPermission parameter, deprecated cameraManager
- `WorkoutScreen.kt` - Added hasCameraPermission parameter

### Git Credentials Setup

If git push fails with authentication error, use GitHub CLI token:
```bash
gh auth status  # Verify logged in
gh auth token   # Get token
git config --global credential.helper store
echo "https://USERNAME:TOKEN@github.com" > ~/.git-credentials
chmod 600 ~/.git-credentials
```

---

## Recent Session Context (2025-10-08)

### Issue Resolved
PIP preview performance regression - camera was slow/laggy after recent commits.

### Root Cause
Commits focused on TV streaming optimization (e0da91a, ed55b9f, fafd67f, 769dc27) didn't modify NativeCameraView, but MainActivity.previewCameraManager was still initializing and competing for camera resources.

### Fix Verification
- Commit: `ac248ee` - "Fix PIP performance regression - eliminate CameraManager conflict"
- Status: Pushed to GitHub
- Testing: Required in Android Studio on physical device
- Expected: PIP preview returns to 60fps smooth performance like Phase 7.4

### Next Steps
1. Pull changes in Android Studio (Windows)
2. Test PIP preview performance without streaming
3. Test TV streaming functionality (should work normally via StreamingService)
4. Test camera switching in both modes
5. Verify no regressions in other functionality