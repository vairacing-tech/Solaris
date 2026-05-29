# Solaris Android GameStream Server

Servidor GameStream Android experimental para streaming desde un dispositivo Android hacia clientes compatibles con Moonlight/Artemis.

## Estado

- Android Kotlin + NDK.
- Captura sin root con `MediaProjection`.
- Encoding hardware obligatorio con `MediaCodec` para H.264 y HEVC.
- Prioridad Snapdragon/Qualcomm en seleccion de encoder.
- Configuracion de resolucion, FPS y bitrate desde la app.
- NVHTTP, pairing, RTSP y RTP video MVP.
- Perfil GameStream legacy `4.1.1.0` para evitar ENet/control cifrado en esta primera version.
- Pairing con PIN definido por Artemis/Moonlight: introduce en la app el PIN que muestra el cliente antes de confirmar el pairing.

## Build

```powershell
.\gradlew.bat assembleDebug testDebugUnitTest
```

APK debug:

```text
app\build\outputs\apk\debug\app-debug.apk
```

La firma debug usa la keystore estandar local de Android: `C:\Users\CJF\.android\debug.keystore`.

## Documentacion

Ver `docs/android-gamestream-server-plan.md` para arquitectura, puertos, limitaciones actuales y plan de pruebas.
