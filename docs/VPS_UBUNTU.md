# VPS WireGuard para Nexo

## 1. Elegir el servidor

Requisitos:

- Ubuntu Server 22.04 o 24.04 de 64 bits;
- una IPv4 pública fija;
- virtualización que permita UDP, TUN y reenvío de paquetes;
- acceso root por SSH;
- al menos 1 vCPU, 512 MB de RAM y unos pocos GB de disco.

La cercanía geográfica no garantiza una ruta rápida. Prueba primero una ubicación cercana —por ejemplo, sur de Florida desde Cuba— y compara el handshake y la experiencia real. No compres un plazo largo antes de probar. Confirma que los términos del proveedor permiten VPN personal.

## 2. Endurecimiento básico

Entra por SSH, actualiza y reinicia si el sistema lo pide:

```bash
apt update && apt full-upgrade -y
```

Usa una clave SSH. Antes de desactivar contraseñas, abre una segunda sesión y confirma que la clave funciona. Configura el firewall del panel del proveedor para permitir:

- TCP/22 desde tus direcciones administrativas, si es posible;
- UDP/51820 desde cualquier origen, porque la IP móvil puede cambiar.

No abras paneles web ni puertos adicionales para Nexo.

## 3. Ejecutar el instalador

Desde tu computadora:

```bash
scp scripts/setup-wireguard-vps.sh root@203.0.113.10:/root/
ssh root@203.0.113.10
chmod 700 /root/setup-wireguard-vps.sh
/root/setup-wireguard-vps.sh --endpoint 203.0.113.10
```

Sustituye `203.0.113.10` por la IPv4 real. Opciones útiles:

```bash
/root/setup-wireguard-vps.sh --help
/root/setup-wireguard-vps.sh \
  --endpoint vpn.ejemplo.net \
  --port 51820 \
  --client-name arena-cuba \
  --mtu 1280
```

El script se detiene si `/etc/wireguard/wg0.conf` ya existe. `--force` respalda las configuraciones actuales con un sufijo UTC, las reemplaza y revoca el perfil anterior; úsalo únicamente si esa es tu intención.

## 4. Copiar el perfil

El perfil se crea como `/root/nexo-clients/arena-cuba.conf`, con permisos `0600`.

```bash
scp root@203.0.113.10:/root/nexo-clients/arena-cuba.conf ./
```

Impórtalo desde Nexo. No lo mandes por grupos, correo sin cifrar o servicios públicos. Borra la copia del equipo compartido cuando termines.

El perfil usa:

- `PersistentKeepalive = 25`, útil detrás de NAT móvil;
- `MTU = 1280`, conservador para redes con encapsulación;
- ruta IPv4 `0.0.0.0/0`, aplicada solo al paquete elegido por Nexo;
- clave precompartida adicional.

La configuración predeterminada no ofrece IPv6 dentro del túnel. Android evita una fuga de esa familia para la app protegida; el juego usará IPv4 a través del VPS.

## 5. Verificar

En el VPS:

```bash
systemctl status wg-quick@wg0 --no-pager
wg show wg0
ss -lunp | grep 51820
sysctl net.ipv4.ip_forward
```

Después de conectar el teléfono y generar tráfico, `wg show` debe indicar un handshake reciente y aumentar RX/TX. Si no hay handshake:

1. confirma la IP o dominio de `Endpoint`;
2. permite UDP/51820 en el firewall del proveedor y en UFW;
3. confirma que `wg-quick@wg0` está activo;
4. prueba desde otra red para distinguir un bloqueo de ruta;
5. si eliges otro puerto UDP, cámbialo tanto en servidor como en perfil.

Si hay handshake pero no navega, revisa la interfaz de salida presente en `PostUp`, el reenvío y NAT:

```bash
ip -4 route show default
iptables -S FORWARD
iptables -t nat -S POSTROUTING
journalctl -u wg-quick@wg0 --no-pager -n 100
```

## 6. Operación

Actualiza regularmente:

```bash
apt update && apt full-upgrade -y
systemctl restart wg-quick@wg0
```

Reiniciar el servidor interrumpe la partida activa. Haz mantenimiento fuera del horario de uso.

Para detener temporalmente:

```bash
systemctl stop wg-quick@wg0
```

Para iniciar:

```bash
systemctl start wg-quick@wg0
```

Guarda una copia cifrada de `/etc/wireguard/wg0.conf`. No necesitas respaldar el perfil del teléfono si conservas una forma segura de generar uno nuevo.

## 7. Revocar el teléfono

La versión inicial del instalador administra un solo peer. La forma más simple de revocarlo y emitir nuevas claves es ejecutar de nuevo con `--force`; esto reemplaza `wg0.conf`, genera un perfil nuevo y hace que el antiguo deje de funcionar:

```bash
/root/setup-wireguard-vps.sh --endpoint 203.0.113.10 --force
```

Copia e importa el perfil recién generado. No uses el mismo perfil privado en varios teléfonos.

## Limitaciones

WireGuard puede cambiar de Wi‑Fi a datos móviles sin renegociar una sesión tradicional, pero requiere que alguna red transporte UDP hasta el VPS. No evita apagones de servicio ni obliga al servidor del juego a aceptar un cambio de IP/ruta. Un puerto distinto puede ayudar frente a una política puntual, pero WireGuard no pretende ocultar que usa UDP y no se promete evasión de bloqueos.
