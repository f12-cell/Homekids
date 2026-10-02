import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent.parent))

from scapy.all import ARP, Ether, srp
import sqlite3
from config import DB

conn = sqlite3.connect(str(DB))

pkt = Ether(dst='ff:ff:ff:ff:ff:ff') / ARP(pdst='192.168.137.0/24')
result, _ = srp(pkt, timeout=3, verbose=False)

print('Devices found on HomeKids hotspot:')
print('-' * 45)

if not result:
    print('No devices found.')
    print('Make sure a phone is connected to HomeKids WiFi first.')
else:
    for _, rcv in result:
        print(f'  IP:  {rcv.psrc}')
        print(f'  MAC: {rcv.hwsrc}')
        print('-' * 45)
        conn.execute(
            '''INSERT OR IGNORE INTO devices
               VALUES (?,?,?,datetime("now"))''',
            (rcv.hwsrc, rcv.psrc, 'Unknown')
        )
        conn.commit()

print('Done. Run name_device.py next.')
conn.close()
