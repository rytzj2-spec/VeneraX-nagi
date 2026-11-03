# -*- coding: utf-8 -*-
# ============================================================
# Nagisaland 屏幕共享 · 牵线服务部署（装到小站那台 GCP 服务器上）
#
#   python deploy_nagi_share.py                     部署 / 更新（TURN 用 config.json 里现在的设置；第一次默认 cloudflare）
#   python deploy_nagi_share.py --turn cloudflare   用 Cloudflare Realtime TURN（Key ID / API Token 从 nagi_share_secrets.json 读，没有就问）
#   python deploy_nagi_share.py --turn coturn       在这台服务器上装 coturn 自建 TURN（turn.<域名> 要灰色云 + 防火墙开端口，见部署说明）
#   python deploy_nagi_share.py --turn none         不要 TURN（只能直连）
#   python deploy_nagi_share.py --rollback          还原上一次部署前的 Nginx / 服务配置
#   python deploy_nagi_share.py --status            只看状态
#
# 和小站的其它脚本一样：在 Windows 上运行，幂等，可重复跑；部署前先备份线上配置。
# 不动小站本身（/opt/galsite、galsite.service）；只在 Nginx 的 galsite 站点里加一段 /share/（用标记包起来）。
# 密钥：只写进服务器上的 /opt/nagi-share/config.json（权限 640），不进 Git，不打印。
# ============================================================
import argparse
import getpass
import io
import json
import os
import secrets
import sys
import time

import paramiko

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding='utf-8', errors='replace', line_buffering=True)

HERE     = os.path.dirname(os.path.abspath(__file__))
# 服务器信息不写在代码里（这个仓库是公开的）：放在同目录的 deploy_local.json（.gitignore 里），例如
#   {"host": "服务器外部 IP", "key": "C:\\key\\私钥文件", "domain": "你的域名"}
# 也可以用环境变量 GALSITE_HOST / GALSITE_KEY / GALSITE_DOMAIN 覆盖（IP 变了改这里就行）
LOCAL_FILE = os.path.join(HERE, 'deploy_local.json')
_local = {}
if os.path.exists(LOCAL_FILE):
    with open(LOCAL_FILE, encoding='utf-8') as _f:
        _local = json.load(_f)
HOST     = os.environ.get('GALSITE_HOST') or _local.get('host', '')
KEY      = os.environ.get('GALSITE_KEY') or _local.get('key', '')
DOMAIN   = os.environ.get('GALSITE_DOMAIN') or _local.get('domain', '')
TURN_HOST = 'turn.' + DOMAIN                                   # 自建 coturn 用的子域名（DNS 要灰色云，直连服务器 IP）
REMOTE   = '/opt/nagi-share'
NGINX_SITE = '/etc/nginx/sites-available/galsite'
SERVICE  = 'nagi-share'
PORT     = 8090
SECRETS_FILE = os.path.join(HERE, 'nagi_share_secrets.json')   # 本地密钥文件（.gitignore 里，不进 Git）

MARK_BEGIN = '    # >>> nagi-share（Nagisaland 屏幕共享，deploy_nagi_share.py 维护，别手改）'
MARK_END   = '    # <<< nagi-share'
NGINX_SNIPPET = f"""{MARK_BEGIN}
    location = /share {{ return 301 /share/; }}
    location = /share/ws {{
        proxy_pass http://127.0.0.1:{PORT};
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }}
    location /share/ {{
        alias {REMOTE}/web/;
        index index.html;
        add_header Cache-Control "no-cache";
    }}
{MARK_END}
"""

UNIT = f"""[Unit]
Description=Nagisaland screen share signaling
After=network.target

[Service]
User=nagishare
Group=nagishare
WorkingDirectory={REMOTE}
ExecStart={REMOTE}/venv/bin/python3 {REMOTE}/nagi_share.py --config {REMOTE}/config.json
Restart=always
RestartSec=3
MemoryMax=80M
NoNewPrivileges=true
ProtectSystem=full
PrivateTmp=true

[Install]
WantedBy=multi-user.target
"""

# coturn：限速限并发，禁止转发到内网（防止被当跳板）；external-ip 每次启动由 coturn-ip.sh 从 GCP 元数据刷新（IP 会变）
TURN_CONF = """# Nagisaland 屏幕共享 · 自建 TURN（deploy_nagi_share.py 生成）
listening-port=3478
fingerprint
use-auth-secret
static-auth-secret=@@SECRET@@
realm=@@REALM@@
external-ip=@@EXTIP@@
min-port=49160
max-port=49200
# 同时最多 12 个转发会话、每个用户 4 个；每个会话约 3 Mbps，总共约 12 Mbps（e2-micro 扛得住的量）
total-quota=12
user-quota=4
max-bps=375000
bps-capacity=1500000
stale-nonce=600
no-multicast-peers
no-cli
no-tlsv1
no-tlsv1_1
denied-peer-ip=0.0.0.0-0.255.255.255
denied-peer-ip=10.0.0.0-10.255.255.255
denied-peer-ip=100.64.0.0-100.127.255.255
denied-peer-ip=127.0.0.0-127.255.255.255
denied-peer-ip=169.254.0.0-169.254.255.255
denied-peer-ip=172.16.0.0-172.31.255.255
denied-peer-ip=192.168.0.0-192.168.255.255
denied-peer-ip=::1
denied-peer-ip=fc00::-fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff
denied-peer-ip=fe80::-febf:ffff:ffff:ffff:ffff:ffff:ffff:ffff
syslog
simple-log
"""

COTURN_IP_SH = r"""#!/bin/sh
# coturn 启动前：从 GCP 元数据拿当前的外部 / 内部 IP，写进 /etc/turnserver.conf（服务器 IP 会变）
MD=http://metadata.google.internal/computeMetadata/v1/instance/network-interfaces/0
EXT=$(curl -s -m 5 -H "Metadata-Flavor: Google" $MD/access-configs/0/external-ip)
INT=$(curl -s -m 5 -H "Metadata-Flavor: Google" $MD/ip)
if [ -n "$EXT" ] && [ -n "$INT" ]; then
  sed -i "s#^external-ip=.*#external-ip=$EXT/$INT#" /etc/turnserver.conf
fi
exit 0
"""


def connect():
    if not HOST or not KEY or not DOMAIN:
        raise SystemExit("❌ 先在 %s 写好服务器信息：\n"
                         '   {"host": "服务器外部 IP", "key": "私钥文件路径", "domain": "你的域名"}\n'
                         "   （这个文件不进 Git）" % LOCAL_FILE)
    print("连接 GCP %s ..." % HOST)
    key = paramiko.Ed25519Key.from_private_key_file(KEY)
    c = paramiko.SSHClient()
    c.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    c.connect(HOST, username='root', pkey=key, timeout=30)
    return c


def make_run(client):
    def run(cmd, show=True, timeout=600):
        _, out, err = client.exec_command(cmd, timeout=timeout)
        o = out.read().decode('utf-8', 'replace').strip()
        e = err.read().decode('utf-8', 'replace').strip()
        code = out.channel.recv_exit_status()
        if show and o:
            print(o)
        if show and e and 'CLIXML' not in e:
            print("ERR:", e[-800:])
        return o, e, code
    return run


def put_text(sftp, path, text, mode=None):
    with sftp.open(path, 'w') as f:
        f.write(text)
    if mode is not None:
        sftp.chmod(path, mode)


def load_secrets():
    if os.path.exists(SECRETS_FILE):
        with open(SECRETS_FILE, encoding='utf-8') as f:
            return json.load(f)
    return {}


def ask_cloudflare(existing):
    s = load_secrets()
    kid = s.get('cloudflare_key_id') or existing.get('key_id') or ''
    tok = s.get('cloudflare_api_token') or existing.get('api_token') or ''
    if kid.startswith('在 ') or not kid:
        kid = input("Cloudflare TURN Key ID：").strip()
    if tok.startswith('同上') or not tok:
        tok = getpass.getpass("Cloudflare TURN API Token（输入时不显示）：").strip()
    if not kid or not tok:
        raise SystemExit("❌ 没有 Cloudflare 的 Key ID / API Token")
    return {'key_id': kid, 'api_token': tok}


def nginx_patch(conf):
    """在 galsite 站点的 `location / {` 前面插入（已有标记就替换）"""
    if MARK_BEGIN in conf and MARK_END in conf:
        a = conf.index(MARK_BEGIN)
        b = conf.index(MARK_END) + len(MARK_END)
        if conf[b:b + 1] == '\n':
            b += 1
        return conf[:a] + NGINX_SNIPPET + conf[b:]
    anchor = '    location / {'
    if anchor not in conf:
        raise RuntimeError("Nginx 配置里没找到 `location / {`，不敢自动改（请把 %s 发给云端看看）" % NGINX_SITE)
    i = conf.index(anchor)
    return conf[:i] + NGINX_SNIPPET + conf[i:]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--turn', choices=['cloudflare', 'coturn', 'none'])
    ap.add_argument('--rollback', action='store_true')
    ap.add_argument('--status', action='store_true')
    a = ap.parse_args()

    client = connect()
    run = make_run(client)
    sftp = client.open_sftp()

    if a.status:
        run(f"systemctl is-active {SERVICE} coturn 2>&1; journalctl -u {SERVICE} -n 15 --no-pager 2>&1 | tail -15")
        run("curl -s -o /dev/null -w '页面 /share/: HTTP %{http_code}\\n' -H 'Host: " + DOMAIN + "' http://127.0.0.1/share/")
        return

    if a.rollback:
        o, _, _ = run(f"ls -1d {REMOTE}/_bak_* 2>/dev/null | tail -1", show=False)
        if not o:
            raise SystemExit("没有备份可还原")
        print("还原 %s ..." % o)
        run(f"cp -a {o}/galsite.nginx {NGINX_SITE} && nginx -t && systemctl reload nginx && echo Nginx 已还原")
        run(f"test -f {o}/config.json && cp -a {o}/config.json {REMOTE}/config.json; "
            f"test -f {o}/turnserver.conf && cp -a {o}/turnserver.conf /etc/turnserver.conf; "
            f"systemctl restart {SERVICE} 2>/dev/null; echo 完成")
        return

    stamp = time.strftime('%Y%m%d_%H%M%S')
    bak = f"{REMOTE}/_bak_{stamp}"

    # ---- 1. 备份 ----
    print("\n[1/7] 备份线上配置 -> %s" % bak)
    run(f"mkdir -p {bak} && cp -a {NGINX_SITE} {bak}/galsite.nginx "
        f"&& (cp -a {REMOTE}/config.json {bak}/ 2>/dev/null; cp -a {REMOTE}/nagi_share.py {bak}/ 2>/dev/null; "
        f"cp -a /etc/turnserver.conf {bak}/ 2>/dev/null; true) && echo 备份完成")
    run(f"ls -1d {REMOTE}/_bak_* | head -n -5 | xargs -r rm -rf")   # 只留最近 5 份

    # ---- 2. 上传代码 ----
    print("\n[2/7] 上传程序和观看页面 ...")
    run(f"id nagishare >/dev/null 2>&1 || useradd --system --no-create-home --shell /usr/sbin/nologin nagishare; "
        f"mkdir -p {REMOTE}/web")
    for local, remote in [('nagi_share.py', 'nagi_share.py'), ('requirements.txt', 'requirements.txt'),
                          (os.path.join('web', 'index.html'), 'web/index.html')]:
        print("   上传 %s" % local)
        sftp.put(os.path.join(HERE, local), f"{REMOTE}/{remote}")

    # ---- 3. 依赖 ----
    print("\n[3/7] Python 虚拟环境 + 依赖 ...")
    o, e, code = run(f"cd {REMOTE} && (test -x venv/bin/python3 || python3 -m venv venv) "
                     f"&& ./venv/bin/pip install -q -r requirements.txt && echo 依赖就绪", timeout=900)
    if code != 0:
        raise SystemExit("❌ 依赖安装失败（Ubuntu 上可能要先 apt install python3-venv）")

    # ---- 4. 配置 ----
    print("\n[4/7] 生成 / 更新 config.json（密钥不显示）...")
    cfg = {}
    try:
        with sftp.open(f"{REMOTE}/config.json") as f:
            cfg = json.loads(f.read().decode('utf-8'))
    except IOError:
        with open(os.path.join(HERE, 'config.example.json'), encoding='utf-8') as f:
            cfg = json.load(f)
        cfg.pop('_说明', None)
    turn = a.turn or cfg.get('turn') or 'cloudflare'
    cfg['turn'] = turn
    cfg['listen_host'] = '127.0.0.1'
    cfg['listen_port'] = PORT
    if turn == 'cloudflare':
        cfg['cloudflare'] = ask_cloudflare(cfg.get('cloudflare') or {})
    if turn == 'coturn':
        ct = cfg.get('coturn') or {}
        if not ct.get('secret') or ct['secret'].startswith('部署脚本'):
            ct['secret'] = secrets.token_urlsafe(32)
        ct.setdefault('port', 3478)
        ct.setdefault('ttl', 43200)
        ct['host'] = TURN_HOST
        cfg['coturn'] = ct
    put_text(sftp, f"{REMOTE}/config.json", json.dumps(cfg, ensure_ascii=False, indent=2))
    run(f"chown -R root:nagishare {REMOTE} && chmod 640 {REMOTE}/config.json && chmod 755 {REMOTE} {REMOTE}/web "
        f"&& chmod 644 {REMOTE}/web/index.html {REMOTE}/nagi_share.py && echo 配置写好了（TURN={turn}）")

    # ---- 5. coturn（可选） ----
    if turn == 'coturn':
        print("\n[5/7] 安装 / 配置 coturn ...")
        run("command -v turnserver >/dev/null || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq coturn) "
            "&& echo coturn 已安装", timeout=900)
        conf = TURN_CONF.replace('@@SECRET@@', cfg['coturn']['secret']).replace('@@REALM@@', DOMAIN).replace('@@EXTIP@@', '0.0.0.0/0.0.0.0')
        put_text(sftp, '/etc/turnserver.conf', conf, 0o640)
        put_text(sftp, f"{REMOTE}/coturn-ip.sh", COTURN_IP_SH, 0o755)
        run("chown root:turnserver /etc/turnserver.conf 2>/dev/null; "
            "sed -i 's/^#\\?TURNSERVER_ENABLED=.*/TURNSERVER_ENABLED=1/' /etc/default/coturn 2>/dev/null; "
            "mkdir -p /etc/systemd/system/coturn.service.d && "
            f"printf '[Service]\\nExecStartPre=+{REMOTE}/coturn-ip.sh\\n' > /etc/systemd/system/coturn.service.d/nagi.conf && "
            "systemctl daemon-reload && systemctl enable coturn >/dev/null 2>&1; systemctl restart coturn; "
            "sleep 2; echo coturn: $(systemctl is-active coturn); grep ^external-ip /etc/turnserver.conf")
    else:
        print("\n[5/7] coturn：不用（TURN=%s）" % turn)

    # ---- 6. 服务 + Nginx ----
    print("\n[6/7] 注册 %s 服务、改 Nginx ..." % SERVICE)
    put_text(sftp, f"/etc/systemd/system/{SERVICE}.service", UNIT)
    run(f"systemctl daemon-reload && systemctl enable {SERVICE} >/dev/null 2>&1; systemctl restart {SERVICE}; "
        f"sleep 2; echo {SERVICE}: $(systemctl is-active {SERVICE})")
    with sftp.open(NGINX_SITE) as f:
        old = f.read().decode('utf-8')
    new = nginx_patch(old)
    if new != old:
        put_text(sftp, NGINX_SITE, new)
    o, e, code = run("nginx -t 2>&1 | tail -1", show=False)
    print("   nginx -t:", o or e)
    if code != 0 or 'successful' not in (o + e):
        print("   ❌ Nginx 配置检查没过，还原备份")
        run(f"cp -a {bak}/galsite.nginx {NGINX_SITE} && nginx -t && systemctl reload nginx")
        raise SystemExit(1)
    run("systemctl reload nginx && echo Nginx 已重新加载")

    # ---- 7. 验证 ----
    print("\n[7/7] 验证 ...")
    run("curl -s -o /dev/null -w '页面 /share/ : HTTP %{http_code}\\n' -H 'Host: " + DOMAIN + "' http://127.0.0.1/share/")
    run("curl -s -o /dev/null -w '小站首页    : HTTP %{http_code}\\n' -H 'Host: " + DOMAIN + "' http://127.0.0.1/")
    test = ("import asyncio,json,websockets\n"
            "async def m():\n"
            "  async with websockets.connect('ws://127.0.0.1/share/ws') as w:\n"
            "    await w.send(json.dumps({'t':'host','name':'deploy-test'}))\n"
            "    r=json.loads(await w.recv()); print('牵线测试：', r.get('t'), '房间', r.get('room'), "
            "'ICE 服务器', len(r.get('ice') or []), '个', '（含 TURN）' if any('turn' in str(s.get('urls')) for s in r.get('ice') or []) else '（没有 TURN）')\n"
            "asyncio.run(m())\n")
    put_text(sftp, '/tmp/nagi_share_test.py', test)
    run(f"{REMOTE}/venv/bin/python3 /tmp/nagi_share_test.py; rm -f /tmp/nagi_share_test.py")
    sftp.close()
    client.close()

    print("\n✅ 部署完成。")
    print("   观看页面：https://%s/share/" % DOMAIN)
    print("   App 里「一起看 → 共享我的屏幕 → 经服务器」的服务器地址填：https://%s/share/" % DOMAIN)
    if turn == 'coturn':
        print("   ⚠️ 自建 TURN 还要：① Cloudflare DNS 加 A 记录 %s → 服务器 IP，必须是灰色云（仅 DNS）；"
              "② GCP 防火墙放行 UDP+TCP 3478、UDP 49160-49200（见部署说明）" % TURN_HOST)
    print("   回滚：python deploy_nagi_share.py --rollback")


if __name__ == '__main__':
    main()
