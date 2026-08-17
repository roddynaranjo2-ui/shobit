#!/usr/bin/env bash
set -Eeuo pipefail
umask 077

WG_INTERFACE="wg0"
WG_PORT="51820"
WG_SERVER_ADDRESS="10.66.66.1/24"
CLIENT_ADDRESS="10.66.66.2/32"
CLIENT_NAME="arena-cuba"
PUBLIC_ENDPOINT=""
DNS_SERVERS="1.1.1.1, 1.0.0.1"
MTU="1280"
FORCE=0

usage() {
  cat <<'USAGE'
Instala WireGuard en un VPS Ubuntu y genera un perfil para Nexo Game Tunnel.

Uso (como root):
  ./setup-wireguard-vps.sh --endpoint IP_PUBLICA [opciones]

Opciones:
  --endpoint HOST       IPv4 pública o dominio del VPS (obligatorio)
  --port PUERTO         Puerto UDP (predeterminado: 51820)
  --client-name NOMBRE  Nombre seguro del perfil (predeterminado: arena-cuba)
  --dns LISTA           DNS del cliente (predeterminado: 1.1.1.1, 1.0.0.1)
  --mtu NUMERO          MTU del túnel (predeterminado: 1280)
  --force               Reemplaza una configuración wg0 existente
  --help                 Muestra esta ayuda

Este instalador configura IPv4. El perfil enruta 0.0.0.0/0 solo para la app
seleccionada en Nexo; Android bloquea IPv6 para esa app para evitar una fuga.
USAGE
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --endpoint) PUBLIC_ENDPOINT="${2:-}"; shift 2 ;;
    --port) WG_PORT="${2:-}"; shift 2 ;;
    --client-name) CLIENT_NAME="${2:-}"; shift 2 ;;
    --dns) DNS_SERVERS="${2:-}"; shift 2 ;;
    --mtu) MTU="${2:-}"; shift 2 ;;
    --force) FORCE=1; shift ;;
    --help|-h) usage; exit 0 ;;
    *) echo "Opción desconocida: $1" >&2; usage >&2; exit 2 ;;
  esac
done

if [[ $EUID -ne 0 ]]; then
  echo "Ejecuta este script como root (sudo -i)." >&2
  exit 1
fi
if [[ -z "$PUBLIC_ENDPOINT" ]]; then
  echo "Falta --endpoint con la IPv4 pública o dominio del VPS." >&2
  exit 2
fi
if ! [[ "$PUBLIC_ENDPOINT" =~ ^[A-Za-z0-9.-]+$ ]]; then
  echo "Endpoint inválido. Usa una IPv4 pública o un dominio, sin puerto." >&2
  exit 2
fi
if ! [[ "$DNS_SERVERS" =~ ^[0-9A-Fa-f:.,[:space:]]+$ ]]; then
  echo "Lista DNS inválida." >&2
  exit 2
fi
if ! [[ "$WG_PORT" =~ ^[0-9]+$ ]] || (( WG_PORT < 1 || WG_PORT > 65535 )); then
  echo "Puerto UDP inválido: $WG_PORT" >&2
  exit 2
fi
if ! [[ "$MTU" =~ ^[0-9]+$ ]] || (( MTU < 576 || MTU > 1500 )); then
  echo "MTU inválido: $MTU" >&2
  exit 2
fi
if ! [[ "$CLIENT_NAME" =~ ^[a-zA-Z0-9_-]{1,32}$ ]]; then
  echo "El nombre de cliente solo admite letras, números, _ y - (máximo 32)." >&2
  exit 2
fi
if [[ ! -r /etc/os-release ]]; then
  echo "No se pudo detectar el sistema. Este script requiere Ubuntu." >&2
  exit 1
fi
# shellcheck disable=SC1091
source /etc/os-release
if [[ "${ID:-}" != "ubuntu" ]]; then
  echo "Sistema no compatible: ${PRETTY_NAME:-desconocido}. Usa Ubuntu 22.04/24.04." >&2
  exit 1
fi

WG_CONFIG="/etc/wireguard/${WG_INTERFACE}.conf"
CLIENT_DIR="/root/nexo-clients"
CLIENT_CONFIG="${CLIENT_DIR}/${CLIENT_NAME}.conf"
if [[ -e "$WG_CONFIG" && $FORCE -ne 1 ]]; then
  echo "$WG_CONFIG ya existe. No se modificó. Usa --force solo si deseas reemplazarlo." >&2
  exit 1
fi

export DEBIAN_FRONTEND=noninteractive
apt-get update
apt-get install -y --no-install-recommends wireguard-tools iptables ca-certificates

DEFAULT_INTERFACE="$(ip -4 route list default | awk 'NR==1 {print $5}')"
if [[ -z "$DEFAULT_INTERFACE" ]]; then
  echo "No se encontró la interfaz IPv4 de salida." >&2
  exit 1
fi

install -d -m 700 /etc/wireguard "$CLIENT_DIR"
SERVER_PRIVATE_KEY="$(wg genkey)"
SERVER_PUBLIC_KEY="$(printf '%s' "$SERVER_PRIVATE_KEY" | wg pubkey)"
CLIENT_PRIVATE_KEY="$(wg genkey)"
CLIENT_PUBLIC_KEY="$(printf '%s' "$CLIENT_PRIVATE_KEY" | wg pubkey)"
PRESHARED_KEY="$(wg genpsk)"

cat > "${WG_CONFIG}.new" <<EOF
[Interface]
Address = ${WG_SERVER_ADDRESS}
ListenPort = ${WG_PORT}
PrivateKey = ${SERVER_PRIVATE_KEY}
PostUp = iptables -I FORWARD 1 -i %i -j ACCEPT; iptables -I FORWARD 1 -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -A POSTROUTING -o ${DEFAULT_INTERFACE} -j MASQUERADE
PostDown = iptables -D FORWARD -i %i -j ACCEPT; iptables -D FORWARD -o %i -m conntrack --ctstate RELATED,ESTABLISHED -j ACCEPT; iptables -t nat -D POSTROUTING -o ${DEFAULT_INTERFACE} -j MASQUERADE

[Peer]
# ${CLIENT_NAME}
PublicKey = ${CLIENT_PUBLIC_KEY}
PresharedKey = ${PRESHARED_KEY}
AllowedIPs = ${CLIENT_ADDRESS}
EOF
chmod 600 "${WG_CONFIG}.new"

cat > "${CLIENT_CONFIG}.new" <<EOF
[Interface]
PrivateKey = ${CLIENT_PRIVATE_KEY}
Address = ${CLIENT_ADDRESS}
DNS = ${DNS_SERVERS}
MTU = ${MTU}

[Peer]
PublicKey = ${SERVER_PUBLIC_KEY}
PresharedKey = ${PRESHARED_KEY}
Endpoint = ${PUBLIC_ENDPOINT}:${WG_PORT}
AllowedIPs = 0.0.0.0/0
PersistentKeepalive = 25
EOF
chmod 600 "${CLIENT_CONFIG}.new"

# Back up both old configurations before replacing either one. Nanoseconds prevent a rapid
# second --force invocation from overwriting the previous backup on supported Ubuntu hosts.
BACKUP_SUFFIX="$(date -u +%Y%m%dT%H%M%S%NZ)"
if [[ -e "$WG_CONFIG" ]]; then
  install -m 600 "$WG_CONFIG" "${WG_CONFIG}.backup-${BACKUP_SUFFIX}"
  echo "Respaldo del servidor: ${WG_CONFIG}.backup-${BACKUP_SUFFIX}"
fi
if [[ -e "$CLIENT_CONFIG" ]]; then
  install -m 600 "$CLIENT_CONFIG" "${CLIENT_CONFIG}.backup-${BACKUP_SUFFIX}"
  echo "Respaldo del cliente: ${CLIENT_CONFIG}.backup-${BACKUP_SUFFIX}"
fi
mv -f "${WG_CONFIG}.new" "$WG_CONFIG"
mv -f "${CLIENT_CONFIG}.new" "$CLIENT_CONFIG"

cat > /etc/sysctl.d/99-nexo-wireguard.conf <<'EOF'
net.ipv4.ip_forward = 1
EOF
sysctl --system >/dev/null

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q '^Status: active'; then
  ufw allow "${WG_PORT}/udp" comment 'Nexo WireGuard'
fi

systemctl enable "wg-quick@${WG_INTERFACE}"
if systemctl is-active --quiet "wg-quick@${WG_INTERFACE}"; then
  systemctl restart "wg-quick@${WG_INTERFACE}"
else
  systemctl start "wg-quick@${WG_INTERFACE}"
fi

cat <<EOF

Nexo WireGuard quedó activo.

  Interfaz:      ${WG_INTERFACE}
  Puerto UDP:    ${WG_PORT}
  Salida:        ${DEFAULT_INTERFACE}
  Perfil móvil:  ${CLIENT_CONFIG}

Copia el perfil sin mostrar su clave en el historial:
  scp root@${PUBLIC_ENDPOINT}:${CLIENT_CONFIG} ./

Después impórtalo en Nexo Game Tunnel y selecciona Arena Breakout Global.
Asegúrate de permitir UDP/${WG_PORT} también en el firewall del proveedor del VPS.

Comprobación en el VPS:
  wg show ${WG_INTERFACE}
  systemctl status wg-quick@${WG_INTERFACE} --no-pager
EOF
