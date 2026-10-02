# HomeKids — Project Summary

*A simple, at-a-glance overview of what this project is and what it does.*

---

## What is it?

**HomeKids** turns an ordinary Windows laptop into a **supervised Wi-Fi network** for a household.
The children's phones and tablets connect to the internet *through* the laptop, which means the
parent can **see everything they access in real time and block what they shouldn't** — all from a
clean, friendly dashboard.

Think of the laptop as a **security checkpoint at the front door of the internet**: every request a
child's device makes passes through it, gets logged, and can be waved through or turned away.

---

## The problem it solves

Parents want to know what their kids are doing online and set healthy limits — but most tools cost a
monthly subscription, send your family's data to a company's cloud, or need a separate device.
HomeKids does it all **on one laptop you already own**, **privately** (nothing leaves the house), with
**no subscription**.

---

## What it can do

- 📋 **See live activity** — every website and app each child uses, as it happens, with timestamps.
- 🚫 **Block sites and apps** — one tap blocks TikTok, Instagram, YouTube, etc., or a whole category
  (Social Media, Gaming, Adult content…).
- ⏰ **Set curfews** — automatically cut off internet during set hours (e.g. bedtime 10 PM–6 AM, or a
  homework window). Enforced automatically in the background.
- 🌙 **Spot late-night use** — flags activity after 10 PM and shows sleep patterns.
- 📊 **Understand usage** — charts of screen time and which apps are used most, per child.
- 🔔 **Get alerts** — an email if a child is online past bedtime or repeatedly tries a blocked site.
- 👤 **Friendly names & per-child rules** — label each device ("Ahmed's Phone") and apply different
  rules to different children.
- ⬇️ **Export reports** — download activity as a spreadsheet.

---

## How it works (in one picture)

```
  Child's phone ──►  Parent's laptop (the checkpoint)  ──►  Internet
                          │
                          ├─ logs every site visited  →  live dashboard
                          └─ refuses blocked sites & enforces curfews
```

The parent opens a dashboard in their browser (or from their own phone) to watch activity and change
the rules at any time.

---

## What makes it notable

- **Runs entirely on one laptop** — no extra hardware, no router changes.
- **Completely private** — no cloud, no account, no data sent anywhere.
- **No subscription** — unlike most commercial parental-control apps.
- **Real-time** — the dashboard updates on its own, no refreshing.

---

## Honest limitations (so expectations are clear)

- Each monitored device needs its **"encrypted DNS" / "Private Relay" setting turned off** (a quick,
  one-time toggle) — otherwise the device hides its activity. The setup guide explains exactly how.
- Blocking works for websites and most apps. A few native apps — most notably the **YouTube app** —
  can partly get around simple blocking; this is a known limitation of this category of tool and is
  flagged as a planned future improvement.

---

## Built with

Python (FastAPI) for the backend, a React dashboard, a SQLite database, and Wireshark/TShark for
network capture — all on Windows. It's a **working prototype, tested end-to-end on real devices.**

---

*For full setup and run instructions, see `README.md`.*
