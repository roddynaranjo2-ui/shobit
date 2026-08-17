# Nexo Game Tunnel

Cliente Android mínimo de WireGuard para proteger **solo Arena Breakout Global** —o la aplicación instalada que elijas— cuando la red móvil cambia o sufre microcortes.

> **Límite importante:** una VPN no puede crear conectividad durante una interrupción total de ETECSA. Tampoco puede garantizar que el servidor del juego conserve una sesión si cambia la ruta o la IP pública. Nexo prioriza el roaming natural de WireGuard y solo reinicia un túnel estancado después de que Android confirma que la red volvió.

## Funciones

- Android 8.0 o posterior (`minSdk 26`), exclusivamente ARM64 (`arm64-v8a`).
- WireGuard userspace sobre `VpnService`, sin root.
- VPN por aplicación; `com.proximabeta.mf.uamo` (Arena Breakout Global) es la opción inicial.
- Importación de un `.conf`, texto pegado o formulario manual; no solicita cámara ni implementa QR.
- Estado local del túnel, tipo de red, último handshake y bytes enviados/recibidos.
- Recuperación adaptativa sin ping continuo ni tráfico de telemetría.
- Perfil cifrado con AES-256-GCM y una clave no exportable de Android Keystore.
- Backups y transferencia del almacenamiento de la aplicación desactivados.
- Interfaz oscura táctica en español.

## Compilar un APK instalable

### Android Studio

1. Instala Android Studio con **JDK 17**, Android SDK Platform 36 y las herramientas de SDK.
2. Abre la carpeta raíz del proyecto y espera la sincronización de Gradle.
3. Elige **Build > Build Bundle(s) / APK(s) > Build APK(s)** con la variante `debug`.
4. El APK queda en:

```text
app/build/outputs/apk/debug/app-debug.apk
```

El APK `debug` ya está firmado por las herramientas Android y puede instalarse. Para una distribución pública, no reutilices la clave de depuración: consulta [docs/BUILD.md](docs/BUILD.md).

### Línea de comandos

```bash
export JAVA_HOME=/ruta/al/jdk-17
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew --no-daemon clean lintDebug testDebugUnitTest assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No se necesita NDK: la dependencia oficial de WireGuard incluye su backend nativo. El módulo filtra el APK a `arm64-v8a`.

## Preparar el VPS

Necesitas un VPS Ubuntu con IPv4 pública. Empieza por una ubicación con la menor latencia real desde tu conexión; para Cuba suele ser razonable **probar** primero el sur de Florida, pero la ruta de cada operador puede variar. Una máquina pequeña (1 vCPU y 512 MB–1 GB de RAM) basta para un usuario.

En un VPS nuevo con Ubuntu 22.04 o 24.04:

```bash
scp scripts/setup-wireguard-vps.sh root@IP_PUBLICA:/root/
ssh root@IP_PUBLICA
chmod 700 /root/setup-wireguard-vps.sh
/root/setup-wireguard-vps.sh --endpoint IP_PUBLICA
```

Permite también **UDP/51820** en el firewall o grupo de seguridad del proveedor. Después copia el perfil a tu computadora sin publicarlo ni enviarlo por un chat:

```bash
scp root@IP_PUBLICA:/root/nexo-clients/arena-cuba.conf ./
```

La guía completa, verificación y mantenimiento están en [docs/VPS_UBUNTU.md](docs/VPS_UBUNTU.md).

## Uso

1. Instala y abre Nexo Game Tunnel.
2. Importa `arena-cuba.conf` desde un archivo. También puedes pegar el texto o completar el formulario.
3. Confirma que **Arena Breakout Global** es la aplicación protegida; pulsa *Cambiar* para elegir otra aplicación con launcher.
4. Pulsa **Conectar** y acepta una vez el diálogo VPN de Android.
5. Espera un handshake reciente y abre el juego.

Solo el paquete seleccionado entra al túnel. Cambiar de aplicación mientras está conectado reinicia la sesión VPN de forma controlada para aplicar la nueva lista.

## Recuperación y diagnóstico

Nexo escucha los callbacks de conectividad de Android. Ante una pérdida o cambio de red:

- deja primero que WireGuard haga roaming de endpoint;
- concede 12 segundos después de recuperar red y exige tráfico saliente antes de intervenir;
- fuera de un cambio de red, exige al menos 4096 bytes de salida sin recepción durante 18 segundos;
- impone 60 segundos de espera entre recuperaciones.

Las estadísticas se leen localmente cada dos segundos. Nexo no envía pings periódicos, análisis, anuncios ni identificadores.

## Seguridad y alcance

- El perfil contiene una clave privada: trátalo como una contraseña y revócalo si se filtra.
- La pantalla de edición impide capturas mediante `FLAG_SECURE`.
- Nexo no es un servicio VPN comercial: tú controlas el servidor y sus registros.
- El instalador no configura logs de navegación. El proveedor del VPS y los destinos aún pueden observar metadatos normales de red.
- Este proyecto no está afiliado con Arena Breakout, MoreFun Studios ni Tencent.

Consulta [docs/SECURITY.md](docs/SECURITY.md) antes de distribuir una compilación.

## Estructura

```text
app/                         Aplicación Android
scripts/setup-wireguard-vps.sh  Instalador reproducible para Ubuntu
docs/BUILD.md                Compilación y firma
docs/VPS_UBUNTU.md           Despliegue y operación del servidor
docs/SECURITY.md             Modelo de seguridad y privacidad
docs/android-ci.yml           Plantilla de validación y APK de CI
```

## Licencias

El cliente integra `com.wireguard.android:tunnel`. Conserva y revisa los avisos de licencia de todas las dependencias antes de redistribuir el APK.
