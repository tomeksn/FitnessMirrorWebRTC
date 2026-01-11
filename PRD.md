# 🏃‍♀️📱📺 FitnessMirror Native - PRD v2.0

## 🎯 Cel projektu

Stworzenie natywnej aplikacji na Androida, która łączy w sobie funkcjonalności:
- **YouTube workout playback** - odtwarzanie treningów z YouTube
- **Camera PIP** - lokalny podgląd z kamerki podczas treningu
- **TV Camera Streaming** - przesyłanie obrazu z kamerki na Smart TV
- **Synchronized Experience** - YouTube na TV + camera stream na tym samym ekranie

Projekt będzie **migracją z Expo na natywny Android** z wykorzystaniem sprawdzonych rozwiązań z **CastApp** do streamingu kamerki.

---

## 🧑‍💼 Grupa docelowa

- **Entuzjaści fitness domowego** którzy chcą trenować z YouTube
- **Użytkownicy z Smart TV** pragnący większego ekranu do treningów
- **Osoby dbające o technikę** potrzebujące podglądu swoich ruchów
- **Tech-savvy fitness lovers** szukający zaawansowanych rozwiązań

---

## ✅ Wymagania funkcjonalne

### 📱 Aplikacja Android (Enhanced)

#### Core Features z FitnessMirror ✅ **COMPLETED**
- ✅ Input YouTube URL z walidacją różnych formatów
- ✅ Lokalny odtwarzacz YouTube (stable android-youtube-player library)
- ✅ Camera PIP z funkcjonalnościami:
  - ✅ Draggable (przeciąganie)
  - ✅ Resizable (pinch-to-zoom 0.5x-3x)
  - ✅ Boundary checking
  - ✅ Rotation handling (landscape/portrait)
- ✅ Basic controls (play/pause YouTube)

#### Nowe funkcjonalności z CastApp ✅ **COMPLETED**
- ✅ **Camera streaming server** (WebSocket + NanoHTTPD)
- ✅ **Network discovery** (IP detection, port configuration)
- ✅ **Dual camera usage:**
  - ✅ Lokalny PIP preview na telefonie
  - ✅ Jednoczesny stream na TV via WebSocket
- ✅ **Connection management:**
  - ✅ Client connection status
  - ✅ Auto-reconnection handling
  - ✅ Multiple endpoint support (/main, /test, /fallback)

### 📺 Smart TV Web Client (New)

#### Hybrydowa funkcjonalność ✅ **COMPLETED**
- ✅ **YouTube Player** (główny ekran 70-80% powierzchni)
  - ✅ Iframe embed z URL przekazywanym z telefonu
  - ✅ Full video controls dostępne
  - ✅ Responsive scaling
- ✅ **Camera Stream Overlay** (20-30% powierzchni w rogu)
  - ✅ WebSocket binary stream (JPEG frames)
  - ✅ Canvas rendering z CastApp
  - ✅ Configurable position/size
  - ✅ Auto-mirror mode dla TV

#### TV Compatibility Features ✅ **COMPLETED**
- ✅ **ES5 JavaScript** compatibility dla starszych TV
- ✅ **Server-Sent Events** fallback dla TV bez WebSocket
- ✅ **Multiple connection endpoints:**
  - ✅ `/` - Main client (YouTube + Camera)
  - ✅ `/test` - Connection testing
  - ✅ `/fallback` - SSE version for problem TVs
  - ✅ `/debug` - Diagnostics page

---

## 🛠️ Wymagania niefunkcjonalne

### Performance
- **Camera streaming**: <150ms latency (jak w CastApp)
- **YouTube playback**: Smooth 30fps bez stuttering
- **Memory usage**: <100MB RAM podczas użytkowania
- **Battery impact**: Akceptowalny dla 30-60min treningu

### Network Requirements
- **Local WiFi**: 5GHz zalecane dla streaming
- **Bandwidth**: ~1-2 Mbps dla camera stream (320x240 JPEG)
- **No Internet required** dla camera streaming (lokalny WebSocket)
- **Internet required** dla YouTube playback

### Device Compatibility
- **Android**: 7.0+ (API 24+)
- **Smart TV browsers**: WebKit, Chromium, Samsung/LG native
- **Development**: WSL2/Windows Android Studio workflow

---

## 📦 Stack technologiczny

### Android Native
| Komponent | Technologia | Pochodzenie |
|----------|-------------|-------------|
| **Framework** | Kotlin + Jetpack Compose | New |
| **Camera** | CameraX (320x240, 10fps, JPEG) | CastApp |
| **Streaming** | NanoHTTPD + WebSocket | CastApp |
| **Network** | NetworkUtils, IP detection | CastApp |
| **Navigation** | Navigation Compose | New |
| **State** | ViewModel + State | New |

### TV Web Client
| Komponent | Technologia | Pochodzenie |
|----------|-------------|-------------|
| **YouTube** | android-youtube-player | Stable native library |
| **Camera Stream** | Canvas + WebSocket | CastApp |
| **Compatibility** | ES5 + SSE fallback | CastApp |
| **Layout** | CSS Grid/Flexbox | New |

---

## 🎨 Interfejs użytkownika

### Android App Screens

#### 1. Home Screen
- **YouTube URL Input** (z FitnessMirror)
- **"Start Workout"** button
- **Server status** indicator (IP:Port display)
- **Quick links** dla często używanych kanałów

#### 2. Workout Screen
- **Local YouTube Player** (opcjonalnie, lub tylko controls)
- **Camera PIP overlay** (draggable/resizable z FitnessMirror)
- **TV Connection panel:**
  - [ ] IP address display
  - [ ] QR code dla łatwego connection
  - [ ] Connection status (connected/disconnected)
  - [ ] Number of connected clients
- **Controls:**
  - [ ] Start/Stop streaming
  - [ ] Switch camera (front/back)
  - [ ] PIP controls (hide/show/resize)

### TV Web Interface

#### Layout Concept
```
┌─────────────────────────────────────────────┐
│                                             │
│        YouTube Player (Main)               │
│        (70-80% screen)                     │
│                                             │
│                    ┌─────────────────────┐  │
│                    │   Camera Stream     │  │
│                    │   (20% corner)      │  │
│                    │   [Your mirror]     │  │
│                    └─────────────────────┘  │
└─────────────────────────────────────────────┘
```

#### TV User Experience
1. **URL Access**: `http://phone-ip:8080/?video=VIDEO_ID`
2. **Auto-setup**: YouTube loads + Camera stream connects automatically
3. **Fallback modes**: Multiple endpoints dla różnych TV browsers
4. **Connection feedback**: Status indicators, test pages

---

## 🧪 User Flow & Testing

### Typical Workout Session
1. **Phone**: User enters YouTube workout URL
2. **Phone**: Presses "Start Workout"
3. **Phone**: App shows local PIP + starts streaming server
4. **TV**: User navigates to displayed IP address
5. **TV**: YouTube workout loads + camera stream appears in corner
6. **Parallel experience**: User sees himself both locally and on TV
7. **Workout**: Full YouTube controls available, camera stream live

### Connection Methods
- **Direct IP**: Manual browser navigation
- **QR Code**: Generated on phone, scan with TV/phone
- **Auto-discovery**: Future enhancement (mDNS/UPnP)

---

## 🔒 Bezpieczeństwo i prywatność

- **Local network only** - brak zewnętrznego internetu dla streaming
- **No video storage** - camera stream tylko live, nie zapisywany
- **No authentication** (faza 1) - suitable dla trusted home network
- **Future**: Token-based auth, HTTPS option

---

## 📊 Success Metrics (KPIs)

### Technical Performance
- [ ] App launch time <5 sekund
- [ ] Camera streaming latency <150ms
- [ ] YouTube playback bez lag/stuttering
- [ ] TV connection success rate >90%
- [ ] No crashes podczas 30min session

### User Experience
- [ ] Setup workout w <2 minuty (including TV connection)
- [ ] Intuitive PIP controls (drag/resize)
- [ ] Reliable TV streaming connection
- [ ] Positive user feedback na ease of use

---

## 🚀 Roadmap & Future Enhancements

### Version 1.0 (MVP)
- ✅ Basic YouTube + Camera PIP (local)
- ✅ TV Camera streaming
- ✅ Hybrid TV experience
- ✅ Multiple TV compatibility endpoints

### Version 1.5 (Enhanced)
- [ ] QR code connection
- [ ] Workout history/favorites
- [ ] Multiple camera angles/positions
- [ ] Audio streaming to TV

### Version 2.0 (Advanced)
- [ ] Multi-TV support (multiple rooms)
- [ ] AI pose detection integration
- [ ] Workout analytics and progress
- [ ] Social sharing capabilities

---

## 🔄 Migration Benefits (Expo → Native)

### ✅ **Advantages Gained**
- **Full native control** - no Expo limitations
- **Better performance** - native camera/networking
- **Advanced integrations** - CameraX, WebSocket servers
- **TV streaming capability** - impossible with Expo
- **Professional development** - Android Studio toolchain
- **Future scalability** - unlimited native Android features

### 📊 **Feature Comparison**

| Feature | FitnessMirror (Expo) | FitnessMirrorNative |
|---------|---------------------|---------------------|
| YouTube Playback | ✅ WebView | ✅ android-youtube-player |
| Camera PIP | ✅ Drag/resize | ✅ Same + streaming |
| TV Integration | ❌ None | ✅ Full streaming |
| Performance | 🟡 Good | ✅ Excellent |
| Development Control | 🟡 Limited | ✅ Full control |
| Deployment | 🟡 Expo managed | ✅ Play Store ready |

---

## 📅 Timeline Estimate

| Phase | Duration | Deliverables |
|--------|----------|-------------|
| **Planning & Setup** | 1 day | PRD, ADR, TASKS, Project structure |
| **Core Migration** | 2-3 days | Basic screens, navigation, YouTube |
| **Camera Integration** | 2 days | Local PIP + streaming server |
| **TV Web Client** | 1-2 days | Hybrid YouTube + camera page |
| **Testing & Polish** | 1-2 days | Multi-device testing, bug fixes |
| **Documentation** | 0.5 day | README, API docs, setup guides |

**Total: ~7-10 days** (depending on complexity and testing requirements)

---

## 📎 Notes & Considerations

### Development Environment
- **WSL2 + Windows** setup compatible
- **Android Studio** on Windows host
- **Git workflow** for code synchronization
- **Physical device testing** recommended dla camera/streaming features

### Technical Risks
- **TV browser compatibility** - mitigation: multiple fallback endpoints
- **Network complexity** - mitigation: proven CastApp solutions
- **Camera resource sharing** - mitigation: CameraX single instance management
- **Performance optimization** - mitigation: low resolution streaming, efficient JPEG

### Success Dependencies
- **Stable WiFi network** for streaming
- **Modern Smart TV** with decent browser
- **Android device** with decent camera
- **User comfort** with manual IP connection (phase 1)

---

**🎯 Outcome: Professional-grade fitness app combining best of both FitnessMirror and CastApp projects with native Android performance and TV integration capabilities.**