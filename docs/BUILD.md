# Compilación y firma

## Requisitos exactos

- Sistema de 64 bits.
- JDK 17.
- Android SDK Platform 36 y SDK Build Tools 36.x.
- Conexión a Google Maven y Maven Central durante la primera sincronización.
- No se necesita Android NDK.

El repositorio incluye Gradle Wrapper 8.13 y usa Android Gradle Plugin 8.13.2. No sustituyas el wrapper por un Gradle del sistema.

## Validación de desarrollo

Desde la raíz:

```bash
java -version
./gradlew --version
./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

El APK de depuración es instalable y está en `app/build/outputs/apk/debug/app-debug.apk`. Solo contiene bibliotecas ARM64; un emulador x86_64 no podrá instalarlo.

Prueba como mínimo:

1. Android 8/9 y una versión Android reciente en dispositivos ARM64.
2. Consentimiento VPN inicial y conexión/desconexión repetida.
3. Importación de archivo, texto y formulario.
4. Cambio entre datos móviles y Wi‑Fi durante tráfico real.
5. Selector de aplicación y ausencia de túnel en otras aplicaciones.
6. Reinicio del proceso y recuperación del perfil cifrado.
7. Modo avión: la UI debe reconocer la falta de red y no prometer continuidad.

## Firma de producción

Crea una clave fuera del repositorio:

```bash
keytool -genkeypair -v \
  -keystore "$HOME/nexo-release.jks" \
  -alias nexo \
  -keyalg RSA -keysize 4096 -validity 10000
```

No añadas el `.jks`, contraseñas ni `keystore.properties` a Git. Android Studio puede generar un APK firmado con **Build > Generate Signed Bundle / APK**. Elige APK, la clave anterior, variante `release` y firma V2/V3.

También puedes compilar el release sin firmar:

```bash
./gradlew clean lintRelease assembleRelease
```

Luego firma con `apksigner` de Android Build Tools y verifica:

```bash
apksigner sign \
  --ks "$HOME/nexo-release.jks" \
  --ks-key-alias nexo \
  --out NexoGameTunnel-release.apk \
  app/build/outputs/apk/release/app-release-unsigned.apk
apksigner verify --verbose --print-certs NexoGameTunnel-release.apk
sha256sum NexoGameTunnel-release.apk
```

Introduce contraseñas de forma interactiva; no las pongas en el comando ni en variables registradas por CI. Conserva la clave en dos copias cifradas. Las actualizaciones Android deben estar firmadas por la misma clave.

## Instalación

Activa temporalmente la depuración USB e instala:

```bash
adb devices
adb install -r NexoGameTunnel-release.apk
```

O transfiere el APK al teléfono, confirma su SHA-256 y autoriza la instalación desde esa fuente. Desactiva ese permiso después.

## CI

`docs/android-ci.yml` es una plantilla que ejecuta lint, pruebas unitarias y `assembleDebug`, y publica `NexoGameTunnel-debug` como artefacto. Para activarla, cópiala a `.github/workflows/android.yml` con una cuenta que tenga permiso para administrar workflows. Es una compilación de prueba, no un release firmado para distribución.
