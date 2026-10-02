import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import socket
import sqlite3
import datetime
import os
from dnslib import DNSRecord, QTYPE, RR, A
from config import DB

PORT = 53
LISTEN_IP = os.getenv('HOMEKIDS_DNS_LISTEN_IP', '0.0.0.0')
UPSTREAM_DNS = os.getenv('HOMEKIDS_UPSTREAM_DNS', '8.8.8.8')

def get_blocked_domains():
    with sqlite3.connect(str(DB)) as conn:
        rows = conn.execute("SELECT domain FROM blocked_sites").fetchall()
        return {r[0].lower() for r in rows}

def get_curfewed_ips():
    with sqlite3.connect(str(DB)) as conn:
        rows = conn.execute("SELECT device_ip FROM active_curfews").fetchall()
        return {r[0] for r in rows}

def resolve_dns(data, addr):
    ip = addr[0]
    request = DNSRecord.parse(data)
    domain = str(request.q.qname).rstrip('.').lower()

    blocked = get_blocked_domains()
    curfewed = get_curfewed_ips()

    is_blocked = False
    reason = "allowed"

    if ip in curfewed:
        is_blocked = True
        reason = "curfew"
    elif any(domain == b or domain.endswith("." + b) for b in blocked):
        is_blocked = True
        reason = "policy"

    if is_blocked:
        print(f"[dns] BLOCK {ip} -> {domain} ({reason})")
        reply = request.reply()
        reply.add_answer(RR(request.q.qname, QTYPE.A, rdata=A("0.0.0.0"), ttl=60))
        return reply.pack()

    # Proxy to upstream
    try:
        sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        sock.settimeout(2)
        sock.sendto(data, (UPSTREAM_DNS, 53))
        response, _ = sock.recvfrom(2048)
        return response
    except:
        return None

def main():
    print(f"[dns] Sinkhole DNS starting on {LISTEN_IP}:{PORT}...")
    server = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        server.bind((LISTEN_IP, PORT))
    except Exception as e:
        print(f"[dns] Error binding to {LISTEN_IP}: {e}")
        return

    while True:
        try:
            data, addr = server.recvfrom(1024)
            response = resolve_dns(data, addr)
            if response:
                server.sendto(response, addr)
        except Exception as e:
            print(f"[dns] Runtime error: {e}")

if __name__ == '__main__':
    main()
