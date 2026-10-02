import sqlite3
import datetime

DB_PATH = r"C:\Users\Cv\Downloads\fatimas project\fatimas project\parental\parental\db\parental.db"

def inspect():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    now_utc = datetime.datetime.utcnow().strftime('%Y-%m-%d %H:%M:%S')
    print(f"Current UTC: {now_utc}")

    cursor.execute("SELECT datetime('now')")
    print(f"SQLite UTC: {cursor.fetchone()[0]}")

    print("\n--- DEVICES ---")
    cursor.execute("SELECT ip, child_name, last_seen FROM devices")
    for row in cursor.fetchall():
        print(row)

    print("\n--- NEWEST LOGS ---")
    cursor.execute("SELECT * FROM dns_log ORDER BY id DESC LIMIT 5")
    for row in cursor.fetchall():
        print(row)

    conn.close()

if __name__ == "__main__":
    inspect()
