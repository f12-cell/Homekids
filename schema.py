"""
Phase 1 Schema for HomeKids - Advanced Parental Controls & Security.
"""

SCHEMA = [
    '''CREATE TABLE IF NOT EXISTS profiles (
        child_name TEXT PRIMARY KEY,
        avatar TEXT DEFAULT '🧒',
        age INTEGER,
        bedtime TEXT DEFAULT '22:00',
        wake_time TEXT DEFAULT '06:00',
        daily_limit INTEGER DEFAULT 120,
        internet_paused INTEGER DEFAULT 0,
        enforce_doh_blocking INTEGER DEFAULT 1,
        color TEXT DEFAULT '#3b82f6',
        created_at TEXT DEFAULT (datetime('now')))''',

    '''CREATE TABLE IF NOT EXISTS blocked_sites (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        domain TEXT UNIQUE,
        category TEXT DEFAULT 'Custom',
        severity TEXT DEFAULT 'Low',
        added_at TEXT DEFAULT (datetime('now')))''',

    '''CREATE TABLE IF NOT EXISTS devices (
        mac TEXT PRIMARY KEY,
        ip TEXT UNIQUE,
        device_uuid TEXT UNIQUE,
        api_key TEXT UNIQUE,
        child_name TEXT,
        platform TEXT DEFAULT 'Android',
        app_version TEXT,
        last_seen TEXT,
        provisioned INTEGER DEFAULT 0,
        tamper_status TEXT DEFAULT 'Healthy')''',

    '''CREATE TABLE IF NOT EXISTS schedules (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        child_name TEXT,
        label TEXT,
        block_from TEXT,
        block_until TEXT,
        days TEXT)''',

    '''CREATE TABLE IF NOT EXISTS dns_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        ip TEXT,
        child TEXT,
        domain TEXT,
        app TEXT,
        ts TEXT DEFAULT (datetime('now')),
        status TEXT DEFAULT 'allowed',
        rule_matched TEXT)''',

    '''CREATE TABLE IF NOT EXISTS security_events (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        device_uuid TEXT,
        child_name TEXT,
        event_type TEXT,
        severity TEXT,
        detail TEXT,
        explanation TEXT,
        recommendation TEXT,
        ts TEXT DEFAULT (datetime('now')))''',

    '''CREATE TABLE IF NOT EXISTS audit_log (
        id INTEGER PRIMARY KEY AUTOINCREMENT,
        action TEXT,
        old_value TEXT,
        new_value TEXT,
        ts TEXT DEFAULT (datetime('now')))''',

    '''CREATE TABLE IF NOT EXISTS threat_domains (
        domain TEXT PRIMARY KEY,
        threat_type TEXT,
        source TEXT)'''
]

def migrate(conn):
    """Adds missing columns for Phase 1 without breaking existing data."""
    tables = {
        'profiles': [
            ('internet_paused', 'INTEGER DEFAULT 0'),
            ('enforce_doh_blocking', 'INTEGER DEFAULT 1'),
            ('color', "TEXT DEFAULT '#3b82f6'")
        ],
        'devices': [
            ('device_uuid', 'TEXT UNIQUE'),
            ('api_key', 'TEXT UNIQUE'),
            ('app_version', 'TEXT'),
            ('tamper_status', "TEXT DEFAULT 'Healthy'")
        ],
        'dns_log': [
            ('status', "TEXT DEFAULT 'allowed'"),
            ('rule_matched', 'TEXT')
        ],
        'blocked_sites': [
            ('category', "TEXT DEFAULT 'Custom'"),
            ('severity', "TEXT DEFAULT 'Low'")
        ]
    }
    for table, cols in tables.items():
        try:
            existing = [r[1] for r in conn.execute(f"PRAGMA table_info({table})").fetchall()]
            for col_name, col_def in cols:
                if col_name not in existing:
                    # Clean up 'UNIQUE' for ALTER TABLE as SQLite has restrictions
                    clean_def = col_def.replace('UNIQUE', '')
                    conn.execute(f"ALTER TABLE {table} ADD COLUMN {col_name} {clean_def}")
        except: pass

def init(conn):
    for stmt in SCHEMA:
        try: conn.execute(stmt)
        except: pass
    migrate(conn)
    conn.commit()
