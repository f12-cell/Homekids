import sqlite3
import os

DB_PATH = r"C:\Users\Cv\Downloads\fatimas project\fatimas project\parental\parental\db\parental.db"

def inspect():
    conn = sqlite3.connect(DB_PATH)
    cursor = conn.cursor()

    print("--- COUNT LOGS BY CHILD ---")
    cursor.execute("SELECT child, COUNT(*) FROM dns_log GROUP BY child")
    for row in cursor.fetchall():
        print(row)

    print("\n--- CHECK FATIMA ENTRIES ---")
    # Using Fatima with and without space
    cursor.execute("SELECT * FROM dns_log WHERE child LIKE 'Fatima%' ORDER BY id DESC LIMIT 5")
    for row in cursor.fetchall():
        print(row)

    conn.close()

if __name__ == "__main__":
    inspect()
