import sys
import subprocess
import time
import ctypes
from pathlib import Path

ROOT = Path(__file__).parent.resolve()

def is_admin():
    try:
        return ctypes.windll.shell32.IsUserAnAdmin()
    except Exception:
        return False

def check(proc, name):
    time.sleep(2)
    if proc.poll() is not None:
        print(f'  WARNING: {name} exited early — check output above.')
        return False
    print(f'  OK — {name} running')
    return True

def find_hotspot_interface(fallback):
    """
    Return the tshark -i value for the adapter that currently holds the hotspot
    gateway IP 192.168.137.1. Uses the adapter's GUID *device path* (stable)
    rather than the tshark index number, which reorders across hotspot toggles
    and reboots. Falls back to the manual index if auto-detection fails.
    """
    ps = ("$a=(Get-NetIPAddress -AddressFamily IPv4 | "
          "Where-Object IPAddress -eq '192.168.137.1').InterfaceAlias; "
          "(Get-NetAdapter -IncludeHidden | Where-Object Name -eq $a).InterfaceGuid")
    try:
        out = subprocess.run(['powershell', '-NoProfile', '-Command', ps],
                             capture_output=True, text=True, timeout=10).stdout.strip()
        if out.startswith('{') and out.endswith('}'):
            return r'\Device\NPF_' + out
    except Exception:
        pass
    return fallback

def main():
    if not is_admin():
        print('=' * 60)
        print('ERROR: Administrator privileges required.')
        print('Right-click start.bat → Run as administrator')
        print('=' * 60)
        input('\nPress Enter to exit...')
        sys.exit(1)

    print('HomeKids Parental Control — starting services')
    print('=' * 60)

    logs = ROOT / 'logs'
    logs.mkdir(exist_ok=True)
    dns_log = logs / 'dns_log.csv'

    # Make sure DB folder exists
    db_dir = ROOT / 'db'
    db_dir.mkdir(exist_ok=True)

    procs = []

    # ── 1. TShark DNS capture ─────────────────────────────────
    # The hotspot capture interface is AUTO-DETECTED by the adapter that holds
    # 192.168.137.1, using its stable GUID device path. The tshark index number
    # reorders across hotspot toggles/reboots, so we no longer rely on it.
    # HOTSPOT_INTERFACE is only a manual fallback if auto-detection fails.
    tshark_exe = r'C:\Program Files\Wireshark\tshark.exe'
    HOTSPOT_INTERFACE = '7'  # fallback only — auto-detect overrides this

    hotspot_iface = find_hotspot_interface(HOTSPOT_INTERFACE)
    print(f'[1/6] DNS capture  →  {dns_log}')
    print(f'      capture interface: {hotspot_iface}')
    if Path(tshark_exe).exists():
        with open(str(dns_log), 'a') as log_fh:
            p = subprocess.Popen([
                tshark_exe,
                '-i', hotspot_iface,   # ← auto-detected GUID device path (stable)
                '-f', 'udp port 53',
                '-T', 'fields',
                '-e', 'frame.time_epoch',
                '-e', 'ip.src',
                '-e', 'dns.qry.name',
                '-E', 'separator=,',
                '-l',                      # ← line-buffered so entries appear immediately
            ], stdout=log_fh, stderr=subprocess.DEVNULL)
        procs.append(p)
        check(p, 'TShark')
    else:
        print(f'  ERROR: TShark not found at {tshark_exe}')
        print('  Install Wireshark from https://www.wireshark.org/download.html')
        input('\nPress Enter to exit...')
        sys.exit(1)

    # ── 2. DNS blocking server ───────────────────────────────
    print('[2/6] DNS blocking server  →  192.168.137.1:53')
    p = subprocess.Popen(
        [sys.executable, str(ROOT / 'backend' / 'dns_server.py')],
        cwd=str(ROOT)
    )
    procs.append(p)
    check(p, 'DNS blocking server')

    # ── 3. DNS parser ─────────────────────────────────────────
    print('[3/6] DNS parser  (watching for new DNS entries)')
    p = subprocess.Popen(
        [sys.executable, str(ROOT / 'backend' / 'dns_parser.py')],
        cwd=str(ROOT)
    )
    procs.append(p)
    check(p, 'DNS parser')

    # ── 4. Curfew scheduler ───────────────────────────────────
    print('[4/6] Curfew scheduler  (enforces Schedule tab curfews)')
    p = subprocess.Popen(
        [sys.executable, str(ROOT / 'backend' / 'scheduler.py')],
        cwd=str(ROOT)
    )
    procs.append(p)
    check(p, 'Curfew scheduler')

    # ── 5. FastAPI backend ────────────────────────────────────
    print('[5/6] FastAPI backend  →  http://localhost:8000')
    p = subprocess.Popen(
        [sys.executable, '-m', 'uvicorn', 'app:app',
         '--host', '0.0.0.0', '--port', '8000'],
        cwd=str(ROOT / 'backend')
    )
    procs.append(p)
    time.sleep(3)
    if p.poll() is not None:
        print('  ERROR: FastAPI failed to start.')
    else:
        print('  OK — FastAPI running')

    # ── 6. React dashboard ────────────────────────────────────
    print('[6/6] React dashboard  →  http://localhost:3000')
    p = subprocess.Popen(
        ['npm.cmd', 'start'],
        cwd=str(ROOT / 'frontend' / 'dashboard'),
    )
    procs.append(p)
    time.sleep(5)
    if p.poll() is not None:
        print("  ERROR: React dev server failed. Run 'npm install' in frontend/dashboard first.")
    else:
        print('  OK — Dashboard running')

    print()
    print('=' * 60)
    print('Dashboard: http://localhost:3000')
    print('API:       http://localhost:8000/api/connected')
    print('Press Ctrl+C to stop all services.')
    print('=' * 60)

    try:
        while True:
            time.sleep(5)
    except KeyboardInterrupt:
        print('\nShutting down...')
        for p in procs:
            try: p.terminate()
            except: pass
        print('Done.')

if __name__ == '__main__':
    main()
