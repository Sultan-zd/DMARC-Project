import React, { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, RefreshCw, Link2, Check, AlertTriangle, Loader2,
  CheckCircle2, XCircle, HelpCircle, ChevronDown, Info, MinusCircle,
} from 'lucide-react';
import PublicNav from '../components/layout/PublicNav';
import ScanVerdict from '../components/reports/ScanVerdict';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './ScanResult.css';

const SEVERITY_ORDER = { critical: 0, high: 1, warning: 2, medium: 3, info: 4, low: 5, success: 6 };

/** What each check actually protects against, in one line. */
const CHECK_PURPOSE = {
  DMARC: 'Tells receivers what to do with mail that fails authentication, and asks them to report it.',
  SPF: 'Lists which servers are allowed to send mail for this domain.',
  DKIM: 'Cryptographic signature proving a message was not forged or altered in transit.',
  MX: 'Where mail addressed to this domain is delivered.',
  BIMI: 'Displays a verified brand logo in supporting inboxes. Not a security control.',
};

const STATUS_META = {
  found: { Icon: CheckCircle2, tone: 'good', label: 'Configured' },
  not_found: { Icon: XCircle, tone: 'bad', label: 'Missing' },
  indeterminate: { Icon: HelpCircle, tone: 'muted', label: 'Could not verify' },
  error: { Icon: AlertTriangle, tone: 'warn', label: 'Lookup failed' },
};

/**
 * BIMI is optional and carries no score, so an absent record is not a failure.
 * Showing it in red beside genuine gaps overstated it — a domain with no BIMI is
 * not less secure, it just has no logo in the inbox.
 */
const statusMeta = (record) => {
  if (record.type === 'BIMI' && record.status !== 'found') {
    return { Icon: MinusCircle, tone: 'muted', label: 'Not published · optional' };
  }
  return STATUS_META[record.status] ?? STATUS_META.error;
};

/**
 * Public, shareable scan result at /scan/:domain.
 *
 * A stored result is shown when one exists, so opening a shared link costs no DNS
 * traffic. Only an explicit re-scan, or a domain nobody has scanned yet, triggers a
 * fresh lookup.
 */
const ScanResult = () => {
  const { domain } = useParams();
  const navigate = useNavigate();
  usePageTitle(domain ? `${domain} — email security report` : 'Scan result');

  const [result, setResult] = useState(null);
  const [status, setStatus] = useState('loading');
  const [error, setError] = useState(null);
  const [copied, setCopied] = useState(false);
  const [expanded, setExpanded] = useState({});

  const runScan = useCallback(async () => {
    setStatus('scanning');
    setError(null);
    try {
      setResult(await api.publicScan(domain));
      setStatus('ready');
    } catch (err) {
      setError(err);
      setStatus('error');
    }
  }, [domain]);

  useEffect(() => {
    let cancelled = false;

    const load = async () => {
      setStatus('loading');
      setError(null);
      try {
        // Prefer a stored result: a shared link must not drive DNS queries.
        const stored = await api.getPublicScan(domain);
        if (!cancelled) {
          setResult(stored);
          setStatus('ready');
        }
      } catch (err) {
        if (cancelled) return;
        if (err.status === 404) {
          await runScan();
        } else {
          setError(err);
          setStatus('error');
        }
      }
    };

    load();
    return () => { cancelled = true; };
  }, [domain, runScan]);

  const copyLink = async () => {
    try {
      await navigator.clipboard.writeText(window.location.href);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // Clipboard is unavailable over plain HTTP in some browsers; the URL bar still
      // holds the shareable link, so this is not worth interrupting for.
    }
  };

  const busy = status === 'loading' || status === 'scanning';
  const records = result?.records ?? [];
  const recommendations = [...(result?.recommendations ?? [])]
    .sort((a, b) => (SEVERITY_ORDER[a.severity] ?? 9) - (SEVERITY_ORDER[b.severity] ?? 9));

  // What needs doing, separated from what is already fine.
  const actions = recommendations.filter((r) => r.severity !== 'success');
  const passing = recommendations.filter((r) => r.severity === 'success');

  const dmarc = records.find((r) => r.type === 'DMARC');
  const policy = dmarc?.parsed?.p ?? null;
  const pct = dmarc?.parsed?.pct != null ? Number(dmarc.parsed.pct) : null;

  return (
    <div className="scan-page">
      <PublicNav />

      <main className="scan-main">
        <Link to="/" className="scan-back">
          <ArrowLeft size={16} /> Analyse another domain
        </Link>

        {busy && (
          <div className="scan-state">
            <Loader2 size={30} className="spin" />
            <p>{status === 'scanning' ? `Querying DNS records for ${domain}…` : 'Loading…'}</p>
          </div>
        )}

        {status === 'error' && (
          <div className="scan-state scan-state-error">
            <AlertTriangle size={30} />
            <h1>Could not analyse {domain}</h1>
            <p>{error?.message}</p>
            {error?.retryAfterSeconds ? (
              <p className="scan-retry">Try again in {error.retryAfterSeconds} seconds.</p>
            ) : (
              <button className="btn-primary" onClick={runScan}>Try again</button>
            )}
            <button className="scan-link-btn" onClick={() => navigate('/')}>
              Analyse a different domain
            </button>
          </div>
        )}

        {status === 'ready' && result && (
          <>
            <header className="scan-header">
              <div>
                <p className="scan-eyebrow">Email security report</p>
                <p className="scan-meta">
                  Scanned {new Date(result.analyzed_at).toLocaleString('en-US')}
                </p>
              </div>

              <div className="scan-header-actions">
                <button className="scan-action" onClick={copyLink}>
                  {copied ? <Check size={16} /> : <Link2 size={16} />}
                  {copied ? 'Link copied' : 'Copy link'}
                </button>
                <button className="scan-action" onClick={runScan}>
                  <RefreshCw size={16} /> Re-scan
                </button>
              </div>
            </header>

            {/* The answer, before any number. */}
            <ScanVerdict
              domain={result.domain}
              policy={policy}
              pct={pct}
              score={result.score?.score ?? 0}
              grade={result.score?.grade ?? 'F'}
            />

            {/* Per-check summary strip: the whole posture at a glance. */}
            <section className="scan-strip">
              {records.map((record) => {
                const meta = statusMeta(record);
                const points = result.score?.breakdown?.[record.type];
                return (
                  <div key={record.type} className={`strip-item tone-${meta.tone}`}>
                    <meta.Icon size={18} />
                    <span className="strip-type">{record.type}</span>
                    <span className="strip-label">{meta.label}</span>
                    {points != null && record.type !== 'BIMI' && (
                      <span className="strip-points">{points} pts</span>
                    )}
                  </div>
                );
              })}
            </section>

            {actions.length > 0 && (
              <section className="scan-section">
                <h2>What to fix, in order</h2>
                <p className="scan-section-lead">
                  Ordered by how much each one reduces the risk of your domain being used
                  to impersonate you.
                </p>

                <ol className="scan-actions">
                  {actions.map((rec, index) => (
                    <li key={index} className={`scan-action-item sev-${rec.severity}`}>
                      <span className="scan-action-index">{index + 1}</span>
                      <div>
                        <div className="scan-action-head">
                          <span className={`scan-sev sev-${rec.severity}`}>{rec.severity}</span>
                          {rec.category && <span className="scan-cat">{rec.category}</span>}
                        </div>
                        <p className="scan-action-message">{rec.message}</p>
                        {rec.action && <p className="scan-action-fix">{rec.action}</p>}
                      </div>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            {/* Full detail per check, raw record included. */}
            <section className="scan-section">
              <h2>Records found</h2>

              <div className="scan-records">
                {records.map((record) => {
                  const meta = statusMeta(record);
                  const open = expanded[record.type];
                  const hasDetail = record.rawRecord || Object.keys(record.parsed ?? {}).length > 0;

                  return (
                    <article key={record.type} className={`record tone-${meta.tone}`}>
                      <button
                        className="record-head"
                        onClick={() => setExpanded({ ...expanded, [record.type]: !open })}
                        aria-expanded={!!open}
                        disabled={!hasDetail}
                      >
                        <meta.Icon size={18} className="record-icon" />
                        <span className="record-type">{record.type}</span>
                        <span className="record-summary">{record.summary}</span>
                        {hasDetail && (
                          <ChevronDown size={16} className={`record-chevron ${open ? 'open' : ''}`} />
                        )}
                      </button>

                      <p className="record-purpose">{CHECK_PURPOSE[record.type]}</p>

                      {open && hasDetail && (
                        <div className="record-detail">
                          {record.rawRecord && (
                            <>
                              <h4>Published record</h4>
                              <pre className="record-raw">{record.rawRecord}</pre>
                            </>
                          )}
                          {Object.keys(record.parsed ?? {}).length > 0 && (
                            <>
                              <h4>Parsed</h4>
                              <dl className="record-parsed">
                                {Object.entries(record.parsed).map(([key, value]) => (
                                  <React.Fragment key={key}>
                                    <dt>{key}</dt>
                                    <dd>{String(value)}</dd>
                                  </React.Fragment>
                                ))}
                              </dl>
                            </>
                          )}
                        </div>
                      )}
                    </article>
                  );
                })}
              </div>

              <p className="scan-trust">
                <Info size={14} />
                These are the same public DNS records that Google, Yahoo and Microsoft read
                when they decide whether to deliver a message.
              </p>
            </section>

            {passing.length > 0 && (
              <section className="scan-section">
                <h2>Already in place</h2>
                <ul className="scan-passing">
                  {passing.map((rec, index) => (
                    <li key={index}>
                      <CheckCircle2 size={16} />
                      <span>{rec.message}</span>
                    </li>
                  ))}
                </ul>
              </section>
            )}

            <aside className="scan-cta">
              <h2>A configuration check is only half the picture</h2>
              <p>
                This report shows how {result.domain} is set up. It cannot show who is
                actually sending mail in its name — that comes from the DMARC reports
                mailbox providers send you. Import them here to see every source, and
                which ones are impersonating you.
              </p>
              <div className="scan-cta-actions">
                <Link to="/register" className="btn-primary">Create an account</Link>
                <Link to="/login" className="scan-cta-secondary">Sign in</Link>
              </div>
            </aside>
          </>
        )}
      </main>
    </div>
  );
};

export default ScanResult;
