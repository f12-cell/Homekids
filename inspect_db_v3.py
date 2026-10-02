import sqlite3
import os

DB_PATH = r"C:\Users\Cv\Downloads\fatimas project\fatimas project\parental\parental\db\parental.db"

def inspect():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    print("--- RECENT LOGS FOR AHMED ---")
    cursor.execute("SELECT * FROM dns_log WHERE child='Ahmed' ORDER BY id DESC LIMIT 5")
    for row in cursor.fetchall():
        print(row)

    print("\n--- ALL LOGS IN LAST 10 MIN ---")
    # Cutoff 10 mins ago
    import datetime
    cutoff = (datetime.datetime.utcnow() - datetime.timedelta(minutes=10)).strftime('%Y-%m-%d %H:%M:%S')
    cursor.execute("SELECT * FROM dns_log WHERE ts > ? ORDER BY id DESC", (cutoff,))
    for row in cursor.fetchall():
        print(row)

    conn.close()

if __name__ == "__main__":
    inspect()
