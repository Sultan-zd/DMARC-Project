import React, { useCallback, useEffect, useState } from 'react';
import {
  Building2, Users, FileText, Search, Inbox, Server, AlertTriangle, ShieldCheck,
  RefreshCw, Loader2, Database, Clock, Lock,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import TenantExplorer from '../components/platform/TenantExplorer';
import DatabaseConsole from '../components/platform/DatabaseConsole';
import * as api from '../services/api';
import './Platform.css';

const number = (value) => new Intl.NumberFormat('en-GB').format(value ?? 0);

const since = (minutes) => {
  if (minutes < 60) return `${minutes} min`;
  if (minutes < 1440) return `${Math.floor(minutes / 60)} h ${minutes % 60} min`;
  return `${Math.floor(minutes / 1440)} d ${Math.floor((minutes % 1440) / 60)} h`;
};

const day = (iso) =>
  new Date(iso).toLocaleDateString('en-GB', { day: '2-digit', month: 'short' });

const when = (value) =>
  value ? new Date(value).toLocaleString('en-GB', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }) : '—';

/**
 * The console for whoever runs the service, not for whoever runs one organization
 * inside it.
 *
 * There is a line this page does not cross. It reports that a tenant holds four
 * hundred reports; it never shows what is in them. An aggregate report names every
 * IP address sending as a domain, and keeping tenants apart is the product's main
 * promise — a console that quietly exempted the operator would make that promise
 * conditional on trust rather than on design.
 */
const Platform = () => {
  usePageTitle('Platform');
  const { token, user } = useAuth();
  const { showToast } = useToast();

  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [collecting, setCollecting] = useState(false);

  const load = useCallback(async () => {
    try {
      setData(await api.getPlatformOverview(token));
    } catch (err) {
      setError(err.message);
    }
  }, [token]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const collectNow = async () => {
    setCollecting(true);
    try {
      const { detail } = await api.runPlatformCollection(token);
      showToast(detail, 'success');
      await load();
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setCollecting(false);
    }
  };

  if (!user?.platform_operator) {
    return (
      <div className="platform-denied glass-card">
        <Lock size={48} />
        <h2>Not available</h2>
        <p>
          This console is for whoever operates the service. Access comes from the
          deployment configuration, not from a role that can be granted here.
        </p>
      </div>
    );
  }

  if (error) {
    return <div className="platform-denied glass-card"><AlertTriangle size={40} /><p>{error}</p></div>;
  }
  if (!data) {
    return <div className="platform-page"><p className="platform-loading">Reading the service…</p></div>;
  }

  const r = data.runtime;
  const peak = Math.max(1, ...data.signupsByDay.map((d) => d.count));

  const checks = [
    !r.jwtSecretConfigured && 'JWT_SECRET is unset — sessions end at every restart.',
    !r.secretsKeyConfigured && 'SECRETS_KEY is unset — mailbox passwords cannot be stored.',
    r.schemaAutoUpdate && 'The schema updates itself — set DB_DDL_AUTO=validate.',
    !r.mailConfigured && 'No mail host — confirmation links go to the log, not to people.',
    r.publicUrl?.includes('localhost') && 'PUBLIC_URL still points at localhost — emailed links work nowhere else.',
  ].filter(Boolean);

  return (
    <div className="platform-page">
      <div className="platform-header">
        <div>
          <h1>Platform</h1>
          <p>Every organization on this deployment, in counts and health.</p>
        </div>
        <button className="btn btn-secondary" onClick={load}>
          <RefreshCw size={15} /> Refresh
        </button>
      </div>

      {/* Things that need doing, before anything that is merely interesting. */}
      {(checks.length > 0 || data.mailboxesFailing > 0) && (
        <section className="platform-attention">
          <h2><AlertTriangle size={16} /> Needs attention</h2>
          <ul>
            {data.mailboxesFailing > 0 && (
              <li className="urgent">
                {data.mailboxesFailing} mailbox{data.mailboxesFailing === 1 ? '' : 'es'} failed
                the last collection — those tenants are receiving nothing.
              </li>
            )}
            {checks.map((c) => <li key={c}>{c}</li>)}
          </ul>
        </section>
      )}

      <div className="platform-tiles">
        <article className="platform-tile">
          <header><Building2 size={16} /> Organizations</header>
          <strong>{number(data.organizations)}</strong>
          <span>{data.organizationsNewThisWeek} new this week</span>
        </article>

        <article className="platform-tile">
          <header><Users size={16} /> Accounts</header>
          <strong>{number(data.accountsActive)}</strong>
          <span>
            active of {number(data.accountsTotal)} ·{' '}
            {data.accountsWithTwoFactor} with two-step
          </span>
          <ul className="tile-breakdown">
            {Object.entries(data.accountsByRole).sort(([, a], [, b]) => b - a).map(([role, n]) => (
              <li key={role}><span>{role}</span><span>{n}</span></li>
            ))}
          </ul>
        </article>

        <article className="platform-tile">
          <header><FileText size={16} /> Reports processed</header>
          <strong>{number(data.reportsStored)}</strong>
          <span>{number(data.messagesCovered)} messages · newest {when(data.newestReportAt)}</span>
        </article>

        <article className="platform-tile">
          <header><Search size={16} /> Analyses</header>
          <strong>{number(data.analysesRun)}</strong>
          <span>{number(data.publicScans)} anonymous scans from the landing page</span>
        </article>

        <article className={`platform-tile ${data.mailboxesFailing > 0 ? 'attention' : ''}`}>
          <header><Inbox size={16} /> Mailboxes</header>
          <strong>{data.mailboxesConfigured}</strong>
          <span>{data.mailboxesPolling} collecting automatically</span>
          {data.mailboxesFailing > 0 && (
            <span className="tile-alarm">{data.mailboxesFailing} failing</span>
          )}
          <button className="btn btn-secondary tile-action" onClick={collectNow} disabled={collecting}>
            {collecting
              ? <><Loader2 size={14} className="spin" /> Running…</>
              : <><RefreshCw size={14} /> Collect now</>}
          </button>
        </article>

        <article className="platform-tile">
          <header><ShieldCheck size={16} /> Invitations</header>
          <strong>{data.invitationsPending}</strong>
          <span>links outstanding across every tenant</span>
        </article>
      </div>

      <div className="platform-columns">
        {/* Sign-ups over time */}
        <section className="platform-card">
          <h2>Sign-ups · last 14 days</h2>
          {peak === 1 && data.signupsByDay.every((d) => d.count === 0) ? (
            <p className="platform-empty">No accounts created in this period.</p>
          ) : (
            <div className="signup-chart">
              {data.signupsByDay.map((d) => (
                <div className="signup-bar" key={d.day} title={`${d.day}: ${d.count}`}>
                  <div className="signup-fill" style={{ height: `${(d.count / peak) * 100}%` }}>
                    {d.count > 0 && <span>{d.count}</span>}
                  </div>
                  <span className="signup-day">{day(d.day)}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        {/* Runtime */}
        <section className="platform-card">
          <h2><Server size={16} /> Runtime</h2>
          <dl className="runtime-list">
            <div><dt>Version</dt><dd className="mono">{r.version}</dd></div>
            <div><dt><Clock size={13} /> Uptime</dt><dd>{since(r.uptimeMinutes)}</dd></div>
            <div><dt>Java</dt><dd className="mono">{r.javaVersion}</dd></div>
            <div><dt><Database size={13} /> Database</dt><dd className="mono">{r.database}</dd></div>
            <div><dt>Schema size</dt><dd>{r.databaseSizeMb} MB</dd></div>
            <div>
              <dt>Heap</dt>
              <dd>
                {r.heapUsedMb} / {r.heapMaxMb} MB
                <span className="heap-bar">
                  <span style={{ width: `${Math.min(100, (r.heapUsedMb / r.heapMaxMb) * 100)}%` }} />
                </span>
              </dd>
            </div>
            <div><dt>Public URL</dt><dd className="mono wrap">{r.publicUrl}</dd></div>
          </dl>
        </section>
      </div>

      <TenantExplorer currentUsername={user?.username} onChange={load} />

      <DatabaseConsole />

    </div>
  );
};

export default Platform;
