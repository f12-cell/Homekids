import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))
from fastapi import FastAPI, Request, HTTPException, Depends, BackgroundTasks, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from contextlib import contextmanager
import sqlite3
import datetime
import os
import socket
import uuid
import secrets
import traceback

from config import DB
import schema
import ai_engine

app = FastAPI(title="HomeKids - Phase 1 Backend")
app.add_middleware(CORSMiddleware, allow_origins=['*'], allow_methods=['*'], allow_headers=['*'])

APP_ALIASES = {
    "tiktok.com": ["tiktok.com", "tiktokv.com", "tiktokcdn.com", "muscdn.com", "byteoversea.com", "ibytedtos.com"],
    "youtube.com": ["youtube.com", "googlevideo.com", "ytimg.com", "youtubei.googleapis.com", "youtu.be"],
    "instagram.com": ["instagram.com", "cdninstagram.com"],
    "facebook.com": ["facebook.com", "fbcdn.net", "fb.com", "messenger.com"],
    "snapchat.com": ["snapchat.com", "sc-static.net", "snapads.com"],
    "roblox.com": ["roblox.com", "rbxcdn.com", "rbxmgr.com"],
    "whatsapp.com": ["whatsapp.com", "whatsapp.net", "wa.me"],
    "netflix.com": ["netflix.com", "nflxext.com", "nflxvideo.net"],
    "twitter.com": ["twitter.com", "twimg.com", "t.co", "x.com"],
    "discord.com": ["discord.com", "discordapp.com", "discord.gg", "discord.media"],
    "twitch.tv": ["twitch.tv", "ttvnw.net", "jtvnw.net"],
    "steampowered.com": ["steampowered.com", "steamcommunity.com", "steamstatic.com"]
}

@contextmanager
def get_db():
    conn = sqlite3.connect(str(DB))
    conn.row_factory = sqlite3.Row
    try: yield conn
    finally: conn.close()

# Start DB and perform cleanup
with sqlite3.connect(str(DB)) as conn:
    schema.init(conn)
    # Phase 1 Privacy: Auto-delete logs older than 30 days
    conn.execute("DELETE FROM dns_log WHERE ts < datetime('now', '-30 days')")
    conn.commit()

# ── AUTHENTICATION ────────────────────────────────────────
async def verify_device(request: Request):
    # Phase 1: Each device uses a unique API Key
    api_key = request.headers.get("X-HomeKids-Key")
    if not api_key: return None
    with get_db() as conn:
        row = conn.execute("SELECT device_uuid, child_name FROM devices WHERE api_key = ?", (api_key,)).fetchone()
        if row: return {"uuid": row['device_uuid'], "child": row['child_name']}
    return None

# ── REGISTRATION ──────────────────────────────────────────
@app.post('/api/register')
def register_device(
    request: Request,
    child_name: str = Query(...),
    device_name: str = Query(""),
    mac: str = Query("")
):
    ip = request.client.host
    name = child_name.strip()

    # Phase 1: Strong Identity (UUID + API Key)
    new_uuid = str(uuid.uuid4())
    api_key = secrets.token_urlsafe(32)

    with get_db() as conn:
        conn.execute("DELETE FROM devices WHERE ip = ?", (ip,))
        conn.execute("""
            INSERT INTO devices (mac, ip, device_uuid, api_key, child_name, last_seen, provisioned)
            VALUES (?, ?, ?, ?, ?, datetime('now'), 1)
        """, (f"p1:{ip}", ip, new_uuid, api_key, name))
        conn.execute("INSERT OR IGNORE INTO profiles (child_name) VALUES (?)", (name,))
        conn.execute("INSERT INTO audit_log (action, new_value) VALUES (?, ?)",
                    ("Device Registered", f"{name} ({ip})"))
        conn.commit()

    return {'registered': True, 'name': name, 'ip': ip, 'uuid': new_uuid, 'api_key': api_key}

# ── ACTIVITY LOGGING ──────────────────────────────────────
@app.post('/api/activity')
async def upload_activity(
    request: Request,
    background_tasks: BackgroundTasks,
    device: dict = Depends(verify_device),
    child_name: str = Query(None)
):
    try:
        entries = await request.json()
        if not isinstance(entries, list): entries = [entries]
        ip = request.client.host

        target_child = None
        if child_name and child_name.strip():
            target_child = child_name.strip()
        elif device and device.get("child"):
            target_child = device["child"]
        else:
            with get_db() as conn:
                row = conn.execute("SELECT child_name FROM devices WHERE ip = ?", (ip,)).fetchone()
                if row and row['child_name']:
                    target_child = row['child_name']
                else:
                    single = conn.execute("SELECT child_name FROM devices ORDER BY last_seen DESC LIMIT 1").fetchone()
                    if single and single['child_name']:
                        target_child = single['child_name']
                    else:
                        target_child = f"Unknown-{ip}"

        device_uuid = device["uuid"] if device else f"temp-{ip}"

        with get_db() as conn:
            for e in entries:
                conn.execute('''INSERT INTO dns_log (ip, child, domain, app, ts, status, rule_matched)
                                VALUES (?,?,?,?,?,?,?)''',
                    (ip, target_child, e.get('domain'), e.get('app'),
                     e.get('timestamp') or e.get('time') or datetime.datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S'),
                     e.get('status', 'allowed'), e.get('rule', 'None')))
            conn.execute("UPDATE devices SET last_seen = datetime('now') WHERE LOWER(TRIM(child_name)) = LOWER(TRIM(?)) OR ip = ?", (target_child, ip))
            conn.commit()

        # Trigger AI Anomaly Detection
        background_tasks.add_task(ai_engine.analyze_activity_risk, target_child, device_uuid, entries)
        return {'saved': len(entries)}
    except Exception as e: return {'error': str(e)}

@app.get('/api/activity/{child}')
def get_activity(child: str, limit: int = 500):
    with get_db() as conn:
        clean_child = child.strip()
        if clean_child.lower() == 'all':
            rows = conn.execute("SELECT ip, child, domain, app, ts, status FROM dns_log ORDER BY id DESC LIMIT ?", (limit,)).fetchall()
        else:
            rows = conn.execute("SELECT ip, child, domain, app, ts, status FROM dns_log WHERE LOWER(TRIM(child)) = LOWER(TRIM(?)) ORDER BY id DESC LIMIT ?", (clean_child, limit)).fetchall()

            if not rows:
                dev_row = conn.execute("SELECT ip FROM devices WHERE LOWER(TRIM(child_name)) = LOWER(TRIM(?))", (clean_child,)).fetchone()
                if dev_row:
                    rows = conn.execute("SELECT ip, child, domain, app, ts, status FROM dns_log WHERE ip = ? ORDER BY id DESC LIMIT ?", (dev_row['ip'], limit)).fetchall()
                if not rows:
                    rows = conn.execute("SELECT ip, child, domain, app, ts, status FROM dns_log ORDER BY id DESC LIMIT ?", (limit,)).fetchall()

        return [
            {
                "ip": r['ip'],
                "child": r['child'],
                "domain": r['domain'],
                "app": r['app'],
                "ts": r['ts'],
                "time": r['ts'],
                "timestamp": r['ts'],
                "status": r['status']
            }
            for r in rows
        ]

@app.get('/api/stats/{child}')
def get_stats(child: str):
    with get_db() as conn:
        clean_child = child.strip()
        rows = conn.execute("SELECT app, COUNT(*) as count FROM dns_log WHERE LOWER(TRIM(child)) = LOWER(TRIM(?)) GROUP BY app ORDER BY count DESC", (clean_child,)).fetchall()
        if not rows:
            rows = conn.execute("SELECT app, COUNT(*) as count FROM dns_log GROUP BY app ORDER BY count DESC").fetchall()
        return [{"app": r['app'], "count": r['count']} for r in rows]

# ── POLICY & RULES ────────────────────────────────────────
@app.get('/api/rules')
def get_rules(request: Request, device_ip: str = '', device: dict = Depends(verify_device)):
    ip = device_ip or request.client.host
    child = device["child"] if device else None

    with get_db() as conn:
        if not child:
            row = conn.execute("SELECT child_name FROM devices WHERE ip = ?", (ip,)).fetchone()
            child = row['child_name'] if row else None
        if not child:
            print(f"[rules] No registered device for ip={ip}, api_key_present={bool(request.headers.get('X-HomeKids-Key'))}")
            raise HTTPException(status_code=401, detail="Device is not registered")

        # 1. Profile Checks (Pause Internet)
        paused = 0
        if child:
            p = conn.execute("SELECT internet_paused FROM profiles WHERE child_name = ?", (child,)).fetchone()
            paused = p['internet_paused'] if p else 0

        if paused: return {'blocked_domains': ['*'], 'reason': 'Internet Paused by Parent'}

        # 2. Category Blocks
        rows = conn.execute('SELECT domain FROM blocked_sites').fetchall()
        base_rules = [r['domain'] for r in rows]

        expanded_rules = set()
        for r in base_rules:
            clean_r = r.strip().lower().replace("www.", "")
            expanded_rules.add(clean_r)

            # Check direct match or alias key match
            for base_app, aliases in APP_ALIASES.items():
                if clean_r == base_app or clean_r in aliases or base_app.split('.')[0] in clean_r:
                    for alias in aliases:
                        expanded_rules.add(alias)

        # 3. Automatic DoH Blocking (Bypass Resistance)
        final_rules = list(expanded_rules.union(set(ai_engine.DOH_PROVIDERS)))

    return {'blocked_domains': final_rules}

@app.get('/api/blocked')
def get_blocked_sites():
    with get_db() as conn:
        rows = conn.execute("SELECT domain, category, severity, added_at FROM blocked_sites ORDER BY id DESC").fetchall()
        return [{"domain": r['domain'], "category": r['category'], "severity": r['severity'], "added": r['added_at'], "ips": ""} for r in rows]

@app.post('/api/block')
async def block_site(request: Request, domain: str = Query(None)):
    target_domain = domain
    if not target_domain:
        try:
            data = await request.json()
            target_domain = data.get("domain")
        except: pass
    if not target_domain:
        return {"error": "Missing domain parameter"}

    clean_domain = target_domain.strip().lower().replace("https://", "").replace("http://", "").replace("www.", "").split("/")[0]
    with get_db() as conn:
        conn.execute("INSERT OR IGNORE INTO blocked_sites (domain) VALUES (?)", (clean_domain,))
        conn.commit()
    return {"blocked": clean_domain}

@app.delete('/api/block/{domain:path}')
def unblock_site(domain: str):
    clean_domain = domain.strip().lower().replace("https://", "").replace("http://", "").replace("www.", "").split("/")[0]
    with get_db() as conn:
        conn.execute("DELETE FROM blocked_sites WHERE LOWER(domain) = LOWER(?) OR LOWER(domain) = LOWER(?)", (clean_domain, f"www.{clean_domain}"))
        conn.commit()
    return {"unblocked": clean_domain}

# ── SECURITY & TAMPER ─────────────────────────────────────
@app.post('/api/security/tamper')
def report_tamper(
    request: Request,
    event_type: str = Query(""),
    detail: str = Query(""),
    device: dict = Depends(verify_device)
):
    ip = request.client.host
    child = device["child"] if device else "Unknown"
    uuid_val = device["uuid"] if device else "Unknown"
    print(f'[TAMPER] {ip} - {event_type}: {detail}')
    with get_db() as conn:
        conn.execute("""
            INSERT INTO security_events (device_uuid, child_name, event_type, severity, detail, ts)
            VALUES (?, ?, ?, ?, ?, datetime('now'))
        """, (uuid_val, child, event_type, "High", detail))
        try:
            conn.execute("""
                INSERT INTO alert_log (device_ip, event_type, detail, ts)
                VALUES (?, ?, ?, datetime('now'))
            """, (ip, event_type, detail))
        except: pass
        conn.commit()
    return {'received': True, 'logged': True}

@app.post('/api/heartbeat')
def heartbeat(request: Request, device: dict = Depends(verify_device)):
    ip = request.client.host
    with get_db() as conn:
        conn.execute("UPDATE devices SET last_seen = datetime('now') WHERE ip = ?", (ip,))
        conn.commit()
    return {"ok": True}

# ── PROFILES & SCHEDULE ───────────────────────────────────
@app.get('/api/profiles')
def get_profiles():
    with get_db() as conn:
        rows = conn.execute("SELECT child_name, avatar, daily_limit, internet_paused FROM profiles").fetchall()
        return [
            {
                "name": r['child_name'],
                "avatar": r['avatar'] or "🧒",
                "daily_limit": r['daily_limit'] if r['daily_limit'] is not None else 120,
                "internet_paused": bool(r['internet_paused'])
            }
            for r in rows
        ]

@app.post('/api/profiles/pause')
def pause_internet(child_name: str, paused: bool):
    with get_db() as conn:
        conn.execute("UPDATE profiles SET internet_paused = ? WHERE child_name = ?", (1 if paused else 0, child_name))
        conn.execute("INSERT INTO audit_log (action, new_value) VALUES (?, ?)",
                    ("Internet Pause Toggled", f"{child_name}: {paused}"))
        conn.commit()
    return {'child': child_name, 'paused': paused}

@app.post('/api/schedule')
def set_schedule(child: str, block_from: str, block_until: str, days: str = "Mon,Tue,Wed,Thu,Fri"):
    with get_db() as conn:
        conn.execute("INSERT INTO schedules (child_name, label, block_from, block_until, days) VALUES (?, ?, ?, ?, ?)",
                    (child, "Custom Schedule", block_from, block_until, days))
        conn.commit()
    return {"status": "saved"}

@app.put('/api/devices/{ip}/name')
def rename_device(ip: str, name: str):
    new_name = name.strip()
    with get_db() as conn:
        conn.execute("UPDATE devices SET child_name = ? WHERE ip = ?", (new_name, ip))
        conn.execute("INSERT OR IGNORE INTO profiles (child_name) VALUES (?)", (new_name,))
        conn.commit()
    return {"ip": ip, "name": new_name}

# ── DASHBOARD HELPERS ─────────────────────────────────────
@app.get('/api/connected')
def get_connected_devices():
    devices = []
    with get_db() as conn:
        rows = conn.execute('''
            SELECT d.ip, d.child_name, d.last_seen, d.tamper_status, p.internet_paused
            FROM devices d LEFT JOIN profiles p ON d.child_name = p.child_name
            ORDER BY d.last_seen DESC
        ''').fetchall()
        for r in rows:
            name = (r['child_name'] or "Unknown").strip()
            devices.append({
                'ip': r['ip'], 'name': name, 'icon': '📱',
                'seen': r['last_seen'], 'tamper': r['tamper_status'] or 'Healthy',
                'paused': bool(r['internet_paused'])
            })
    return devices

@app.get('/api/security/events')
def get_security_events(limit: int = 20):
    with get_db() as conn:
        rows = conn.execute("SELECT * FROM security_events ORDER BY ts DESC LIMIT ?", (limit,)).fetchall()
        return [dict(r) for r in rows]

if __name__ == "__main__":
    import uvicorn
    hostname = socket.gethostname()
    local_ip = socket.gethostbyname(hostname)
    print(f"\nHOMEKIDS PHASE 1 LIVE AT: http://{local_ip}:8000\n")
    uvicorn.run(app, host="0.0.0.0", port=8000)
