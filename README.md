# Otto DIY App

App Android nativa (Kotlin) para conectarse por Bluetooth Classic (SPP) al ESP32,
mostrar telemetria (temperatura, humedad, distancia) y controlar el robot.

Este proyecto NO trae el .apk compilado — necesita el SDK de Android para compilar,
algo que no está disponible en el entorno donde se generó este código. Tienes dos
formas rápidas de obtener el .apk:

## Opción A: Android Studio (la más simple, ~10 min)

1. Descarga e instala [Android Studio](https://developer.android.com/studio) (gratis).
2. Abre Android Studio → "Open" → selecciona la carpeta `OttoDiyApp`.
3. Espera a que sincronice Gradle (lo hace solo, descarga lo que falte).
4. Menú `Build` → `Build Bundle(s) / APK(s)` → `Build APK(s)`.
5. El .apk queda en `app/build/outputs/apk/debug/app-debug.apk`.
6. Pásalo a tu celular (cable, Drive, etc.) y ábrelo para instalar
   (activa "Instalar apps de origenes desconocidos" si Android lo pide).

## Opción B: GitHub Actions (sin instalar nada en tu PC)

1. Sube esta carpeta a un repositorio de GitHub.
2. Agrega un archivo `.github/workflows/build.yml` con:
   ```yaml
   name: Build APK
   on: [push]
   jobs:
     build:
       runs-on: ubuntu-latest
       steps:
         - uses: actions/checkout@v4
         - uses: actions/setup-java@v4
           with: { distribution: 'temurin', java-version: '17' }
         - run: gradle wrapper --gradle-version 8.4
         - run: ./gradlew assembleDebug
         - uses: actions/upload-artifact@v4
           with:
             name: app-debug
             path: app/build/outputs/apk/debug/app-debug.apk
   ```
3. Haz push. En la pestaña "Actions" del repo, al terminar el workflow,
   descarga el .apk desde "Artifacts".

## Cómo funciona

- `BluetoothLink.kt`: abre el socket RFCOMM con el UUID estándar de SPP
  (`00001101-0000-1000-8000-00805F9B34FB`), el mismo que usa `BluetoothSerial.h`
  en el ESP32. Lee línea por línea (cada mensaje JSON termina en `\n`).
- `MainActivity.kt`: parsea la telemetría (`type:"telemetry"`), envía comandos
  de control (`type:"cmd"`) y mantiene un heartbeat ping/pong cada 2s para
  detectar enlaces caídos, igual que se definió en el diseño de la lógica de
  estados.
- Antes de conectar, la app lista los dispositivos ya emparejados por
  Bluetooth del sistema — empareja el ESP32 desde Ajustes de Android
  primero (aparece como "ESP32" o el nombre que le pongas en el firmware).

## Firmware ESP32 correspondiente

Esta app espera que el ESP32 use `BluetoothSerial` (no BLE) y hable el mismo
protocolo JSON descrito antes: `telemetry`, `cmd`, `ack`, `error`, `ping`/`pong`.
Si quieres, en el siguiente paso te armo también el firmware completo en C++
para que ambos lados calcen exactamente.
