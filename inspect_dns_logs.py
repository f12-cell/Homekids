import sqlite3
import datetime

DB_PATH = r"C:\Users\Cv\Downloads\fatimas project\fatimas project\parental\parental\db\parental.db"

def inspect():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    print("--- LAST 5 DNS LOGS ---")
    cursor.execute("SELECT child, domain, ts, status FROM dns_log ORDER BY id DESC LIMIT 5")
    rows = cursor.fetchall()
    if not rows:
        print("No logs found.")
    else:
        for row in rows:
            print(row)

    print("\n--- DEVICES IN DB ---")
    cursor.execute("SELECT child_name, ip, api_key FROM devices")
    for row in cursor.fetchall():
        print(row)

    conn.close()

if __name__ == "__main__":
    inspect()
