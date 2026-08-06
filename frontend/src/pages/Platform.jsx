import React, { useCallback, useEffect, useState } from 'react';
import {
  Building2, Users, FileText, Search, Inbox, Server, AlertTriangle, ShieldCheck,
  RefreshCw, Loader2, Database, Clock, Lock, Activity, CheckCircle2, Terminal, ScrollText, HardDriveDownload,
} from 'lucide-react';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import TenantExplorer from '../components/platform/TenantExplorer';
import DatabaseConsole from '../components/platform/DatabaseConsole';
import AuditTrail from '../components/platform/AuditTrail';
import BackupPanel from '../components/platform/BackupPanel';
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
 * Deployment settings still carrying a development value.
 *
 * <p>Ordered by what it costs to leave alone, not by where it appears in the
 * configuration file. A tenant receiving no reports is losing data now; a signing
 * key that resets on restart is an inconvenience until someone reads the log. A
 * list that treats those as equal is a list that gets skimmed.
 */
const deploymentChecks = (r) => [
  !r.secretsKeyConfigured && {
    severity: 'critical',
    title: 'No encryption key.',
    detail: 'Mailbox passwords cannot be stored at all, so no organization can have '
      + 'reports collected automatically.',
    fix: 'SECRETS_KEY',
  },
  !r.mailConfigured && {
    severity: 'critical',
    title: 'No mail host.',
    detail: 'Confirmation and invitation links are written to the log instead of being '
      + 'sent, so nobody can complete a sign-up.',
    fix: 'MAIL_HOST',
  },
  r.publicUrl?.includes('localhost') && {
    severity: 'critical',
    title: 'The public address is still localhost.',
    detail: 'Every emailed link points at this machine and works nowhere else.',
    fix: 'PUBLIC_URL',
  },
  !r.jwtSecretConfigured && {
    severity: 'warning',
    title: 'No signing key.',
    detail: 'A throwaway is generated at startup, so every session ends when the process '
      + 'restarts — and it is printed in the log, where it can be used to forge a token.',
    fix: 'JWT_SECRET',
  },
  r.schemaAutoUpdate && {
    severity: 'warning',
    title: 'The schema updates itself.',
    detail: 'Hibernate may alter live tables at startup. A mistyped entity rewrites real '
      + 'data with nobody approving it.',
    fix: 'DB_DDL_AUTO=validate',
  },
].filter(Boolean);

/**
 * The console for whoever runs the service, not for whoever runs one organization
 * inside it.
 *
 * <p>Three questions, in the order they get asked: is anything wrong, how much is
 * here, and who is it for. The database sits last because it is the only part that
 * can destroy something — reaching it should take a deliberate scroll past
 * everything that answers the question without it.
 */
const Platform = () => {
  usePageTitle('Platform');
  const { token, user } = useAuth();
  const { showToast } = useToast();

  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [collecting, setCollecting] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [refreshedAt, setRefreshedAt] = useState(null);

  const load = useCallback(async () => {
    setRefreshing(true);
    try {
      setData(await api.getPlatformOverview(token));
      setRefreshedAt(new Date());
    } catch (err) {
      setError(err.message);
    } finally {
      setRefreshing(false);
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
  const checks = deploymentChecks(r);
  const critical = checks.filter((c) => c.severity === 'critical').length;
  const healthy = checks.length === 0 && data.mailboxesFailing === 0;

  return (
    <div className="platform-page">
      <header className="platform-header">
        <div className="platform-identity">
          <span className="platform-eyebrow"><Terminal size={13} /> Operator console</span>
          <h1>Platform</h1>
          <p>
            Every organization on this deployment, in counts and health — and the
            schema underneath them.
          </p>
        </div>

        <div className="platform-header-side">
          <div className="platform-operator">
            <span className="platform-operator-dot" />
            <div>
              <strong>{user.username}</strong>
              <span>signed in as operator</span>
            </div>
          </div>
          <button className="btn btn-secondary" onClick={load} disabled={refreshing}>
            {refreshing
              ? <><Loader2 size={15} className="spin" /> Reading…</>
              : <><RefreshCw size={15} /> Refresh</>}
          </button>
          {refreshedAt && (
            <span className="platform-refreshed">
              Read at {refreshedAt.toLocaleTimeString('en-GB',
                { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
            </span>
          )}
        </div>
      </header>

      {/* ── 1. Is anything wrong ── */}
      {healthy ? (
        <section className="platform-healthy">
          <CheckCircle2 size={17} />
          <div>
            <strong>Nothing needs attention.</strong>
            <span>
              Every mailbox collected on its last run, and no deployment setting is
              still carrying a development value.
            </span>
          </div>
        </section>
      ) : (
        <section className={`platform-attention ${critical > 0 ? 'critical' : ''}`}>
          <h2>
            <AlertTriangle size={16} />
            Needs attention
            <span className="attention-count">
              {checks.length + (data.mailboxesFailing > 0 ? 1 : 0)}
            </span>
          </h2>

          <ul>
            {data.mailboxesFailing > 0 && (
              <li className="urgent">
                <strong>
                  {data.mailboxesFailing} mailbox{data.mailboxesFailing === 1 ? '' : 'es'} failed
                  the last collection.
                </strong>
                <span>
                  Those organizations are receiving nothing and have no way of knowing
                  it. Open the organization below to see which.
                </span>
              </li>
            )}
            {checks.map((c) => (
              <li key={c.title} className={c.severity}>
                <strong>{c.title}</strong>
                <span>{c.detail} Set <code>{c.fix}</code>.</span>
              </li>
            ))}
          </ul>
        </section>
      )}

      {/* ── 2. How much is here ── */}
      <h2 className="platform-section-title"><Activity size={15} /> This deployment</h2>

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

      {/* ── 3. Who it is for ── */}
      <h2 className="platform-section-title"><Building2 size={15} /> Tenants</h2>
      <TenantExplorer currentUsername={user?.username} onChange={load} />

      {/* ── 4. Is there a second copy ── */}
      <h2 className="platform-section-title"><HardDriveDownload size={15} /> Backups</h2>
      <BackupPanel />

      {/* ── 5. Who did what ── */}
      <h2 className="platform-section-title"><ScrollText size={15} /> Accountability</h2>
      <AuditTrail />

      {/* ── 6. What is underneath ── */}
      <h2 className="platform-section-title"><Database size={15} /> Storage</h2>
      <DatabaseConsole />
    </div>
  );
};

export default Platform;
