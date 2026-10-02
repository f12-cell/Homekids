import math
import collections
import requests
import datetime
import sqlite3
from config import OLLAMA_BASE_URL, LLM_MODEL, DB

# ── BYPASS RESISTANCE DATA ────────────────────────────────
DOH_PROVIDERS = [
    "dns.google", "cloudflare-dns.com", "dns.quad9.net",
    "doh.opendns.com", "doh.cleanbrowsing.org", "dns.adguard.com"
]

VPN_DOMAINS = [
    "nordvpn.com", "expressvpn.com", "surfshark.com",
    "protonvpn.com", "windscribe.com", "hotspotshield.com",
    "tunnelbear.com", "mullvad.net", "ivpn.net"
]

def calculate_entropy(domain: str) -> float:
    """Detect random-looking domains used by malware or bypass tools."""
    if not domain or len(domain) < 5: return 0.0
    parts = domain.split('.')
    if len(parts) > 1: domain = parts[-2]
    counts = collections.Counter(domain)
    probs = [count / len(domain) for count in counts.values()]
    return -sum(p * math.log2(p) for p in probs)

async def generate_explanation(evidence: dict) -> str:
    """Use local AI to write a parent-friendly explanation."""
    prompt = f"""
    You are 'HomeKids AI', a security assistant for parents.
    Explain this network event to a parent in 2-3 friendly sentences.

    Data:
    - Child: {evidence.get('child_name')}
    - Type: {evidence.get('issue_type')}
    - Info: {evidence.get('details')}

    Focus on safety and privacy.
    """
    try:
        r = requests.post(f"{OLLAMA_BASE_URL}/api/generate",
                         json={"model": LLM_MODEL, "prompt": prompt, "stream": False},
                         timeout=8)
        if r.status_code == 200: return r.json().get('response', '').strip()
    except: pass
    return f"Security alert on {evidence.get('child_name')}'s device: {evidence.get('issue_type')}."

def get_rec(issue):
    return {
        "VPN_BYPASS": "Block VPN domains in settings and check for installed VPN apps.",
        "TAMPER": "Check if your child disabled the VPN in Android settings.",
        "MALWARE": "Scan the device for suspicious apps immediately.",
        "UNUSUAL": "Review activity logs for high-frequency connections."
    }.get(issue, "Continue monitoring.")

async def analyze_activity_risk(child_name, device_uuid, entries):
    """Phase 1: Advanced Anomaly Detection."""
    signals = []
    details = []

    # 1. VPN Detection
    v_hits = [e for e in entries if any(v in e.get('domain', '').lower() for v in VPN_DOMAINS)]
    if v_hits:
        signals.append("VPN_BYPASS")
        details.append(f"Detected VPN related domains: {v_hits[0]['domain']}")

    # 2. DoH Detection
    d_hits = [e for e in entries if e.get('domain') in DOH_PROVIDERS]
    if d_hits:
        signals.append("VPN_BYPASS")
        details.append(f"Attempted to use encrypted DNS: {d_hits[0]['domain']}")

    # 3. Burst Detection (Rate Limiting check)
    if len(entries) > 50:
        signals.append("UNUSUAL")
        details.append(f"High activity burst: {len(entries)} requests in small window.")

    if signals:
        issue = signals[0]
        ev_str = " | ".join(details)
        expl = await generate_explanation({"child_name": child_name, "issue_type": issue, "details": ev_str})

        with sqlite3.connect(str(DB)) as conn:
            conn.execute("""
                INSERT INTO security_events (device_uuid, child_name, event_type, severity, detail, explanation, recommendation)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """, (device_uuid, child_name, issue, "High", ev_str, expl, get_rec(issue)))
            conn.commit()
