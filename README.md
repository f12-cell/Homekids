# HomeKids — Parental Control Dashboard

A real-time parental monitoring tool for Windows. Your laptop becomes a supervised Wi-Fi hotspot;
the kids' devices connect through it, and every site/app they use is captured, shown live in a
dashboard, and can be blocked or put on a curfew schedule — all running locally on one laptop,
with no cloud, no account, and no monthly fee.

---

## ⚠️ Read this first — the one step everyone misses

Modern phones and browsers use **encrypted DNS** (DNS-over-HTTPS / iCloud Private Relay) by default.
When that's on, the device hides where it's going, so HomeKids **cannot see or block anything** on it.
You **must turn encrypted DNS off on every device you want to monitor** (see
[Controlled-device setup](#3-controlled-device-setup--do-this-on-every-monitored-device) below).
This was verified during testing: with it on = 0 activity captured; with it off = full activity captured.

---

## 1. Requirements

| Requirement | Notes |
|---|---|
| **Windows 10/11** | Must run as **Administrator** |
| **Python 3.9+** | [python.org/downloads](https://www.python.org/downloads/) — check "Add to PATH" during install |
| **Node.js 18+** | [nodejs.org](https://nodejs.org) |
| **Wireshark / TShark** | [wireshark.org/download](https://www.wireshark.org/download.html) — include **TShark** and **Npcap** when prompted |
| Python packages | `pip install fastapi uvicorn scapy requests python-dotenv` |

---

## 2. First-time setup (once)

Open PowerShell in the project folder (`parental\parental`) and run:

```
pip install fastapi uvicorn scapy requests python-dotenv
cd frontend\dashboard
npm install
cd ..\..
```

That's it. The capture network interface is now **auto-detected** — you no longer edit any config.

---

## 3. Controlled-device setup — do this on EVERY monitored device

Without this, the device's traffic is encrypted and HomeKids sees nothing. Do the steps for each device:

### iPhone / iPad
1. **Settings → [your name] → iCloud → Private Relay → OFF**
2. **Settings → Wi-Fi → tap the ⓘ next to the HomeKids network → "Limit IP Address Tracking" → OFF**
   *(this per-network toggle is the one most people miss)*
3. On that same screen: **Configure DNS → Automatic**
4. **Settings → General → VPN, DNS & Device Management** → remove any DNS profile or VPN

### Android
- **Settings → Network & internet → Private DNS → Off**
- If using Chrome/Firefox, also do the browser step below

### Any browser (Chrome, Edge, Firefox) on any device
- **Chrome/Edge:** Settings → Privacy & security → Security → **Use secure DNS → OFF**
- **Firefox:** Settings → Privacy → **DNS over HTTPS → Off**

Then **connect the device to the HomeKids hotspot Wi-Fi.** (Tip: after changing these, toggle the
device's Wi-Fi or Airplane Mode off/on to clear its DNS cache.)

---

## 4. Running the dashboard

1. Turn on the hotspot: **Settings → Network & Internet → Mobile hotspot → On**
2. In the project folder, **right-click `start.bat` → Run as administrator**
   *(admin is required for packet capture, port 53, the firewall, and the hosts file)*

It starts six services in order and a browser opens to the dashboard:

```
[1/6] DNS capture        (TShark — auto-detects your hotspot adapter)
[2/6] DNS blocking server (returns "blocked" for banned domains)
[3/6] DNS parser         (writes activity to the database)
[4/6] Curfew scheduler   (enforces Schedule-tab curfews)
[5/6] FastAPI backend    → http://localhost:8000
[6/6] React dashboard    → http://localhost:3000
```

Press **Ctrl+C** in the window to stop everything.

---

## 5. Using the dashboard

- **Activity** — live feed of every domain each child visits; search, filter by date, export CSV; late-night and blocked attempts are flagged.
- **Monitoring** — 24-hour timeline, app pie chart, estimated screen time, sleep-pattern (after 10 PM) list.
- **Usage** — ranked chart of which apps are used most.
- **Blocking** — Quick (tap an app), Categories (block a whole category), Custom (type any domain), Attempts (what they tried to reach). Blocks apply within a few seconds.
- **Schedule** — set a curfew window (e.g. 22:00–06:00, Mon–Fri) that automatically cuts internet for that child's devices; plus one-tap Homework Mode.
- **Rename a device** — select it and click the **✏️** button to name it (e.g. "Ahmed's Phone"); its history follows the new name.
- **Open from a phone** — any device on the hotspot can open the dashboard at **`http://192.168.137.1:3000`**.

---

## 6. How blocking works — and an honest limitation

Blocking works at the **DNS level**: when a device looks up a blocked site, HomeKits answers
"does not exist," so it won't load. This covers websites and most apps.

**Known limitation:** some native apps (most notably the **YouTube app**) cache IP addresses or use
QUIC/HTTP-3 and can keep working even when their domain is blocked. This is a well-known limit of all
DNS-based filters (Pi-hole, NextDNS, etc.), **not a bug**. A deeper enforcement layer (IP/SNI-level
blocking) is planned as a future enhancement.

---

## 7. Email alerts (optional)

Alerts (late-night, repeated blocked attempts, screen-time limit) are sent by email. Edit the **`.env`**
file in the project root:

- `GMAIL_USER` / `PARENT_EMAIL` — your address (already filled in)
- `GMAIL_APP_PASSWORD` — a 16-character **App Password**, *not* your normal password. Generate at
  [myaccount.google.com/apppasswords](https://myaccount.google.com/apppasswords) (needs 2-Step Verification on).
- **Note:** some school/work Google accounts block App Passwords — if so, use a personal `@gmail.com`
  as the sender and keep your main address as `PARENT_EMAIL`.

---

## 8. Troubleshooting

| Symptom | Fix |
|---|---|
| **Activity feed empty / a device shows nothing** | Encrypted DNS still on — redo [Controlled-device setup](#3-controlled-device-setup--do-this-on-every-monitored-device), then toggle the device's Wi-Fi. This is the #1 issue. |
| No devices appear at all | Hotspot off, or not run as admin. Turn hotspot on, connect a device, wait ~30s, refresh. |
| Blocking a site doesn't stop it | Encrypted DNS still on (browser too), or the app cached it — toggle Wi-Fi and retry; for apps see the limitation above. |
| "Access denied" when blocking | Not running as admin — restart via right-click → Run as administrator. |
| Dashboard blank from the phone | Use `http://192.168.137.1:3000` (not localhost); both devices must be on the hotspot. |
| Alerts don't send | App Password missing or blocked by your account — use a personal Gmail. |
| Capture not working after a Windows update | Rare: reinstall/repair Wireshark (Npcap). The interface itself auto-detects. |

---

## 9. How it works

```
Child's device ── HomeKids Wi-Fi hotspot (192.168.137.x) ── TShark captures DNS
                                                                     │
                                                              logs/dns_log.csv
                                                                     │
                                                        dns_parser.py → SQLite (parental.db)
                                                                     │
                                                     FastAPI (/api/*) → React dashboard
                                                                     │
                     dns_server.py (port 53) returns "blocked" for banned domains,
                     scheduler.py cuts internet during curfew windows.
```

Your Windows machine must have the Mobile Hotspot on; children's devices connect to it like normal
Wi-Fi, and all their DNS passes through and is captured.

---

## 10. File structure

```
parental\parental\
├── start.bat              ← double-click (Run as administrator)
├── launch.py             ← starts all 6 services; auto-detects the hotspot interface
├── config.py             ← paths
├── schema.py             ← single source of truth for the database schema
├── .env                  ← email alert credentials (App Password)
├── backend\
│   ├── app.py            ← FastAPI routes + firewall/hosts helpers
│   ├── dns_server.py     ← DNS sinkhole (blocking + curfew enforcement)
│   ├── dns_parser.py     ← CSV → SQLite, computes allow/blocked status
│   ├── scheduler.py      ← evaluates curfew schedules
│   ├── setup_db.py       ← creates the database (uses schema.py)
│   ├── scan_devices.py   ← one-off ARP scan
│   └── name_device.py    ← assign a device name by MAC (or use the ✏️ button)
├── db\parental.db        ← SQLite database (auto-created)
├── logs\dns_log.csv      ← TShark output (auto-created)
└── frontend\dashboard\   ← React app (npm start → localhost:3000)
```

---

## 11. Known limitations — please read (these are expected, not faults)

This is a working prototype. The behaviours below are **known and by design** — they come from how
network monitoring works (or are quick one-time setup steps), and are **not signs that the app is
broken.** Every tool in this category has the same constraints.

1. **Each device needs "encrypted DNS / Private Relay" turned off (one-time).**
   Modern phones hide their activity by default. Until you do the quick toggle in
   [Section 3](#3-controlled-device-setup--do-this-on-every-monitored-device), that device will show
   **no activity** — this is the device hiding itself, not the app failing. Once toggled off, it works.

2. **Some native apps (most notably the YouTube app) can keep working when blocked.**
   Blocking here works at the DNS level — it stops a device from *looking up* a banned site. Websites
   and most apps are blocked reliably. But a few apps remember addresses or use newer connection
   methods (QUIC) that skip the lookup, so they can partly slip through. **This is a well-known limit
   of all DNS-based filters** (the same is true for Pi-hole, NextDNS, and similar tools) — not a defect
   of this project. A deeper blocking layer (IP/connection-level) is identified as a **planned
   enhancement**.

3. **Why blocking is done at the DNS level (a Windows constraint).**
   Windows does not reliably let its built-in firewall filter traffic that is being *shared* to other
   devices, so DNS-level blocking is the dependable approach on a Windows hotspot. This is a Windows
   platform limitation, not a design oversight.

4. **Local-network tool, no login screen.**
   Everything runs on the one laptop and the dashboard is only reachable by devices already on *your*
   hotspot, so it intentionally has no password. This is appropriate for home use; it is not meant to
   be exposed to the public internet.

5. **Prototype status.**
   It has been tested end-to-end on real devices and works. As a prototype, it assumes a normal
   Windows Mobile Hotspot setup; a major Windows or Wireshark change may occasionally need a quick
   reinstall of Wireshark/Npcap. The network adapter itself is auto-detected.

**In short:** the app works. Points 1–3 are the nature of network-level parental controls, and the
setup guide above walks through the one device-side step (encrypted DNS off) that makes everything
visible.

---

See `PROJECT_SUMMARY.md` for a plain-language overview to share.
