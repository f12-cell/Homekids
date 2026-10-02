import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import sqlite3
from config import DB
import schema

DB.parent.mkdir(parents=True, exist_ok=True)

conn = sqlite3.connect(str(DB))
# Use the shared schema so this matches app.py's init_db() exactly.
schema.init(conn)

print(f'Database ready at: {DB}')
conn.close()
