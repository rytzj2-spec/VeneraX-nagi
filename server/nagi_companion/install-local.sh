#!/usr/bin/env bash
# 在你自己通过 SSH 打开的服务器终端运行；本脚本不会远程登录服务器。
set -euo pipefail
if [ "$(id -u)" -ne 0 ]; then echo '请使用 sudo bash install-local.sh'; exit 1; fi
companion_source="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
companion_stamp="$(date -u +%Y%m%dT%H%M%SZ)"
if ! id nagi-companion >/dev/null 2>&1; then useradd --system --home /var/lib/nagi-companion --shell /usr/sbin/nologin nagi-companion; fi
install -d -m 755 /opt/nagi-companion
install -d -m 700 -o nagi-companion -g nagi-companion /var/lib/nagi-companion
if [ -f /opt/nagi-companion/app.py ]; then
    install -d -m 700 "/opt/nagi-companion-backups/$companion_stamp"
    cp /opt/nagi-companion/app.py /opt/nagi-companion/requirements.txt "/opt/nagi-companion-backups/$companion_stamp/"
fi
install -m 644 "$companion_source/app.py" "$companion_source/requirements.txt" /opt/nagi-companion/
if [ ! -f /etc/nagi-companion.env ]; then
    (umask 077; python3 -c 'import secrets; print("NAGI_COMPANION_SETUP_KEY=" + secrets.token_urlsafe(32))' > /etc/nagi-companion.env)
fi
python3 -m venv /opt/nagi-companion/venv
/opt/nagi-companion/venv/bin/pip install -r /opt/nagi-companion/requirements.txt
if [ -f /etc/systemd/system/nagi-companion.service ]; then
    cp /etc/systemd/system/nagi-companion.service "/etc/systemd/system/nagi-companion.service.bak.$companion_stamp"
fi
install -m 644 "$companion_source/nagi-companion.service" /etc/systemd/system/nagi-companion.service
systemctl daemon-reload
systemctl enable nagi-companion
systemctl restart nagi-companion
systemctl is-active nagi-companion
echo '独立服务已启动在本机 8091。请按文档配置 HTTPS 反向代理；没有修改现有屏幕共享服务。'
