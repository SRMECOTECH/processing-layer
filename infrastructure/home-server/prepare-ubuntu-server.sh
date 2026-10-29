#!/bin/sh
# One-time preparation of the home server after a fresh Ubuntu Server 24.04 LTS install (docs/HOME_SERVER_SETUP.md):
#   1. Docker Engine + the compose plugin, from Docker's own apt repository
#   2. A laptop that behaves like a server: keeps running with the lid closed, never suspends
#   3. Firewall: SSH only from the home network
# Safe to run again. Read it before running it - it changes system settings.
#
#   sudo sh infrastructure/home-server/prepare-ubuntu-server.sh 192.168.1.0/24      (your home network)
set -eu

LAN="${1:?usage: sudo sh $0 <home network, e.g. 192.168.1.0/24>}"
[ "$(id -u)" -eq 0 ] || { echo "Run it with sudo." >&2; exit 1; }

echo "== 1. Docker"
apt-get update
apt-get install -y ca-certificates curl
install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
chmod a+r /etc/apt/keyrings/docker.asc
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu $(. /etc/os-release && echo "$VERSION_CODENAME") stable" \
    > /etc/apt/sources.list.d/docker.list
apt-get update
apt-get install -y docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
systemctl enable --now docker
if [ -n "${SUDO_USER:-}" ]; then
    usermod -aG docker "$SUDO_USER"      # docker without sudo, from the next login
fi

echo "== 2. Always on"
sed -i -E 's/^#?HandleLidSwitch=.*/HandleLidSwitch=ignore/; s/^#?HandleLidSwitchExternalPower=.*/HandleLidSwitchExternalPower=ignore/; s/^#?HandleLidSwitchDocked=.*/HandleLidSwitchDocked=ignore/' \
    /etc/systemd/logind.conf
systemctl mask sleep.target suspend.target hibernate.target hybrid-sleep.target

echo "== 3. Firewall (SSH from $LAN only)"
apt-get install -y ufw
ufw allow from "$LAN" to any port 22 proto tcp
ufw --force enable
# Note: ports published by Docker (8989, 8090) do not go through ufw - Docker writes its own iptables rules.
# They are reachable from the home network only because the router does not forward them: never add a port
# forward for them; use Tailscale for access from outside (docs/HOME_SERVER_SETUP.md).

echo
echo "Done. Reboot once (sudo reboot) so the lid setting and the docker group take effect."
