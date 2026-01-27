# Notatka na kolejną sesję (2026-01-27)

## Co zostało zrobione

### Problem: Duże opóźnienie WebRTC video na TV

**Przyczyna:** Ramki były wysyłane w pełnej rozdzielczości 1088x1088 zamiast 320x240.

**Rozwiązanie:** Dodano skalowanie ramek przed kodowaniem WebRTC.

### Commit: da1cf19
```
Scale WebRTC frames to 320x240 to reduce latency
```

**Plik:** `app/src/main/java/com/fitnessmirror/webrtc/streaming/WebRTCManager.kt`

**Zmiany:**
1. Dodano `cropAndScale()` w funkcji `imageProxyToVideoFrame()` - skaluje 1088x1088 → 320x240
2. Zredukowano logowanie - tylko co 100 ramek zamiast każdej
3. Usunięto nieużywaną funkcję `detectAvailableBufferClasses()`

## Do przetestowania

1. **Pull zmiany w Android Studio**
2. **Uruchom streaming WebRTC na TV**
3. **Sprawdź logi** - powinny pokazać:
   ```
   📐 Scaling enabled: 1088x1088 -> 320x240 (10x less pixels)
   📊 Frame #1 - input: 1088x1088, target: 320x240
   ```
4. **Zmierz opóźnienie** - oczekiwane: ~200-300ms (było ~1-2s)

## Jeśli nadal jest opóźnienie

Możliwe dalsze optymalizacje:
- Sprawdzić czy połączenie idzie przez TURN relay (logi ICE candidate type)
- Rozważyć lokalne połączenie P2P zamiast TURN
- Zwiększyć target bitrate w WebRTC
