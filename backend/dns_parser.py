import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import csv
import os
import time
import requests
from config import DNS_LOG

API_URL = "http://localhost:8000/api/activity"


def clean_domain(value):
    if not value:
        return None
    value = value.strip().rstrip('.')
    if not value:
        return None
    return value


def parse_logs():
    if not os.path.exists(DNS_LOG):
        return

    with open(DNS_LOG, 'r', encoding='utf-8', errors='replace', newline='') as f:
        rows = list(csv.reader(f))

    if not rows:
        return

    open(DNS_LOG, 'w', encoding='utf-8').close()

    batch = []
    for row in rows:
        if not row:
            continue
        row = [c.strip() for c in row if c is not None]
        if len(row) < 3:
            continue

        ts_raw = row[0]
        ip = row[1]
        domain = clean_domain(row[2])
        if not domain:
            continue

        try:
            ts = time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime(float(ts_raw)))
        except Exception:
            ts = time.strftime('%Y-%m-%d %H:%M:%S', time.gmtime())

        batch.append({
            "ip": ip,
            "domain": domain,
            "app": "Other",
            "timestamp": ts,
            "status": "allowed"
        })

    if not batch:
        return

    try:
        response = requests.post(API_URL, json=batch, timeout=5)
        response.raise_for_status()
        print(f"[parser] Uploaded {len(batch)} entries from TShark")
    except Exception as e:
        print(f"[parser] Upload error: {e}")


def main():
    print(f"[parser] Watching TShark logs at {DNS_LOG}...")
    while True:
        try:
            parse_logs()
        except Exception as e:
            print(f"[parser] loop error: {e}")
        time.sleep(5)


if __name__ == '__main__':
    main()
