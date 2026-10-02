import sqlite3
import requests
import json
from pathlib import Path

db_path = Path(__file__).parent.parent / "db" / "parental.db"
conn = sqlite3.connect(str(db_path))
cursor = conn.cursor()

# Test 1: Check existing devices
cursor.execute("SELECT ip, child_name, api_key FROM devices")
devices = cursor.fetchall()
print("Devices in DB:", devices)

# Test 2: Check dns_log count
cursor.execute("SELECT COUNT(*) FROM dns_log")
print("dns_log count:", cursor.fetchone()[0])

conn.close()
