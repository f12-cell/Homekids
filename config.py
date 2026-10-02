import os
from pathlib import Path

ROOT    = Path(__file__).parent.resolve()
DB      = ROOT / 'db' / 'parental.db'
LOGS    = ROOT / 'logs'
DNS_LOG = LOGS / 'dns_log.csv'

# Phase 2: Database URL configuration
DATABASE_URL = os.getenv('DATABASE_URL', f"sqlite:///{DB}")

# AI / ML Config
OLLAMA_BASE_URL = os.getenv('OLLAMA_BASE_URL', 'http://localhost:11434')
LLM_MODEL = os.getenv('LLM_MODEL', 'gemma:2b')

# Try to load dotenv but don't crash if it's missing
try:
    from dotenv import load_dotenv
    load_dotenv(ROOT / '.env')
except ImportError:
    pass
