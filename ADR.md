# 📑 Architecture Decision Records - FitnessMirror Native

## ADR-001 – **Migration from Expo to Native Android**

### **Context**
FitnessMirror MVP was successfully built using Expo React Native, providing YouTube workout playback with local camera PIP functionality. However, we need to add TV camera streaming capabilities that require native Android features not available in Expo.

### **Decision**
Migrate completely from Expo to native Android development using **Android Studio + Kotlin + Jetpack Compose**.

### **Alternatives Considered**
1. **Expo Development Build (Bare Workflow)**
   - ❌ Still limited by Expo constraints
   - ❌ Complex native module integration
   - ❌ Development workflow complications

2. **React Native CLI (Pure React Native)**
   - ⚠️ Would preserve JavaScript logic
   - ❌ Still lacks native camera streaming capabilities we need
   - ❌ Bridge performance overhead for intensive tasks

3. **Hybrid Approach (Keep Expo + separate streaming service)**
   - ❌ Two separate apps to maintain
   - ❌ Complex communication between apps
   - ❌ Poor user experience

### **Rationale**
- **Full Native Control:** Complete access to Android APIs, CameraX, networking
- **Performance:** Native performance for camera processing and WebSocket streaming
- **Integration Capability:** Can integrate proven CastApp streaming solutions
- **Professional Development:** Android Studio toolchain, better debugging, profiling
- **Future Scalability:** Unlimited access to Android features for future enhancements
- **TV Streaming Requirement:** Only possible with native WebSocket server implementation

### **Consequences**
- **Positive:**
  - Complete control over all application aspects
  - Better performance for camera and network operations
  - Can leverage existing CastApp codebase directly
  - Professional development environment
  - Play Store deployment readiness

- **Negative:**
  - Complete rewrite of UI layer (React Native → Jetpack Compose)
  - Longer initial development time
  - Need to learn/adapt to Android development patterns

- **Risks:**
  - Learning curve for Compose if unfamiliar
  - Potential bugs in migration from React paradigms

### **Migration Strategy**
- Port UI logic from React components to Jetpack Compose
- Integrate proven camera/streaming components from CastApp
- Maintain feature parity with original FitnessMirror
- Add TV streaming as primary new feature

---

## ADR-002 – **Camera Streaming Architecture Integration**

### **Context**
Need to implement camera streaming to TV while maintaining local PIP functionality. CastApp has a proven WebSocket-based streaming solution that works reliably with Smart TVs.

### **Decision**
Integrate **CameraManager + StreamingServer + NetworkUtils** components directly from CastApp with minimal modifications.

### **Alternatives Considered**
1. **Custom WebRTC Implementation**
   - ❌ Complex to implement reliably
   - ❌ TV browser compatibility issues
   - ❌ Higher latency than simple JPEG stream

2. **HTTP Live Streaming (HLS)**
   - ❌ Higher latency (~3-5 seconds)
   - ❌ More complex server implementation
   - ❌ Overkill for simple camera stream

3. **Custom UDP/TCP Streaming**
   - ❌ Requires custom client implementation
   - ❌ No TV browser support
   - ❌ Higher development complexity

### **Rationale**
- **Proven Solution:** CastApp streaming is battle-tested with Smart TVs
- **Low Latency:** <150ms latency with JPEG frames over WebSocket
- **TV Compatibility:** Multiple fallback endpoints (WebSocket, SSE) for different TV browsers
- **Minimal Effort:** Direct code reuse with minor adaptations
- **Reliability:** Known working solution reduces implementation risk

### **Consequences**
- **Positive:**
  - Immediate access to working streaming infrastructure
  - TV compatibility already solved
  - Performance characteristics known and acceptable
  - Multiple fallback mechanisms available

- **Negative:**
  - Locked into specific streaming protocol (WebSocket + JPEG)
  - Inherits any limitations of CastApp approach

- **Technical Details:**
  - **Resolution:** 320x240 (optimized for bandwidth and latency)
  - **Format:** JPEG compression at 45% quality
  - **Frame Rate:** ~10 FPS (100ms intervals)
  - **Protocol:** Binary WebSocket for main stream, SSE for fallback

---

## ADR-003 – **Dual Camera Usage Pattern**

### **Context**
Application needs to simultaneously:
1. Display local camera preview in draggable PIP (from FitnessMirror)
2. Stream camera feed to TV via WebSocket (from CastApp)

### **Decision**
Implement **single CameraX instance with dual output** - one Preview use case for local display, one ImageAnalysis use case for streaming, both sharing the same camera resource.

### **Alternatives Considered**
1. **Two Separate Camera Instances**
   - ❌ Not supported by CameraX - only one app can access camera at a time
   - ❌ Resource conflicts inevitable

2. **Single Instance for Streaming Only**
   - ❌ Loses local PIP functionality which is core FitnessMirror feature
   - ❌ Poor user experience

3. **Toggle Between Local/Streaming**
   - ❌ User would lose local mirror when streaming to TV
   - ❌ Defeats purpose of having both experiences

### **Rationale**
- **Resource Efficiency:** Single camera resource shared between use cases
- **CameraX Design:** Built for exactly this pattern (multiple use cases)
- **Performance:** Minimal overhead for dual output
- **User Experience:** User gets both local PIP and TV streaming simultaneously

### **Implementation Pattern**
```kotlin
// Single CameraX setup with multiple use cases
val preview = Preview.Builder().build()
val imageAnalysis = ImageAnalysis.Builder()
    .setBackpressureStrategy(KEEP_ONLY_LATEST)
    .build()

// Bind both use cases to single camera
cameraProvider.bindToLifecycle(
    lifecycleOwner,
    cameraSelector,
    preview,        // For local PIP display
    imageAnalysis   // For TV streaming
)
```

### **Consequences**
- **Positive:**
  - Efficient resource usage
  - Both features work simultaneously
  - Follows Android camera best practices
  - Single point of camera lifecycle management

- **Negative:**
  - Slightly more complex setup and configuration
  - Need to handle both use cases in single manager

---

## ADR-004 – **TV Web Client Architecture**

### **Context**
TV web client needs to combine two distinct functionalities:
1. YouTube video playback (from FitnessMirror concept)
2. Live camera stream display (from CastApp)

### **Decision**
Create **hybrid web client** with YouTube iframe taking 70% of screen and camera stream canvas in corner taking 30%, both active simultaneously.

### **Alternatives Considered**
1. **Full Screen Toggle Between YouTube and Camera**
   - ❌ User loses ability to see both simultaneously
   - ❌ Defeats purpose of mirror functionality during workout

2. **Side-by-Side 50/50 Split**
   - ❌ YouTube video becomes too small for effective viewing
   - ❌ Camera stream becomes unnecessarily large

3. **Picture-in-Picture with User Choice**
   - ⚠️ More complex UI controls needed on TV
   - ❌ TV remote control limitations

4. **Separate Pages/URLs**
   - ❌ User would need two TV browser tabs/windows
   - ❌ Poor user experience and setup complexity

### **Rationale**
- **Primary Content Focus:** YouTube workout video is main content, deserves majority of screen
- **Mirror Functionality:** Camera stream needs to be visible but not dominant
- **TV Viewing Distance:** Users typically sit farther from TV, smaller camera window still visible
- **Simplicity:** Single URL setup, no complex controls needed
- **Proven Layout:** Similar to TV news broadcasts with corner video feeds

### **Layout Specifications**
```html
<!-- Main YouTube area: 70% width, 75% height -->
<iframe id="youtube-player"
        width="70%" height="75%"
        src="https://www.youtube.com/embed/{VIDEO_ID}">

<!-- Camera stream: Fixed corner position -->
<div id="camera-container" style="
    position: fixed;
    bottom: 20px;
    right: 20px;
    width: 25vw;
    border: 2px solid #fff;">
    <canvas id="camera-stream"></canvas>
</div>
```

### **Consequences**
- **Positive:**
  - Clear visual hierarchy (workout video primary, mirror secondary)
  - Single URL access for users
  - Both functionalities always available
  - Familiar layout pattern

- **Negative:**
  - Fixed layout - no user customization
  - Camera stream size not user-adjustable
  - Layout not optimized for different TV sizes

- **Future Enhancements:**
  - URL parameters for layout customization
  - Multiple preset positions
  - TV remote control for repositioning

---

## ADR-005 – **State Management Pattern**

### **Context**
Native Android app needs to manage complex state including:
- YouTube URL and validation
- Camera preview and streaming status
- TV connection status and client count
- UI state (current screen, loading states)
- Network information (IP address, server status)

### **Decision**
Use **Jetpack Compose State + ViewModel pattern** with mutable state variables in MainActivity and proper state hoisting to Composable screens.

### **Alternatives Considered**
1. **Traditional Android Architecture (Activities + Fragments)**
   - ❌ More complex state management
   - ❌ Fragment lifecycle complications
   - ❌ Not as performant for UI updates

2. **Android Architecture Components (LiveData + DataBinding)**
   - ⚠️ More boilerplate code
   - ❌ Less reactive than Compose state
   - ❌ DataBinding adds complexity

3. **External State Management (Redux-like)**
   - ❌ Overkill for single-activity app
   - ❌ Additional dependencies and complexity
   - ❌ Not idiomatic for Compose applications

### **State Management Structure**
```kotlin
class MainActivity : ComponentActivity() {
    // UI State
    private var isStreaming by mutableStateOf(false)
    private var currentScreen by mutableStateOf<Screen>(Screen.Home)
    private var currentYouTubeUrl by mutableStateOf<String?>(null)

    // Camera State
    private var cameraPreview by mutableStateOf<Preview?>(null)
    private var hasCameraPermission by mutableStateOf(false)

    // Network State
    private var hasConnectedClient by mutableStateOf(false)
    private var serverAddress by mutableStateOf<String?>(null)

    // Callbacks from CastApp components
    override fun onClientConnected() {
        hasConnectedClient = true
    }

    // State hoisting to Composables
    setContent {
        when (currentScreen) {
            Screen.Home -> HomeScreen(
                onStartWorkout = { url ->
                    currentYouTubeUrl = url
                    currentScreen = Screen.Workout
                }
            )
            Screen.Workout -> WorkoutScreen(
                isStreaming = isStreaming,
                serverAddress = serverAddress,
                hasConnectedClient = hasConnectedClient,
                // ...
            )
        }
    }
}
```

### **Rationale**
- **Compose Native:** mutableStateOf is the idiomatic Compose approach
- **Simplicity:** Single activity architecture reduces complexity
- **Reactivity:** UI automatically recomposes when state changes
- **Performance:** Compose compiler optimizes recomposition
- **Familiarity:** Similar to React useState pattern from original FitnessMirror

### **Consequences**
- **Positive:**
  - Simple, reactive state management
  - Minimal boilerplate code
  - Automatic UI updates on state changes
  - Easy to debug and maintain

- **Negative:**
  - All state lives in single activity (could become large)
  - No state persistence across app restarts
  - Need careful state hoisting to avoid recomposition issues

- **Future Considerations:**
  - If app grows complex, migrate to ViewModel + StateFlow
  - Add state persistence with DataStore if needed
  - Consider state validation and error handling patterns

---

## ADR-006 – **Development Environment and Build System**

### **Context**
Development environment needs to support WSL2 + Windows workflow as used in original CastApp development, while migrating to native Android Studio.

### **Decision**
Use **WSL2 for source code + Windows Android Studio** for building and testing, with Git workflow for synchronization.

### **Alternatives Considered**
1. **Full Windows Development**
   - ⚠️ Would work but loses WSL2 development advantages
   - ❌ Breaks consistency with existing CastApp workflow
   - ❌ Less efficient command-line operations

2. **Full WSL2 Development with Android Studio**
   - ❌ Android Studio performance issues in WSL2
   - ❌ USB device access complications
   - ❌ Graphics performance problems

3. **Visual Studio Code + Command Line Build**
   - ❌ No visual layout editor for Compose
   - ❌ Missing Android Studio debugging tools
   - ❌ More complex build configuration

### **Development Workflow**
```bash
# WSL2 (source development)
cd /home/tomek/FitnessMirrorNative
git add -A && git commit -m "Feature implementation"
git push origin main

# Windows (Android Studio)
# Pull changes from Git
# Open project in Android Studio
# Build -> Make Project
# Run -> Run on device
```

### **Rationale**
- **Consistency:** Matches proven CastApp development workflow
- **Tool Strengths:** WSL2 for development, Windows for Android Studio
- **Git Bridge:** Clean separation and synchronization mechanism
- **Device Access:** Windows has better Android device connectivity
- **Performance:** Each tool runs in its optimal environment

### **Consequences**
- **Positive:**
  - Leverage best of both environments
  - Proven workflow from CastApp project
  - Clean version control with Git
  - Optimal performance for each tool

- **Negative:**
  - Need to sync changes between environments
  - Potential for Git workflow complications
  - Two environments to maintain

- **Best Practices:**
  - Frequent commits to avoid large sync operations
  - Clear commit messages for environment context
  - Use .gitignore to avoid binary file conflicts

---

## ADR-007 – **TV Compatibility Strategy**

### **Context**
Smart TV browsers have varying capabilities and limitations. CastApp solved this with multiple endpoint strategy, which we need to adapt for hybrid YouTube + camera functionality.

### **Decision**
Implement **multiple TV client endpoints** with progressive enhancement, each optimized for different TV browser capabilities.

### **Endpoint Strategy**
1. **`/` (Main Client):** Full YouTube + Camera with WebSocket
2. **`/test`:** Connection diagnostic page
3. **`/fallback`:** YouTube + Camera with SSE (Server-Sent Events)
4. **`/debug`:** Connection troubleshooting information

### **Progressive Enhancement Approach**
```javascript
// TV Detection and Capability Testing
function detectTVAndCapabilities() {
    const isTV = navigator.userAgent.includes('TV') ||
                 navigator.userAgent.includes('webOS') ||
                 navigator.userAgent.includes('Tizen');

    const hasWebSocket = typeof WebSocket !== 'undefined';
    const hasSSE = typeof EventSource !== 'undefined';

    return { isTV, hasWebSocket, hasSSE };
}

// Automatic Fallback Selection
if (hasWebSocket) {
    // Use main client with WebSocket
} else if (hasSSE) {
    // Redirect to /fallback with SSE
} else {
    // Show compatibility error
}
```

### **Rationale**
- **Proven Solution:** CastApp's multi-endpoint approach works reliably
- **Broad Compatibility:** Covers wide range of TV browsers
- **Graceful Degradation:** Always provides some functionality
- **User Experience:** Automatic selection of best option
- **Debugging:** Test and debug endpoints help troubleshooting

### **Consequences**
- **Positive:**
  - High TV compatibility success rate
  - User doesn't need to understand technical details
  - Multiple fallback options available
  - Easy troubleshooting for users

- **Negative:**
  - Multiple client implementations to maintain
  - Increased testing complexity
  - More server endpoints to manage

---

## ADR-008 – **Performance and Resource Optimization**

### **Context**
Application performs intensive tasks: camera processing, video streaming, YouTube playback, and real-time UI updates. Need to optimize for mobile device constraints.

### **Decision**
Implement **multi-layer optimization strategy** targeting CPU, memory, network, and battery efficiency.

### **Optimization Targets**
- **Camera Processing:** 320x240 @ 45% JPEG quality @ 10fps
- **Memory Usage:** <100MB total app memory
- **Battery Impact:** <15% additional drain during 30min session
- **Network:** ~1-2 Mbps for camera stream
- **UI Performance:** 60fps Compose UI, <16ms frame time

### **Implementation Strategy**

#### Camera Optimization (from CastApp)
```kotlin
companion object {
    private const val TARGET_WIDTH = 320
    private const val TARGET_HEIGHT = 240
    private const val JPEG_QUALITY = 45    // Reduced for lower latency
    private const val FRAME_RATE_MS = 100L // ~10 FPS
}
```

#### Memory Management
```kotlin
// Proper bitmap cleanup
bitmap.recycle()
outputStream.close()

// Background processing
private var cameraExecutor = Executors.newSingleThreadExecutor()
private var streamingScope = CoroutineScope(Dispatchers.Default + Job())
```

#### Network Efficiency
- **Binary WebSocket:** More efficient than Base64 encoding
- **Frame dropping:** Skip frames if processing can't keep up
- **Compression:** JPEG quality tuned for size vs quality balance

### **Rationale**
- **Mobile Constraints:** Mobile devices have limited CPU/memory/battery
- **User Experience:** Smooth performance essential for fitness app
- **Proven Values:** CastApp optimization values are battle-tested
- **Multi-tasking:** App must handle camera + network + UI simultaneously

### **Consequences**
- **Positive:**
  - Acceptable performance on mid-range devices
  - Good battery life during workouts
  - Smooth user interface experience
  - Network efficient streaming

- **Negative:**
  - Lower quality camera stream (320x240)
  - Some frame dropping under heavy load
  - Complexity in resource management code

---

## ADR-009 – **YouTube Player Implementation Strategy**

### **Context**
Initial implementation used WebView with YouTube embed iframes, but encountered critical stability issues including MediaCodec errors, Chromium crashes, white screen problems, and BufferQueue overflows. These issues made the app unreliable for workout sessions.

### **Decision**
Replace **WebView-based YouTube playback** with **android-youtube-player library** (`com.pierfrancescosoffritti.androidyoutubeplayer:core:12.1.0`).

### **Alternatives Considered**
1. **WebView with YouTube embed (original approach)**
   - ❌ MediaCodec errors: "BufferQueue has been abandoned"
   - ❌ Chromium crashes and process restarts
   - ❌ White screen with audio-only playback
   - ❌ Poor lifecycle management causing resource conflicts

2. **ExoPlayer with YouTube extraction**
   - ❌ YouTube actively blocks video URL extraction
   - ❌ Violates YouTube Terms of Service
   - ❌ Unreliable due to frequent API changes

3. **YouTube Data API v3 + Custom Player**
   - ❌ Cannot access actual video streams (only metadata)
   - ❌ Still requires embedded player for actual playback
   - ❌ Additional API complexity

4. **Browser Intent (external app)**
   - ❌ Breaks in-app experience requirement
   - ❌ User loses camera PIP functionality
   - ❌ Poor workout session continuity

### **Rationale**
- **Stability First:** android-youtube-player is specifically designed for Android apps
- **Native Integration:** Proper AndroidView wrapper for Jetpack Compose
- **Lifecycle Management:** Built-in handling of Activity lifecycle events
- **Proven Track Record:** Used successfully in thousands of Android apps
- **Official Support:** YouTube-approved library with proper API usage
- **Equivalent Solution:** Native Android equivalent of `react-native-youtube-iframe` used in original FitnessMirror

### **Implementation Pattern**
```kotlin
AndroidView(
    modifier = modifier.clip(RoundedCornerShape(8.dp)),
    factory = { context ->
        YouTubePlayerView(context).apply {
            if (context is androidx.lifecycle.LifecycleOwner) {
                context.lifecycle.addObserver(this)
            }

            addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
                override fun onReady(youTubePlayer: YouTubePlayer) {
                    youTubePlayer.loadVideo(videoId, 0f)
                }

                override fun onError(youTubePlayer: YouTubePlayer, error: PlayerError) {
                    Log.e("YouTubePlayer", "Player error: $error")
                }
            })
        }
    }
)
```

### **Migration Results**
- ✅ **Eliminated MediaCodec errors** completely
- ✅ **Resolved Chromium crashes** and process restarts
- ✅ **Fixed white screen issue** - video displays properly
- ✅ **Proper lifecycle management** with automatic cleanup
- ✅ **Better error handling** with meaningful error callbacks
- ✅ **Improved stability** during workout sessions

### **Consequences**
- **Positive:**
  - Stable YouTube playback without crashes
  - Proper Android lifecycle integration
  - Better user experience with reliable video
  - Eliminates MediaCodec resource conflicts with camera
  - Official YouTube API compliance

- **Negative:**
  - Additional dependency (12.1.0 library)
  - Slightly larger APK size
  - Need to learn new library API instead of standard WebView

- **Performance Impact:**
  - Reduced memory usage (no Chromium process overhead)
  - Better resource management for camera + video playback
  - Eliminated BufferQueue conflicts

### **Future Considerations**
- Library is actively maintained and updated
- Follows YouTube's official embedding guidelines
- Compatible with future Android API changes
- Can be enhanced with additional features (playlist support, quality selection)

---

## 📋 Decision Summary Table

| ADR | Decision | Rationale | Impact |
|-----|----------|-----------|---------|
| **ADR-001** | Expo → Native Android | Full native control + TV streaming | Complete rewrite, better capabilities |
| **ADR-002** | CastApp streaming integration | Proven solution, low latency | Immediate streaming functionality |
| **ADR-003** | Dual camera usage pattern | Efficient resource sharing | Local PIP + TV streaming together |
| **ADR-004** | Hybrid TV web client | YouTube primary + camera secondary | Single URL, clear visual hierarchy |
| **ADR-005** | Compose State management | Simple, reactive pattern | Minimal complexity, good performance |
| **ADR-006** | WSL2 + Windows workflow | Proven development environment | Consistent with CastApp approach |
| **ADR-007** | Multiple TV endpoints | Broad compatibility coverage | High success rate across TV types |
| **ADR-008** | Performance optimization | Mobile resource constraints | Good UX within device limitations |
| **ADR-009** | android-youtube-player library | Stability + official YouTube support | Eliminated crashes, reliable playback |

---

## 🔮 Future Architecture Considerations

### Potential Evolution Points

1. **State Management Scaling**
   - If app complexity grows: migrate to ViewModel + StateFlow
   - Add state persistence with DataStore
   - Consider MVI (Model-View-Intent) architecture

2. **Multi-TV Support**
   - Current: Single TV connection
   - Future: Multiple concurrent TV connections
   - Impact: Need connection pooling, resource allocation

3. **Enhanced Streaming**
   - Current: JPEG @ 320x240
   - Future: H.264 hardware encoding, adaptive quality
   - Impact: Better quality, more complex implementation

4. **Modular Architecture**
   - Current: Monolithic single activity
   - Future: Feature modules, dynamic delivery
   - Impact: Better code organization, reduced APK size

5. **Cross-Platform Expansion**
   - Current: Android only
   - Future: iOS version using shared business logic
   - Impact: Kotlin Multiplatform consideration

### Technical Debt Considerations

- **CastApp Integration:** Direct code copying may create maintenance burden
- **Single Activity Pattern:** May not scale if app grows significantly
- **Hard-coded Constants:** Performance values should be configurable
- **Error Handling:** Could be more comprehensive with user-friendly messages

---

**🎯 These architectural decisions prioritize rapid development with proven solutions while maintaining flexibility for future enhancements. The hybrid approach leverages the best of both FitnessMirror and CastApp projects to create a superior fitness experience.**