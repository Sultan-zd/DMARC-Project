import React from 'react';
import { Link } from 'react-router-dom';
import { ShieldAlert, ShieldCheck, ArrowRight, TrendingUp, TrendingDown, Search } from 'lucide-react';
import './DomainPosture.css';

/** Policy strength, from most to least exposed. */
const POLICY_LABEL = {
  reject: { text: 'Reject', tone: 'good', hint: 'Spoofed mail is rejected outright.' },
  quarantine: { text: 'Quarantine', tone: 'warn', hint: 'Spoofed mail lands in spam, not blocked.' },
  none: { text: 'Monitor only', tone: 'bad', hint: 'Nothing is blocked. Anyone can spoof this domain.' },
};

const gradeTone = (score) => (score >= 90 ? 'good' : score >= 70 ? 'warn' : 'bad');

/**
 * Configuration posture per domain, weakest first.
 *
 * Deliberately separate from the traffic figures above it: this says whether a
 * domain *can* be spoofed, while those say what was actually sent. A domain can be
 * flawlessly configured and still show failures, and vice versa.
 */
const DomainPosture = ({ domains = [] }) => {
  if (domains.length === 0) {
    return (
      <div className="posture-card glass-card">
        <div className="posture-head">
          <h3>Domain posture</h3>
        </div>
        <div className="posture-empty">
          <Search size={30} />
          <p>No domain has been analysed yet.</p>
          <Link to="/analysis" className="btn-primary">Analyse a domain</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="posture-card glass-card">
      <div className="posture-head">
        <h3>Domain posture</h3>
        <span className="posture-sub">How your domains are configured · weakest first</span>
      </div>

      <ul className="posture-list">
        {domains.map((d) => {
          const policy = POLICY_LABEL[d.policy] ?? {
            text: 'No DMARC', tone: 'bad',
            hint: 'No DMARC record published — the domain is unprotected.',
          };
          const delta = d.previousScore == null ? null : d.score - d.previousScore;

          return (
            <li key={d.domain} className="posture-row">
              <div className={`posture-score tone-${gradeTone(d.score)}`}>
                <span className="posture-grade">{d.grade}</span>
                <span className="posture-value">{d.score}</span>
              </div>

              <div className="posture-main">
                <div className="posture-domain">
                  <span className="posture-name">{d.domain}</span>
                  {delta != null && delta !== 0 && (
                    <span className={`posture-delta ${delta > 0 ? 'up' : 'down'}`}>
                      {delta > 0 ? <TrendingUp size={13} /> : <TrendingDown size={13} />}
                      {delta > 0 ? '+' : ''}{delta}
                    </span>
                  )}
                </div>

                <div className="posture-facts">
                  <span className={`posture-pill tone-${policy.tone}`} title={policy.hint}>
                    {policy.text}
                    {d.pct != null && d.pct < 100 && ` · ${d.pct}%`}
                  </span>

                  {/* A weaker subdomain policy is the gap attackers actually use. */}
                  {d.subdomainPolicy && d.subdomainPolicy !== d.policy && (
                    <span className="posture-pill tone-warn"
                          title="Subdomains follow a weaker policy than the domain itself.">
                      Subdomains: {d.subdomainPolicy}
                    </span>
                  )}

                  {!d.reportingConfigured && (
                    <span className="posture-pill tone-bad"
                          title="No rua address, so no aggregate reports are sent to you.">
                      No reporting
                    </span>
                  )}

                  {d.dkimStatus === 'indeterminate' && (
                    <span className="posture-pill tone-muted"
                          title="DKIM selectors cannot be listed from DNS; import this domain's reports to resolve it.">
                      DKIM unverified
                    </span>
                  )}
                </div>
              </div>

              <div className="posture-traffic">
                {d.reportCount > 0 ? (
                  <Link to={`/reports?domain=${encodeURIComponent(d.domain)}`} className="posture-link">
                    {d.reportCount} report{d.reportCount > 1 ? 's' : ''} <ArrowRight size={13} />
                  </Link>
                ) : (
                  <span className="posture-none" title="No aggregate reports ingested for this domain yet.">
                    no traffic data
                  </span>
                )}
              </div>
            </li>
          );
        })}
      </ul>

      <p className="posture-foot">
        {domains.some((d) => d.policy === 'none' || !d.policy)
          ? <><ShieldAlert size={14} /> At least one domain can still be spoofed.</>
          : <><ShieldCheck size={14} /> Every analysed domain enforces a DMARC policy.</>}
      </p>
    </div>
  );
};

export default DomainPosture;
