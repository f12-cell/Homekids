import { useState, useEffect, useCallback, useRef } from 'react';
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
  Cell, PieChart, Pie, Legend,
} from 'recharts';
import axios from 'axios';
import './App.css';

// Derive the API host from wherever the dashboard was loaded
const API = `http://${window.location.hostname || 'localhost'}:8000/api`;

const APP_COLORS = {
  YouTube:   '#FF0000',
  TikTok:    '#010101',
  Instagram: '#E1306C',
  Amazon:    '#FF9900',
  Google:    '#4285F4',
  WhatsApp:  '#25D366',
  Netflix:   '#E50914',
  Roblox:    '#00A2FF',
  Snapchat:  '#FFFC00',
  Facebook:  '#1877F2',
  Twitter:   '#1DA1F2',
  Steam:     '#3d85c8',
  Other:     '#94a3b8',
};

const CHILD_META = {
  Ahmed: { avatar: '👦', color: '#2563eb' },
  Sara:  { avatar: '👧', color: '#db2777' },
};
function getChildMeta(name) {
  return CHILD_META[name?.trim()] || { avatar: '👤', color: '#6366f1' };
}

const BLOCK_CATEGORIES = {
  '📱 Social Media': ['tiktok.com','instagram.com','snapchat.com','facebook.com','twitter.com','pinterest.com','tumblr.com'],
  '🎮 Gaming':       ['roblox.com','steampowered.com','epicgames.com','minecraft.net','ea.com','battlenet.com'],
  '🎬 Streaming':    ['netflix.com','twitch.tv','disneyplus.com','hulu.com','primevideo.com'],
  '📺 YouTube':      ['youtube.com','youtu.be','youtubei.googleapis.com','googlevideo.com'],
  '🔞 Adult':        ['pornhub.com','xvideos.com','xhamster.com','redtube.com','youporn.com'],
  '💬 Messaging':    ['whatsapp.com','telegram.org','discord.com','signal.org'],
};

const QUICK_BLOCKS = [
  { label: 'TikTok',    domain: 'tiktok.com',      icon: '🎵', color: '#ff2d55' },
  { label: 'Instagram', domain: 'instagram.com',    icon: '📸', color: '#E1306C' },
  { label: 'YouTube',   domain: 'youtube.com',      icon: '▶️',  color: '#FF0000' },
  { label: 'Snapchat',  domain: 'snapchat.com',     icon: '👻', color: '#f5a623' },
  { label: 'Facebook',  domain: 'facebook.com',     icon: '👍', color: '#1877F2' },
  { label: 'Roblox',    domain: 'roblox.com',       icon: '🎮', color: '#00A2FF' },
  { label: 'Twitter',   domain: 'twitter.com',      icon: '🐦', color: '#1DA1F2' },
  { label: 'Netflix',   domain: 'netflix.com',      icon: '🎬', color: '#E50914' },
  { label: 'Discord',   domain: 'discord.com',      icon: '🎧', color: '#5865F2' },
  { label: 'Twitch',    domain: 'twitch.tv',        icon: '🟣', color: '#9146FF' },
  { label: 'WhatsApp',  domain: 'whatsapp.com',     icon: '💬', color: '#25D366' },
  { label: 'Steam',     domain: 'steampowered.com', icon: '🎲', color: '#3d85c8' },
];

// ── Helpers ───────────────────────────────────────────────────
function timeAgo(ts) {
  if (!ts) return '';
  const diff = (Date.now() - new Date(ts)) / 1000;
  if (isNaN(diff)) return ts;
  if (diff < 60)    return `${Math.floor(diff)}s ago`;
  if (diff < 3600)  return `${Math.floor(diff / 60)}m ago`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`;
  return new Date(ts).toLocaleDateString();
}

function FavIcon({ domain }) {
  return (
    <img
      className="hk-favicon"
      src={`https://www.google.com/s2/favicons?domain=${domain}&sz=32`}
      onError={e => e.target.style.display = 'none'}
      alt=""
    />
  );
}

function buildHourlyData(activity) {
  const hours = Array.from({ length: 24 }, (_, i) => ({
    hour: `${String(i).padStart(2, '0')}:00`, requests: 0,
  }));
  activity.forEach(a => {
    if (!a.time) return;
    const h = parseInt(a.time.slice(11, 13));
    if (!isNaN(h)) hours[h].requests++;
  });
  return hours;
}

function buildScreenTime(activity) {
  const appTimes = {};
  const sorted = [...activity].sort((a, b) => new Date(a.time) - new Date(b.time));
  sorted.forEach((a, i) => {
    const next = sorted[i + 1];
    if (!next || a.app !== next.app) return;
    const gap = (new Date(next.time) - new Date(a.time)) / 60000;
    if (gap < 5) appTimes[a.app] = (appTimes[a.app] || 0) + gap;
  });
  return Object.entries(appTimes)
    .map(([app, mins]) => ({ app, minutes: Math.round(mins) }))
    .sort((a, b) => b.minutes - a.minutes);
}

function detectSleepIssues(activity) {
  return activity.filter(a => {
    if (!a.time) return false;
    const h = parseInt(a.time.slice(11, 13));
    return h >= 22 || h <= 5;
  });
}

function StatCard({ label, value, icon, color, iconBg, sub }) {
  return (
    <div className="hk-stat" style={{ '--stat-color': color }}>
      <div className="hk-stat-icon" style={{ background: iconBg || `${color}18` }}>
        {icon}
      </div>
      <div className="hk-stat-body">
        <div className="hk-stat-label">{label}</div>
        <div key={value} className="hk-stat-value" style={{ color }}>{value}</div>
        {sub && <div className="hk-stat-sub">{sub}</div>}
      </div>
    </div>
  );
}

function EmptyState({ icon, title, body }) {
  return (
    <div className="hk-empty">
      <span className="hk-empty-icon">{icon}</span>
      <div className="hk-empty-title">{title}</div>
      {body && <div className="hk-empty-body">{body}</div>}
    </div>
  );
}

function SkeletonRows({ n = 6 }) {
  return (
    <div className="hk-feed">
      {Array.from({ length: n }, (_, i) => (
        <div
          key={i}
          className="hk-skeleton hk-skeleton-row"
          style={{ animationDelay: `${i * 0.08}s` }}
        />
      ))}
    </div>
  );
}

const tooltipStyle = {
  background: 'var(--c-surface)',
  border: '1px solid var(--c-border)',
  borderRadius: 8,
  fontSize: 12,
  color: 'var(--c-text)',
};

// ── App ───────────────────────────────────────────────────────
export default function App() {
  const [children, setChildren]         = useState([]);
  const [devices, setDevices]           = useState([]);
  const [tab, setTab]                   = useState('activity');
  const [tabKey, setTabKey]             = useState(0);
  const [child, setChild]               = useState('');
  const [activity, setAct]              = useState([]);
  const [stats, setStats]               = useState([]);
  const [blocked, setBlocked]           = useState([]);
  const [newSite, setNewSite]           = useState('');
  const [schedFrom, setFrom]            = useState('22:00');
  const [schedUntil, setUntil]          = useState('06:00');
  const [dark, setDark]                 = useState(false);
  const [search, setSearch]             = useState('');
  const [filterDate, setDate]           = useState('');
  const [lastUpdate, setLast]           = useState(null);
  const [showAlert, setAlert]           = useState(false);
  const [blockSearch, setBlockSearch]   = useState('');
  const [blockTab, setBlockTab]         = useState('quick');
  const [blockConfirm, setBlockConfirm] = useState(null);
  const [attempts, setAttempts]         = useState([]);
  const [toasts, setToasts]             = useState([]);
  const [firstLoad, setFirstLoad]       = useState(true);
  const [securityEvents, setSecurityEvents] = useState([]);

  const [notifPermission, setNotifPermission] = useState(
    'Notification' in window ? Notification.permission : 'unsupported'
  );

  const lastActivityTsRef = useRef('');
  const notifiedRef       = useRef(new Set());
  const prevLateCountRef  = useRef(0);
  const blockedRef        = useRef([]);

  const addToast = useCallback((msg, type = 'success') => {
    const id = Date.now() + Math.random();
    setToasts(t => [...t, { id, msg, type }]);
    setTimeout(() => setToasts(t => t.filter(x => x.id !== id)), 3800);
  }, []);

  const removeToast = (id) => setToasts(t => t.filter(x => x.id !== id));

  useEffect(() => {
    if ('Notification' in window && Notification.permission === 'default') {
      Notification.requestPermission().then(p => setNotifPermission(p));
    }
  }, []);

  const switchTab = (id) => {
    setTab(id);
    setTabKey(k => k + 1);
  };

  useEffect(() => {
    const loadDevices = () => {
      axios.get(`${API}/connected`).then(r => {
        const connected = r.data || [];
        setDevices(connected);
        const names = [...new Set(connected.map(d => d.name).filter(Boolean))];
        setChildren(names);
        setChild(prev => names.includes(prev) ? prev : (names[0] || ''));
        if (names.length === 0) {
          setFirstLoad(false);
          setAct([]);
          setStats([]);
        }
      }).catch(() => {
        setChildren([]);
        setDevices([]);
        setChild('');
        setFirstLoad(false);
      });
    };
    loadDevices();
    const t = setInterval(loadDevices, 10000);
    return () => clearInterval(t);
  }, []);

  const childInfo = getChildMeta(child);

  useEffect(() => { blockedRef.current = blocked; }, [blocked]);

  const refreshBlocked = useCallback(() => {
    axios.get(`${API}/blocked`).then(r => setBlocked(r.data)).catch(() => {});
  }, []);

  useEffect(() => { refreshBlocked(); }, [refreshBlocked]);

  const load = useCallback(() => {
    if (!child) {
      setFirstLoad(false);
      return;
    }

    Promise.all([
      axios.get(`${API}/activity/${child}?limit=500`),
      axios.get(`${API}/stats/${child}`),
      axios.get(`${API}/security/events?limit=20`),
    ]).then(([actRes, statsRes, secRes]) => {
      const act      = actRes.data;
      const bDomains = blockedRef.current.map(b => b.domain);

      setAct(act);
      setStats(statsRes.data);
      setSecurityEvents(secRes.data || []);
      setLast(new Date());
      setFirstLoad(false);

      setAttempts(act.filter(a => a.status === 'blocked' || bDomains.some(d => a.domain?.includes(d))));

      const lateCount = detectSleepIssues(act).length;
      if (lateCount > prevLateCountRef.current) {
        setAlert(true);
        prevLateCountRef.current = lateCount;
      }

      if (Notification.permission === 'granted' && lastActivityTsRef.current) {
        const newRows = act.filter(a => a.time > lastActivityTsRef.current);
        newRows.forEach(a => {
          const h        = a.time ? parseInt(a.time.slice(11, 13)) : -1;
          const isLate   = h >= 22 || h < 6;
          const isBlocked = a.status === 'blocked' || bDomains.some(d => a.domain?.includes(d));

          if (isLate) {
            const key = `late:${child}:${a.domain}:${a.time?.slice(0, 13)}`;
            if (!notifiedRef.current.has(key)) {
              notifiedRef.current.add(key);
              try {
                new Notification('🌙 HomeKids — Late Night Activity', {
                  body: `${child} visited ${a.domain} at ${a.time?.slice(11, 16)}`,
                  icon: '/favicon.ico',
                  tag:  key,
                });
              } catch (_) {}
            }
          }

          if (isBlocked) {
            const key = `blocked:${child}:${a.domain}:${a.time?.slice(0, 16)}`;
            if (!notifiedRef.current.has(key)) {
              notifiedRef.current.add(key);
              try {
                new Notification('🚫 HomeKids — Blocked Site Attempt', {
                  body: `${child} tried to access ${a.domain}`,
                  icon: '/favicon.ico',
                  tag:  key,
                });
              } catch (_) {}
            }
          }
        });
      }

      if (act.length > 0) {
        const latest = act[0].time;
        if (!lastActivityTsRef.current || latest > lastActivityTsRef.current) {
          lastActivityTsRef.current = latest;
        }
      }
    }).catch(() => {});
  }, [child]);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [load]);

  // ── Actions ──────────────────────────────────────────────────
  const blockSite = (domain) => {
    const site = (domain || newSite).trim().toLowerCase().replace(/^https?:\/\//, '').replace(/\/.*$/, '');
    if (!site) return;
    setNewSite('');
    axios.post(`${API}/block`, null, { params: { domain: site } })
      .then(() => {
        setBlocked(prev => prev.some(b => b.domain === site) ? prev : [...prev, { domain: site, added: new Date().toISOString(), ips: '' }]);
        addToast(`🚫 ${site} blocked for all devices`, 'success');
        return refreshBlocked();
      })
      .catch(() => {
        addToast(`❌ Failed to block ${site}`, 'error');
      });
  };

  const blockCategory = (cat) => {
    const domains = BLOCK_CATEGORIES[cat] || [];
    Promise.all(domains.map(d => axios.post(`${API}/block`, null, { params: { domain: d } })))
      .then(() => {
        addToast(`🚫 ${cat} blocked`, 'success');
        return refreshBlocked();
      })
      .catch(() => {
        addToast(`❌ Failed to block all ${cat} sites`, 'error');
        refreshBlocked();
      });
  };

  const unblockCategory = (cat) => {
    const domains = BLOCK_CATEGORIES[cat] || [];
    Promise.all(domains.map(d => axios.delete(`${API}/block/${encodeURIComponent(d)}`)))
      .then(() => {
        addToast(`✓ ${cat} unblocked`, 'info');
        return refreshBlocked();
      })
      .catch(() => {
        addToast(`❌ Failed to unblock all ${cat} sites`, 'error');
        refreshBlocked();
      });
  };

  const unblockSite = (d) => {
    axios.delete(`${API}/block/${encodeURIComponent(d)}`)
      .then(() => {
        addToast(`✓ ${d} unblocked`, 'info');
        return refreshBlocked();
      })
      .catch(() => {
        addToast(`❌ Failed to unblock ${d}`, 'error');
        refreshBlocked();
      });
  };

  const unblockAll = () => {
    const toRemove = [...blocked];
    Promise.all(toRemove.map(b => axios.delete(`${API}/block/${encodeURIComponent(b.domain)}`)))
      .then(() => {
        addToast('✓ All sites unblocked', 'info');
        return refreshBlocked();
      })
      .catch(() => {
        addToast('❌ Failed to unblock all sites', 'error');
        refreshBlocked();
      });
  };

  const saveSchedule = () => {
    axios.post(`${API}/schedule?child=${child}&block_from=${schedFrom}&block_until=${schedUntil}&days=Mon,Tue,Wed,Thu,Fri`)
      .then(() => addToast('✓ Schedule saved!', 'success'));
  };

  const exportCSV = () => {
    const rows = filteredActivity.map(a => `${a.time},${a.domain},${a.app}`);
    const blob = new Blob([['Time,Domain,App', ...rows].join('\n')], { type: 'text/csv' });
    const a    = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `${child}_activity.csv`;
    a.click();
    addToast(`⬇️ Exported ${filteredActivity.length} rows`, 'info');
  };

  const readSummary = () => {
    const text = `${child} visited ${activity.length} sites. Top app: ${stats[0]?.app || 'none'}. ${blocked.length} sites blocked.`;
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(new SpeechSynthesisUtterance(text));
    addToast('🔊 Reading summary aloud', 'info');
  };

  const renameDevice = (name) => {
    const dev = devices.find(d => d.name === name);
    if (!dev) return;
    const nick = window.prompt(`Rename "${name}" to:`, name);
    if (!nick || !nick.trim() || nick.trim() === name) return;
    const newName = nick.trim();
    axios.put(`${API}/devices/${dev.ip}/name?name=${encodeURIComponent(newName)}`)
      .then(() => {
        addToast(`✏️ Renamed to ${newName}`, 'success');
        setChildren(prev => prev.map(n => n === name ? newName : n));
        setChild(prev => prev === name ? newName : prev);
        setDevices(prev => prev.map(d => d.ip === dev.ip ? { ...d, name: newName, is_named: true } : d));
      })
      .catch(() => addToast('❌ Rename failed', 'error'));
  };

  const togglePause = (name, currentStatus) => {
    const next = !currentStatus;
    axios.post(`${API}/profiles/pause?child_name=${encodeURIComponent(name)}&paused=${next}`)
      .then(() => {
        addToast(next ? `⏸️ Internet paused for ${name}` : `▶️ Internet resumed for ${name}`, next ? 'warn' : 'success');
        setDevices(prev => prev.map(d => d.name === name ? { ...d, paused: next } : d));
      })
      .catch(() => addToast('❌ Failed to update status', 'error'));
  };

  const sendFeedback = (id, useful) => {
    axios.post(`${API}/feedback?event_id=${id}&is_useful=${useful}`)
      .then(() => addToast('🙏 Thanks for your feedback!', 'success'));
  };

  const filteredActivity = activity.filter(a => {
    const ms = !search || a.domain?.toLowerCase().includes(search.toLowerCase()) || a.app?.toLowerCase().includes(search.toLowerCase());
    const md = !filterDate || a.time?.startsWith(filterDate);
    return ms && md;
  });

  const filteredBlocked = blocked.filter(b =>
    !blockSearch || b.domain?.toLowerCase().includes(blockSearch.toLowerCase())
  );

  const lateNight   = detectSleepIssues(activity);
  const screenTime  = buildScreenTime(activity);
  const hourlyData  = buildHourlyData(activity);
  const uniqueSites = [...new Set(activity.map(a => a.domain))].length;
  const topApp      = stats[0]?.app || '—';
  const totalMins   = screenTime.reduce((s, a) => s + a.minutes, 0);
  const pieData     = stats.slice(0, 6).map(s => ({ name: s.app, value: s.count }));

  const currentDevice = devices.find(d => d.name === child);
  const isPaused = currentDevice?.paused || false;

  const isCategoryBlocked = (cat) =>
    (BLOCK_CATEGORIES[cat] || []).every(d => blocked.some(b => b.domain === d));

  const TABS = [
    { id: 'activity',   label: '📋 Activity' },
    { id: 'security',   label: '🛡️ Security' },
    { id: 'monitoring', label: '📡 Monitoring' },
    { id: 'usage',      label: '📊 Usage' },
    { id: 'blocking',   label: '🚫 Blocking' },
  ];

  return (
    <div className="hk-root" data-theme={dark ? 'dark' : 'light'}>

      <div className="hk-toast-container">
        {toasts.map(t => (
          <div key={t.id} className={`hk-toast hk-toast-${t.type}`}>
            {t.msg}
            <button className="hk-toast-x" onClick={() => removeToast(t.id)}>×</button>
          </div>
        ))}
      </div>

      <header className="hk-header">
        <div className="hk-logo">
          <div className="hk-logo-icon">🛡️</div>
          <div>
            <div className="hk-logo-text">HomeKids AI</div>
            <div className="hk-logo-sub">
              {lastUpdate ? `Updated ${timeAgo(lastUpdate.toISOString())}` : children.length === 0 ? 'No devices connected' : 'Connecting...'}
            </div>
          </div>
        </div>

        <div className="hk-header-right">
          <div className="hk-child-switcher">
            {children.length === 0
              ? <span className="hk-loading-txt" style={{ color: 'var(--c-muted)', fontSize: 13 }}>
                  📡 Waiting for devices...
                </span>
              : children.map(name => {
                  const dev = devices.find(d => d.name === name);
                  return (
                    <button
                      key={name}
                      className={`hk-child-btn ${child === name ? 'active' : ''} ${dev?.paused ? 'is-paused' : ''}`}
                      onClick={() => { setChild(name); setAlert(false); }}
                    >
                      {getChildMeta(name).avatar} {name} {dev?.paused && '(paused)'}
                    </button>
                  );
                })
            }
          </div>
          {child && (
            <>
              <button
                className={`hk-icon-btn ${isPaused ? 'paused-active' : ''}`}
                onClick={() => togglePause(child, isPaused)}
                title={isPaused ? "Resume Internet" : "Pause Internet"}
              >
                {isPaused ? '▶️' : '⏸️'}
              </button>
              <button className="hk-icon-btn" onClick={() => renameDevice(child)} title="Rename device">✏️</button>
            </>
          )}
          <button className="hk-icon-btn" onClick={readSummary} title="Read aloud">🔊</button>
          <button className="hk-text-btn" onClick={() => setDark(!dark)}>
            {dark ? '☀️ Light' : '🌙 Dark'}
          </button>
        </div>
      </header>

      <div className="hk-main">

        <div className="hk-stats">
          <StatCard
            label="Security Status" value={currentDevice?.tamper || 'Healthy'} icon="🛡️"
            color={currentDevice?.tamper === 'Healthy' ? '#16a34a' : '#dc2626'} sub="Tamper detection"
          />
          <StatCard
            label="Top App" value={topApp} icon="📱"
            color={APP_COLORS[topApp] || '#f59e0b'}
            sub={`${stats[0]?.count || 0} requests`}
          />
          <StatCard
            label="Screen Time" value={`${totalMins}m`} icon="⏱️"
            color="#7c3aed" sub="estimated"
          />
          <StatCard
            label="Blocked Today" value={attempts.length} icon="🚫"
            color="#dc2626" sub="attempts"
          />
          <StatCard
            label="AI Insights" value={securityEvents.filter(e => e.child_name === child).length}
            icon="🧠" color="#2563eb" sub="total events"
          />
        </div>

        <nav className="hk-tabs">
          {TABS.map(({ id, label }) => (
            <button
              key={id}
              className={`hk-tab ${tab === id ? 'active' : ''}`}
              onClick={() => switchTab(id)}
            >
              {label}
              {id === 'security' && securityEvents.filter(e => e.child_name === child).length > 0 && (
                <span className="hk-tab-badge">{securityEvents.filter(e => e.child_name === child).length}</span>
              )}
            </button>
          ))}
        </nav>

        <div key={tabKey} className="hk-tab-content">

          {/* ═══ ACTIVITY ══════════════════════════════════════ */}
          {tab === 'activity' && (
            <div className="hk-panel">
              <div className="hk-panel-header">
                <h2 className="hk-section-title">📋 {child}'s Activity Log</h2>
                <div className="hk-filter-bar">
                  <input className="hk-input" value={search} onChange={e => setSearch(e.target.value)} placeholder="🔍 Search..." />
                  <button className="hk-btn hk-btn-sm" onClick={exportCSV}>⬇️ Export</button>
                </div>
              </div>
              {filteredActivity.length === 0 ? (
                <div className="hk-feed">
                  <EmptyState
                    icon="📝"
                    title="No activity recorded yet"
                    body="This child has no browsing activity in the current time window. Once DNS events arrive, they’ll appear here."
                  />
                </div>
              ) : (
                <div className="hk-feed">
                  {filteredActivity.map((a, i) => (
                    <div key={i} className={`hk-feed-row ${a.status === 'blocked' ? 'blocked' : ''}`}>
                      <FavIcon domain={a.domain} />
                      <div className="hk-feed-main">
                        <div className="hk-feed-domain">{a.domain}</div>
                        <div className="hk-feed-meta">{a.app} • {timeAgo(a.time)}</div>
                      </div>
                      {a.status === 'blocked' && <span className="hk-badge hk-badge-blocked">Blocked</span>}
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* ═══ SECURITY ══════════════════════════════════════ */}
          {tab === 'security' && (
            <div className="hk-panel">
              <div className="hk-panel-header">
                <h2 className="hk-section-title">🧠 AI Security Center</h2>
                <p className="hk-meta-txt">Local AI analysis of network behavior and risks</p>
              </div>

              <div className="hk-security-feed">
                {securityEvents.filter(e => e.child_name === child).length === 0 ? (
                  <EmptyState icon="🛡️" title="Everything looks good" body="No security anomalies detected on this device yet." />
                ) : (
                  securityEvents.filter(e => e.child_name === child).map((e, i) => (
                    <div key={i} className={`hk-sec-card severity-${e.severity?.toLowerCase()}`}>
                      <div className="hk-sec-header">
                        <span className={`hk-sec-badge sev-${e.severity?.toLowerCase()}`}>{e.severity} Risk</span>
                        <span className="hk-sec-time">{timeAgo(e.ts)}</span>
                      </div>
                      <h3 className="hk-sec-title">{e.event_type?.replace('_', ' ')}</h3>
                      <p className="hk-sec-explanation">"{e.explanation}"</p>

                      <div className="hk-sec-evidence">
                        <strong>Technical Evidence:</strong> {e.detail}
                      </div>

                      <div className="hk-sec-action-box">
                        <div className="hk-sec-rec">
                          <strong>AI Recommendation:</strong> {e.recommendation}
                        </div>
                        <button className="hk-btn hk-btn-primary hk-btn-sm" onClick={() => addToast('Action applied successfully', 'success')}>
                          Apply Recommendation
                        </button>
                      </div>

                      <div className="hk-sec-feedback">
                        <span>Was this insight useful?</span>
                        <button onClick={() => sendFeedback(e.id, true)}>👍</button>
                        <button onClick={() => sendFeedback(e.id, false)}>👎</button>
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}

          {/* ═══ MONITORING ════════════════════════════════════ */}
          {tab === 'monitoring' && (
            <div className="hk-two-col">
              <div className="hk-panel">
                <h3 className="hk-panel-title">⏰ 24h Activity Timeline</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={hourlyData}><XAxis dataKey="hour" /><YAxis /><Tooltip contentStyle={tooltipStyle} /><Bar dataKey="requests" fill="#4f46e5" radius={[4, 4, 0, 0]} /></BarChart>
                </ResponsiveContainer>
              </div>
              <div className="hk-panel">
                <h3 className="hk-panel-title">🥧 App Usage Share</h3>
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart><Pie data={pieData} dataKey="value" nameKey="name" outerRadius={60} label>{pieData.map((e, i) => <Cell key={i} fill={APP_COLORS[e.name] || '#94a3b8'} />)}</Pie><Tooltip /><Legend /></PieChart>
                </ResponsiveContainer>
              </div>
            </div>
          )}

          {/* ═══ USAGE ═════════════════════════════════════════ */}
          {tab === 'usage' && (
            <div className="hk-panel">
              <h2 className="hk-section-title">📊 Time Spent per App</h2>
              {screenTime.map((s, i) => (
                <div key={i} className="hk-st-row">
                  <div className="hk-st-header"><span>{s.app}</span><span>{s.minutes}m</span></div>
                  <div className="hk-bar-track"><div className="hk-bar-fill" style={{ width: `${(s.minutes / (screenTime[0]?.minutes || 1)) * 100}%`, background: APP_COLORS[s.app] || '#94a3b8' }} /></div>
                </div>
              ))}
            </div>
          )}

          {/* ═══ BLOCKING ══════════════════════════════════════ */}
          {tab === 'blocking' && (
            <div className="hk-panel">
              <h2 className="hk-section-title">🚫 Smart Blocking</h2>
              <div className="hk-quick-grid">
                {QUICK_BLOCKS.map(b => (
                  <button
                    key={b.domain}
                    className={`hk-quick-btn ${blocked.some(x => x.domain === b.domain) ? 'is-blocked' : ''}`}
                    onClick={() => blocked.some(x => x.domain === b.domain) ? unblockSite(b.domain) : blockSite(b.domain)}
                    style={blocked.some(x => x.domain === b.domain) ? { borderColor: b.color, background: `${b.color}12`, color: b.color } : {}}
                  >
                    <span>{b.icon}</span> {b.label}
                  </button>
                ))}
              </div>
            </div>
          )}

        </div>
      </div>
    </div>
  );
}
