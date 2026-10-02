"""
HomeKids alert system — email + WhatsApp notifications for parent.

Called from dns_parser.py after every DNS insert.
Reads credentials from the project-root .env file.
Uses alert_log table to prevent duplicate alerts within a 24h window.
"""
import os
import smtplib
import datetime
from pathlib import Path
from email.message import EmailMessage

try:
    from dotenv import load_dotenv
    load_dotenv(Path(__file__).parent / '.env')
except ImportError:
    pass  # Fall back to whatever is already in the environment


def load_config():
    return {
        'GMAIL_USER':              os.getenv('GMAIL_USER', ''),
        'GMAIL_APP_PASSWORD':      os.getenv('GMAIL_APP_PASSWORD', ''),
        'PARENT_EMAIL':            os.getenv('PARENT_EMAIL', ''),
        'TWILIO_SID':              os.getenv('TWILIO_SID', ''),
        'TWILIO_AUTH_TOKEN':       os.getenv('TWILIO_AUTH_TOKEN', ''),
        'TWILIO_FROM':             os.getenv('TWILIO_FROM', ''),
        'PARENT_WHATSAPP':         os.getenv('PARENT_WHATSAPP', ''),
        'BEDTIME_HOUR':            int(os.getenv('BEDTIME_HOUR',            '22')),
        'ATTEMPT_THRESHOLD':       int(os.getenv('ATTEMPT_THRESHOLD',        '5')),
        'SCREEN_TIME_LIMIT_HOURS': float(os.getenv('SCREEN_TIME_LIMIT_HOURS', '4')),
    }


# ── Deduplication helpers ─────────────────────────────────────

def _already_sent(conn, child, alert_type, detail, window_hours=24):
    cutoff = (datetime.datetime.now() - datetime.timedelta(hours=window_hours)) \
               .strftime('%Y-%m-%d %H:%M:%S')
    row = conn.execute(
        'SELECT 1 FROM alert_log WHERE child=? AND alert_type=? AND detail=? AND sent_at > ?',
        (child, alert_type, detail, cutoff)
    ).fetchone()
    return row is not None


def _log_alert(conn, child, alert_type, detail):
    conn.execute(
        "INSERT INTO alert_log (child, alert_type, detail, sent_at) "
        "VALUES (?, ?, ?, datetime('now'))",
        (child, alert_type, detail)
    )
    conn.commit()


# ── Senders ───────────────────────────────────────────────────

def send_email(subject, body, cfg):
    user = cfg.get('GMAIL_USER', '')
    pwd  = cfg.get('GMAIL_APP_PASSWORD', '')
    to   = cfg.get('PARENT_EMAIL', '')
    if not (user and pwd and to):
        return False, 'email not configured (GMAIL_USER / GMAIL_APP_PASSWORD / PARENT_EMAIL missing)'
    try:
        msg = EmailMessage()
        msg['Subject'] = f'[HomeKids] {subject}'
        msg['From']    = user
        msg['To']      = to
        msg.set_content(body)
        with smtplib.SMTP('smtp.gmail.com', 587, timeout=10) as s:
            s.ehlo()
            s.starttls()
            s.login(user, pwd)
            s.send_message(msg)
        return True, 'sent'
    except smtplib.SMTPAuthenticationError:
        return False, 'Gmail auth failed — check GMAIL_APP_PASSWORD in .env'
    except Exception as e:
        return False, str(e)


def send_whatsapp(body, cfg):
    sid   = cfg.get('TWILIO_SID', '')
    token = cfg.get('TWILIO_AUTH_TOKEN', '')
    from_ = cfg.get('TWILIO_FROM', '')
    to    = cfg.get('PARENT_WHATSAPP', '')
    if not (sid and token and from_ and to):
        return False, 'WhatsApp not configured (TWILIO_* / PARENT_WHATSAPP missing)'
    try:
        from twilio.rest import Client
        Client(sid, token).messages.create(
            body=f'[HomeKids] {body}',
            from_=from_,
            to=to,
        )
        return True, 'sent'
    except ImportError:
        return False, 'twilio not installed — run: pip install twilio'
    except Exception as e:
        return False, str(e)


# ── Internal: send + log ──────────────────────────────────────

def _dispatch(subject, body, cfg, conn, child, alert_type, detail):
    ok_e, err_e = send_email(subject, body, cfg)
    ok_w, err_w = send_whatsapp(body, cfg)
    _log_alert(conn, child, alert_type, detail)
    if not ok_e:
        print(f'[alerts] email skipped or failed: {err_e}')
    if not ok_w:
        print(f'[alerts] whatsapp skipped or failed: {err_w}')


# ── Main entry point (called per DNS insert) ──────────────────

def check_and_send_alerts(child, domain, ts_str, conn, cfg):
    """
    Check three conditions and send email + WhatsApp if triggered.
    Dedup logic prevents re-alerting within a 24h window per condition.
    """
    if not child or child == 'Unknown':
        return

    bedtime_hour      = cfg['BEDTIME_HOUR']
    attempt_threshold = cfg['ATTEMPT_THRESHOLD']
    screen_limit_hrs  = cfg['SCREEN_TIME_LIMIT_HOURS']

    try:
        ts = datetime.datetime.fromisoformat(ts_str)
    except Exception:
        ts = datetime.datetime.now()

    hour     = ts.hour
    date_str = ts.date().isoformat()

    # ── 1. Bedtime violation ──────────────────────────────────
    is_past_bedtime = (hour >= bedtime_hour) or (hour < 6)
    if is_past_bedtime:
        detail = f'{child}:{date_str}'
        if not _already_sent(conn, child, 'bedtime', detail):
            subject = f'{child} is online after bedtime'
            body = (
                f'{child} visited {domain} at {ts.strftime("%H:%M")} — '
                f'after the {bedtime_hour:02d}:00 bedtime limit.\n\n'
                f'Open the HomeKids dashboard to see the full activity log.'
            )
            _dispatch(subject, body, cfg, conn, child, 'bedtime', detail)

    # ── 2. Blocked site attempt threshold ─────────────────────
    blocked_domains = {r[0] for r in conn.execute('SELECT domain FROM blocked_sites').fetchall()}
    domain_root = domain.split('.')[0] if '.' in domain else domain
    matched_blocked = next((b for b in blocked_domains if b in domain), None)

    if matched_blocked:
        today_count = conn.execute(
            "SELECT COUNT(*) FROM dns_log "
            "WHERE child=? AND domain LIKE ? AND ts LIKE ?",
            (child, f'%{domain_root}%', f'{date_str}%')
        ).fetchone()[0]

        if today_count >= attempt_threshold:
            detail = f'{child}:{domain_root}:{date_str}'
            if not _already_sent(conn, child, 'blocked_threshold', detail):
                subject = f'{child} attempted a blocked site {today_count}× today'
                body = (
                    f'{child} has tried to access {matched_blocked} (and related domains) '
                    f'{today_count} times today — your threshold is {attempt_threshold}.\n\n'
                    f'Open the HomeKids dashboard → Blocking → Attempts for details.'
                )
                _dispatch(subject, body, cfg, conn, child, 'blocked_threshold', detail)

    # ── 3. Daily screen time limit ────────────────────────────
    # Proxy: DNS entry count for the day. Each entry ≈ ~1 min of browsing activity.
    entry_count = conn.execute(
        "SELECT COUNT(*) FROM dns_log WHERE child=? AND ts LIKE ?",
        (child, f'{date_str}%')
    ).fetchone()[0]

    screen_limit_entries = int(screen_limit_hrs * 60)
    if entry_count >= screen_limit_entries:
        detail = f'{child}:screen:{date_str}'
        if not _already_sent(conn, child, 'screen_time', detail):
            subject = f'{child} has exceeded {screen_limit_hrs:.0f}h of screen time today'
            body = (
                f'{child} has accumulated an estimated {screen_limit_hrs:.0f}+ hours of '
                f'screen time today ({entry_count} DNS requests recorded).\n\n'
                f'Open the HomeKids dashboard → Monitoring for the full breakdown.'
            )
            _dispatch(subject, body, cfg, conn, child, 'screen_time', detail)
