import React from 'react';
import { Users, Mail, Globe, FileText, Inbox, AlertTriangle, CheckCircle2 } from 'lucide-react';
import { roleLabel } from '../../utils/roles';
import './OperationsOverview.css';

const formatNumber = (value) => new Intl.NumberFormat('en-GB').format(value ?? 0);

const formatDate = (value) =>
  value
    ? new Date(value).toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' })
    : null;

/**
 * The state of the organization, above the controls that change it.
 *
 * Every figure is counted server-side for the caller's own tenant. The mailbox tile
 * reports whether the scheduled poll is actually running rather than whether a
 * server address happens to be configured — an address with no password polls
 * nothing, which is easy to mistake for working.
 */
const OperationsOverview = ({ overview }) => {
  if (!overview) return null;

  const roles = Object.entries(overview.accountsByRole ?? {})
    .sort(([, a], [, b]) => b - a);

  const window = [formatDate(overview.reportingWindowStart), formatDate(overview.reportingWindowEnd)];

  return (
    <div className="ops-overview">
      <article className="ops-card">
        <header><Users size={17} /> Accounts</header>
        <strong>{overview.accountsActive}</strong>
        <span className="ops-sub">
          active
          {overview.accountsTotal !== overview.accountsActive &&
            ` · ${overview.accountsTotal - overview.accountsActive} disabled`}
        </span>
        <ul className="ops-breakdown">
          {roles.map(([role, count]) => (
            <li key={role}>
              <span>{roleLabel(role)}</span>
              <span className="ops-count">{count}</span>
            </li>
          ))}
        </ul>
      </article>

      <article className="ops-card">
        <header><Mail size={17} /> Invitations</header>
        <strong>{overview.invitationsPending}</strong>
        <span className="ops-sub">awaiting acceptance</span>
        <p className="ops-note">
          {overview.invitationsPending === 0
            ? 'Nobody has an unused link to this organization.'
            : 'Each link works once and expires on its own.'}
        </p>
      </article>

      <article className="ops-card">
        <header><Globe size={17} /> Claimed domains</header>
        <strong>{overview.domainsVerified}</strong>
        <span className="ops-sub">
          verified of {overview.domainsClaimed} claimed
        </span>
        <p className="ops-note">
          {overview.domainsVerified > 0
            ? 'Colleagues signing up at these domains join this organization automatically.'
            : 'Until a claim is verified it grants nothing.'}
        </p>
      </article>

      <article className="ops-card">
        <header><FileText size={17} /> Reports held</header>
        <strong>{formatNumber(overview.reportsStored)}</strong>
        <span className="ops-sub">
          covering {formatNumber(overview.messagesCovered)} messages
        </span>
        <p className="ops-note">
          {window[0] ? `${window[0]} → ${window[1]}` : 'No aggregate reports imported yet.'}
        </p>
      </article>

      <article className={`ops-card ${overview.mailboxConfigured ? '' : 'attention'}`}>
        <header><Inbox size={17} /> Mailbox ingestion</header>
        <strong className="ops-state">
          {overview.mailboxConfigured
            ? <><CheckCircle2 size={18} /> Running</>
            : <><AlertTriangle size={18} /> Not connected</>}
        </strong>
        <span className="ops-sub mono">{overview.mailboxAddress || 'no mailbox set'}</span>
        <p className="ops-note">
          {overview.mailboxConfigured
            ? `Checked every ${overview.mailboxPollMinutes} minutes.`
            : 'No mailbox password is set, so nothing is collected automatically. Reports can still be uploaded below.'}
        </p>
      </article>

      <article className="ops-card">
        <header><AlertTriangle size={17} /> Weakest domains</header>
        <strong>{overview.domainsAnalysed}</strong>
        <span className="ops-sub">
          analysed · {formatNumber(overview.analysesRun)} checks run
        </span>
        {overview.weakestDomains?.length > 0 ? (
          <ul className="ops-breakdown mono">
            {overview.weakestDomains.map((entry) => <li key={entry}><span>{entry}</span></li>)}
          </ul>
        ) : (
          <p className="ops-note">No domain has been analysed yet.</p>
        )}
      </article>
    </div>
  );
};

export default OperationsOverview;
