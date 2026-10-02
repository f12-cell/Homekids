import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import sqlite3
from config import DB

conn = sqlite3.connect(str(DB))

conn.execute('''
    INSERT OR REPLACE INTO devices
    VALUES (?, ?, ?, datetime("now"))
''', ('de:02:1d:ea:47:64', '192.168.137.111', 'Ahmed'))

conn.commit()

rows = conn.execute('SELECT * FROM devices').fetchall()
print('All registered devices:')
for r in rows:
    print(f'  {r[2]:10} | {r[1]}')

conn.close()
