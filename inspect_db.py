import sqlite3
import os

DB_PATH = r"C:\Users\Cv\Downloads\fatimas project\fatimas project\parental\parental\db\parental.db"

def inspect():
    if not os.path.exists(DB_PATH):
        print(f"Database not found at {DB_PATH}")
        return

    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    print("--- TABLES ---")
    cursor.execute("SELECT name FROM sqlite_master WHERE type='table';")
    print(cursor.fetchall())

    print("\n--- DEVICES ---")
    try:
        cursor.execute("SELECT * FROM devices")
        for row in cursor.fetchall():
            print(row)
    except Exception as e: print(e)

    print("\n--- RECENT DNS LOGS (last 10) ---")
    try:
        cursor.execute("SELECT * FROM dns_log ORDER BY id DESC LIMIT 10")
        for row in cursor.fetchall():
            print(row)
    except Exception as e: print(e)

    print("\n--- BLOCKED SITES ---")
    try:
        cursor.execute("SELECT * FROM blocked_sites")
        for row in cursor.fetchall():
            print(row)
    except Exception as e: print(e)

    conn.close()

if __name__ == "__main__":
    inspect()
