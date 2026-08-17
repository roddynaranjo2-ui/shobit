# Seguridad y privacidad

## Datos almacenados

Nexo guarda únicamente:

- el texto del perfil WireGuard;
- el paquete de la aplicación seleccionada.

El contenido se cifra con AES-256-GCM. La clave se genera dentro de Android Keystore y no es exportable. Android Backup y la transferencia dispositivo a dispositivo están desactivados para todos los dominios de datos de la aplicación.

Desinstalar Nexo elimina los datos y la clave. Si el almacenamiento cifrado queda corrupto o la clave se invalida, la aplicación marca el perfil como ilegible para que el usuario lo borre y lo importe otra vez.

## Datos que no se recopilan

El proyecto no integra analítica, publicidad, reporte remoto de fallos, cuentas, ubicación ni identificadores. El diagnóstico usa estadísticas locales del backend WireGuard y callbacks de red de Android; no genera ping continuo.

## Protección del perfil

Un archivo `.conf` contiene una clave privada que autoriza el acceso al VPS:

- usa un perfil distinto por teléfono;
- transfiérelo mediante SCP u otro canal cifrado;
- no lo incluyas en capturas, issues, logs ni repositorios;
- elimina copias innecesarias después de importarlo;
- si se expone, elimina el peer del servidor y genera otro perfil.

Los diálogos de edición activan `FLAG_SECURE` para reducir capturas accidentales. Esto no protege un dispositivo comprometido o con root.

## Superficie de red

Nexo pide solo `INTERNET` y `ACCESS_NETWORK_STATE`, además del consentimiento VPN administrado por Android y declarado por el servicio incluido en el backend oficial. No solicita cámara, almacenamiento general, contactos, ubicación ni notificaciones.

La aplicación no usa HTTP en claro. El tráfico de la app seleccionada sale con la IP del VPS. El cifrado WireGuard protege el tramo teléfono–VPS, no convierte conexiones HTTP del destino en HTTPS y no oculta metadatos al proveedor del VPS.

## VPS

El script:

- habilita reenvío IPv4;
- abre solamente el puerto UDP elegido cuando UFW ya está activo;
- configura NAT y un peer;
- guarda claves y configuraciones con permisos `0600`;
- no instala un panel web ni configura registros de navegación.

Mantén Ubuntu actualizado, usa autenticación SSH por clave, desactiva el acceso SSH por contraseña cuando sea posible y aplica el firewall del proveedor. Revisa `wg show` y elimina peers desconocidos.

## Reporte de vulnerabilidades

No publiques claves, perfiles ni direcciones del servidor al reportar un fallo. Describe los pasos con valores ficticios y especifica versión de Android, fabricante y versión de Nexo.
