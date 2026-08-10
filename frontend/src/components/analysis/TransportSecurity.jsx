import React, { useCallback, useEffect, useState } from 'react';
import {
  ShieldCheck, Lock, Unlock, AlertTriangle, Loader2, Server, CalendarClock, Info,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import * as api from '../../services/api';
import './TransportSecurity.css';

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleDateString('en-GB', { day: 'numeric', month: 'short', year: 'numeric' })
    : '—';

/** How urgent an expiry is, which is not the same as how far away it is. */
const expiryTone = (days) => {
  if (days === null || days === undefined) return 'muted';
  if (days < 0) return 'bad';
  if (days <= 14) return 'bad';
  if (days <= 30) return 'warn';
  return 'good';
};

const expiryText = (days) => {
  if (days === null || days === undefined) return 'unknown';
  if (days < 0) return `expired ${Math.abs(days)} day${Math.abs(days) === 1 ? '' : 's'} ago`;
  if (days === 0) return 'expires today';
  return `${days} day${days === 1 ? '' : 's'} left`;
};

/**
 * Whether mail to this domain travels encrypted.
 *
 * <p>Loaded separately from the analysis rather than with it. The declared half is
 * DNS, but reaching each mail server and asking it four questions takes seconds —
 * folding it into the main check would make every analysis wait for the slowest
 * thing on the page.
 *
 * <p>Graded on its own scale and shown beside the /100 rather than inside it: a
 * fifth control folded into a score built from four would take points from the
 * others, and a domain that changed nothing would drop a grade overnight.
 */
const TransportSecurity = ({ domain }) => {
  const { token } = useAuth();
  const [result, setResult] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const load = useCallback(async () => {
    if (!domain) return;
    setLoading(true);
    setError('');
    setResult(null);
    try {
      setResult(await api.checkTransportSecurity(token, domain));
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  }, [token, domain]);

  useEffect(() => { load(); }, [load]);

  if (!domain) return null;

  if (loading) {
    return (
      <section className="transport-card glass-card">
        <h2><ShieldCheck size={20} /> Transport security</h2>
        <p className="transport-loading">
          <Loader2 size={16} className="spin" />
          Reading MTA-STS, then connecting to each mail server…
        </p>
      </section>
    );
  }

  if (error) {
    return (
      <section className="transport-card glass-card">
        <h2><ShieldCheck size={20} /> Transport security</h2>
        <p className="transport-error"><AlertTriangle size={15} /> {error}</p>
      </section>
    );
  }

  if (!result) return null;

  const d = result.declared;

  return (
    <section className="transport-card glass-card">
      <div className="transport-head">
        <div>
          <h2><ShieldCheck size={20} /> Transport security</h2>
          <p className="transport-lead">
            DMARC, SPF and DKIM say <em>who may send</em> as {result.domain}. This says
            whether what is sent <em>to</em> it can be read on the way.
          </p>
        </div>
        <div className={`transport-grade grade-${result.grade.replace('+', 'plus').toLowerCase()}`}>
          <strong>{result.grade}</strong>
          <span>{result.score}/100</span>
          <em>separate from the score above</em>
        </div>
      </div>

      {/* ── What the domain declares ── */}
      <h3 className="transport-section">What {result.domain} declares</h3>
      <div className="transport-declared">
        <div className={`declared-item ${d.mtaStsPolicy && d.mtaStsMode === 'enforce' ? 'good'
          : d.mtaStsRecord ? 'warn' : 'bad'}`}>
          <span className="declared-name">MTA-STS</span>
          <span className="declared-value">
            {d.mtaStsPolicy ? `policy in ${d.mtaStsMode} mode`
              : d.mtaStsRecord ? 'record published, policy unreadable'
                : 'not published'}
          </span>
          <span className="declared-note">
            {d.mtaStsMode === 'enforce'
              ? 'Senders must use TLS with a valid certificate, or not deliver at all.'
              : d.mtaStsMode === 'testing'
                ? 'Failures are reported but mail still goes through unencrypted.'
                : 'Without it, an attacker in the path can strip STARTTLS and the '
                  + 'message travels in the clear.'}
          </span>
        </div>

        <div className={`declared-item ${d.tlsRpt ? 'good' : 'bad'}`}>
          <span className="declared-name">TLS-RPT</span>
          <span className="declared-value">{d.tlsRpt ? 'reporting address set' : 'not published'}</span>
          <span className="declared-note">
            {d.tlsRpt
              ? d.tlsRptAddresses
              : 'TLS delivery failures go unreported, so a broken certificate is found '
                + 'by somebody complaining rather than by a report.'}
          </span>
        </div>
      </div>

      {d.mtaStsMx?.length > 0 && (
        <p className="transport-mx-list">
          Policy names: {d.mtaStsMx.map((mx) => <code key={mx}>{mx}</code>)}
        </p>
      )}

      {/* ── What the servers offered ── */}
      <h3 className="transport-section">What the mail servers offered</h3>

      {result.probeUnavailableReason ? (
        <p className="transport-unavailable">
          <Info size={15} />
          <span>{result.probeUnavailableReason}</span>
        </p>
      ) : (
        <div className="transport-hosts">
          {result.hosts.map((host) => (
            <article key={host.host} className={`host-card ${host.reachable ? '' : 'unreachable'}`}>
              <header>
                <Server size={15} />
                <span className="host-name">{host.host}</span>
                <span className="host-priority">priority {host.priority}</span>
                {host.daneTlsa && <span className="host-dane">DANE</span>}
              </header>

              {!host.reachable ? (
                <p className="host-note">{host.unreachableReason}</p>
              ) : (
                <>
                  <div className="host-cert">
                    <div>
                      <span className="cert-label"><CalendarClock size={13} /> Certificate</span>
                      <span className={`cert-expiry ${expiryTone(host.daysToExpiry)}`}>
                        {expiryText(host.daysToExpiry)}
                      </span>
                      <span className="cert-dates">
                        valid to {formatDate(host.certificateNotAfter)}
                      </span>
                    </div>
                    <div>
                      <span className="cert-label">Issued by</span>
                      <span className="cert-issuer">{commonName(host.certificateIssuer)}</span>
                      <span className="cert-dates">
                        {host.keyAlgorithm} {host.keyBits ? `${host.keyBits} bits` : ''} ·{' '}
                        {host.signatureAlgorithm}
                      </span>
                    </div>
                  </div>

                  <div className="host-protocols">
                    {host.protocols.map((p) => (
                      <span
                        key={p.version}
                        className={`protocol ${p.accepted
                          ? (p.deprecated ? 'accepted-bad' : 'accepted-good')
                          : 'refused'}`}
                        title={p.deprecated
                          ? 'Deprecated by RFC 8996'
                          : 'Current, and what modern senders use'}
                      >
                        {p.accepted ? <Unlock size={11} /> : <Lock size={11} />}
                        {p.version}
                      </span>
                    ))}
                  </div>

                  {host.notes.map((note) => (
                    <p key={note} className="host-note warn">
                      <AlertTriangle size={13} /> {note}
                    </p>
                  ))}
                </>
              )}
            </article>
          ))}
        </div>
      )}

      {/* ── What to do ── */}
      {result.findings.length > 0 && (
        <>
          <h3 className="transport-section">What to fix</h3>
          <ul className="transport-findings">
            {result.findings.map((finding) => <li key={finding}>{finding}</li>)}
          </ul>
        </>
      )}
    </section>
  );
};

/** `CN=R11, O=Let's Encrypt, C=US` reads better as just the common name. */
const commonName = (distinguishedName) => {
  if (!distinguishedName) return '—';
  const match = /CN=([^,]+)/i.exec(distinguishedName);
  return match ? match[1] : distinguishedName;
};

export default TransportSecurity;
