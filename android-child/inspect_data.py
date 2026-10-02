import sqlite3
import os
import sys
from pathlib import Path

# Force utf-8 stdout
sys.stdout.reconfigure(encoding='utf-8')

db_path = Path(__file__).parent.parent / "db" / "parental.db"
print(f"Checking DB at: {db_path.resolve()}")
if not db_path.exists():
    print("Database does not exist!")
    exit(1)

conn = sqlite3.connect(str(db_path))
cursor = conn.cursor()

print("\n--- DEVICES ---")
cursor.execute("SELECT ip, child_name, last_seen, api_key FROM devices")
devices = cursor.fetchall()
for d in devices:
    print(d)

print("\n--- PROFILES ---")
cursor.execute("SELECT * FROM profiles")
for p in cursor.fetchall():
    print(p)

print("\n--- TOTAL DNS LOGS ---")
cursor.execute("SELECT COUNT(*) FROM dns_log")
print("Total count:", cursor.fetchone()[0])

print("\n--- RECENT DNS LOGS (last 20) ---")
cursor.execute("SELECT id, ip, child, domain, app, ts, status FROM dns_log ORDER BY id DESC LIMIT 20")
for r in cursor.fetchall():
    print(r)

print("\n--- DISTINCT CHILDREN IN DNS LOG ---")
cursor.execute("SELECT DISTINCT child FROM dns_log")
print(cursor.fetchall())

conn.close()
