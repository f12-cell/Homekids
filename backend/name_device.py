import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

import sqlite3
from config import DB

conn = sqlite3.connect(str(DB))

MAC  = 'de:02:1d:ea:47:64'
IP   = '192.168.137.172'
NAME = 'Ahmed'

conn.execute(
    '''INSERT OR IGNORE INTO devices
       VALUES (?, ?, 'Unknown', datetime("now"))''',
    (MAC, IP)
)
conn.execute(
    'UPDATE devices SET child_name=?, ip=?, last_seen=datetime("now") WHERE mac=?',
    (NAME, IP, MAC)
)
conn.commit()
print(f'Device registered:')
print(f'  Name: {NAME}')
print(f'  IP:   {IP}')
print(f'  MAC:  {MAC}')
conn.close()
