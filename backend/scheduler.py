"""
HomeKids curfew scheduler.

Evaluates the `schedules` table every cycle and maintains `active_curfews` — the
set of device IPs that are currently inside a scheduled block window. dns_server.py
reads that table and returns NXDOMAIN for ALL queries from those IPs, cutting
internet for the child's devices during the curfew. This reuses the same DNS-
sinkhole mechanism that domain blocking uses (the one verified to actually reach
hotspot clients), rather than the unreliable forwarded-traffic firewall path.
"""
import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import sqlite3
import time
import datetime
from config import DB
import schema

CHECK_INTERVAL_SEC = 30
DAY_NAMES = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun']


def parse_days(days_str):
    """'Mon,Tue,Wed' -> {'Mon','Tue','Wed'}. Tolerant of spacing/case/long names."""
    if not days_str:
        return set()
    return {d.strip()[:3].title() for d in days_str.split(',') if d.strip()}


def is_curfew_active(now, block_from, block_until, days_str):
    """
    now: datetime. block_from/block_until: 'HH:MM' (zero-padded, so string
    comparison is chronological). days refer to the day the window STARTS.
    Handles windows that cross midnight (e.g. 22:00-06:00).
    """
    days = parse_days(days_str)
    if not days or not block_from or not block_until:
        return False
    cur = now.strftime('%H:%M')
    today = DAY_NAMES[now.weekday()]
    yesterday = DAY_NAMES[(now.weekday() - 1) % 7]
    if block_from <= block_until:
        # Same-day window, e.g. 09:00-17:00
        return today in days and block_from <= cur < block_until
    # Wraps midnight, e.g. 22:00-06:00: active late today OR early "next" day
    return (today in days and cur >= block_from) or \
           (yesterday in days and cur < block_until)


def child_ips(conn, child):
    """All device IPs belonging to a child: saved devices + recently-active IPs."""
    ips = set()
    for (ip,) in conn.execute("SELECT ip FROM devices WHERE child_name=?", (child,)):
        if ip:
            ips.add(ip)
    cutoff = (datetime.datetime.now() - datetime.timedelta(minutes=10)) \
        .strftime('%Y-%m-%d %H:%M:%S')
    for (ip,) in conn.execute(
            "SELECT DISTINCT ip FROM dns_log WHERE child=? AND ts > ?", (child, cutoff)):
        if ip:
            ips.add(ip)
    return ips


def compute_curfewed(conn, now):
    """Return {device_ip: child_name} for every device currently in a curfew window."""
    curfewed = {}
    for child, bfrom, buntil, days in conn.execute(
            "SELECT child_name, block_from, block_until, days FROM schedules"):
        if is_curfew_active(now, bfrom, buntil, days):
            for ip in child_ips(conn, child):
                curfewed[ip] = child
    return curfewed


def main():
    conn = sqlite3.connect(str(DB))
    schema.init(conn)  # ensure active_curfews (and the rest) exist
    print('[scheduler] Curfew scheduler started. Checking every %ds.' % CHECK_INTERVAL_SEC)
    prev = set()
    while True:
        try:
            now = datetime.datetime.now()
            curfewed = compute_curfewed(conn, now)
            ips = set(curfewed)

            conn.execute("DELETE FROM active_curfews")
            for ip, child in curfewed.items():
                conn.execute(
                    "INSERT OR REPLACE INTO active_curfews (device_ip, child_name, since) "
                    "VALUES (?,?,datetime('now'))", (ip, child))
            conn.commit()

            for ip in (ips - prev):
                print('[scheduler] CURFEW ON  %s (%s)' % (ip, curfewed[ip]))
            for ip in (prev - ips):
                print('[scheduler] curfew off %s' % ip)
            prev = ips
        except Exception as e:
            print('[scheduler] error:', e)
        time.sleep(CHECK_INTERVAL_SEC)


if __name__ == '__main__':
    main()
