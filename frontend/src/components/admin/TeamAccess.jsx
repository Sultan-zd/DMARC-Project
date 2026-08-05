import React, { useCallback, useEffect, useState } from 'react';
import {
  Mail, Globe, Copy, Check, Trash2, ShieldCheck, Clock, RefreshCw, Info,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import { ROLE, roleLabel } from '../../utils/roles';
import * as api from '../../services/api';
import './TeamAccess.css';

/**
 * The two ways someone joins this organization.
 *
 * Without either, a colleague signing up on their own gets a second organization
 * carrying the same company name and an empty dashboard — the two never meet.
 */
const TeamAccess = ({ onChange }) => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [invitations, setInvitations] = useState([]);
  const [domains, setDomains] = useState([]);
  const [email, setEmail] = useState('');
  const [role, setRole] = useState(ROLE.VIEWER);
  const [domain, setDomain] = useState('');
  const [domainRole, setDomainRole] = useState(ROLE.VIEWER);
  const [link, setLink] = useState(null);
  const [copied, setCopied] = useState(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [inv, dom] = await Promise.all([
        api.getInvitations(token),
        api.getClaimedDomains(token),
      ]);
      setInvitations(inv ?? []);
      setDomains(dom ?? []);
    } catch {
      showToast('Could not load team settings', 'error');
    }
  }, [token, showToast]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const run = async (fn, message) => {
    setBusy(true);
    try {
      const result = await fn();
      if (message) showToast(message, 'success');
      load();
      onChange?.();
      return result;
    } catch (err) {
      showToast(err.message, 'error');
      return null;
    } finally {
      setBusy(false);
    }
  };

  const handleInvite = async (event) => {
    event.preventDefault();
    const created = await run(() => api.inviteMember(token, email, role), `Invitation created for ${email}`);
    if (created?.link) {
      setLink(created.link);
      setEmail('');
    }
  };

  const copy = async (value, key) => {
    try {
      await navigator.clipboard.writeText(value);
      setCopied(key);
      setTimeout(() => setCopied(null), 2000);
    } catch {
      // Clipboard may be unavailable; the value is on screen either way.
    }
  };

  return (
    <div className="team-access">
      {/* The link stands in for the email until SMTP is wired up. */}
      {link && (
        <div className="invite-link-banner">
          <Mail size={18} />
          <div>
            <strong>Invitation link</strong>
            <p>No mail is sent yet — pass this on yourself. It works once and expires in 7 days.</p>
            <code>{link}</code>
          </div>
          <button onClick={() => copy(link, 'link')}>
            {copied === 'link' ? <Check size={15} /> : <Copy size={15} />}
            {copied === 'link' ? 'Copied' : 'Copy'}
          </button>
        </div>
      )}

      <div className="team-grid">
        {/* ── Invitations ── */}
        <section className="admin-card">
          <div className="card-header-icon">
            <Mail size={20} className="icon-blue" />
            <h2>Invite a colleague</h2>
          </div>
          <p className="description">
            The reliable way in: the invitee joins <em>this</em> organization with the role
            you pick, rather than creating one of their own.
          </p>

          <form onSubmit={handleInvite} className="team-form">
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              placeholder="colleague@yourcompany.com"
              aria-label="Email to invite"
              required
            />
            <select value={role} onChange={(e) => setRole(e.target.value)} aria-label="Role">
              {Object.values(ROLE).map((r) => (
                <option key={r} value={r}>{roleLabel(r)}</option>
              ))}
            </select>
            <button type="submit" className="btn btn-primary" disabled={busy}>Invite</button>
          </form>

          {invitations.length > 0 && (
            <ul className="invite-list">
              {invitations.map((inv) => (
                <li key={inv.id}>
                  <div className="invite-main">
                    <span className="invite-email">{inv.email}</span>
                    <span className="invite-role">{roleLabel(inv.role)}</span>
                  </div>
                  <span className={`invite-state ${inv.accepted_at ? 'done' : inv.pending ? 'pending' : 'expired'}`}>
                    {inv.accepted_at ? 'Joined' : inv.pending ? <><Clock size={12} /> Pending</> : 'Expired'}
                  </span>
                  {!inv.accepted_at && (
                    <button
                      className="icon-btn danger"
                      title="Revoke"
                      onClick={() => run(() => api.revokeInvitation(token, inv.id), 'Invitation revoked')}
                    >
                      <Trash2 size={14} />
                    </button>
                  )}
                </li>
              ))}
            </ul>
          )}
        </section>

        {/* ── Claimed email domains ── */}
        <section className="admin-card">
          <div className="card-header-icon">
            <Globe size={20} className="icon-blue" />
            <h2>Claim an email domain</h2>
          </div>
          <p className="description">
            Once verified, anyone signing up with an address at this domain joins this
            organization automatically instead of creating a new one.
          </p>

          <form
            onSubmit={(e) => {
              e.preventDefault();
              run(() => api.claimDomain(token, domain, domainRole), `Claim created for ${domain}`)
                .then(() => setDomain(''));
            }}
            className="team-form"
          >
            <input
              type="text"
              value={domain}
              onChange={(e) => setDomain(e.target.value)}
              placeholder="yourcompany.com"
              aria-label="Domain to claim"
              required
            />
            <select value={domainRole} onChange={(e) => setDomainRole(e.target.value)} aria-label="Role granted">
              {Object.values(ROLE).map((r) => (
                <option key={r} value={r}>{roleLabel(r)}</option>
              ))}
            </select>
            <button type="submit" className="btn btn-primary" disabled={busy}>Claim</button>
          </form>

          <ul className="domain-list">
            {domains.map((d) => (
              <li key={d.id} className={d.verified ? 'verified' : ''}>
                <div className="domain-head">
                  <span className="domain-name">{d.domain}</span>
                  {d.verified ? (
                    <span className="domain-state done"><ShieldCheck size={13} /> Verified</span>
                  ) : (
                    <span className="domain-state pending">Awaiting DNS proof</span>
                  )}
                  <span className="domain-role">joins as {roleLabel(d.default_role)}</span>
                  <button
                    className="icon-btn danger"
                    title="Remove claim"
                    onClick={() => run(() => api.releaseClaimedDomain(token, d.id), 'Claim removed')}
                  >
                    <Trash2 size={14} />
                  </button>
                </div>

                {/* Until the record is published the claim grants nothing — which is
                    what stops anyone claiming a company they do not own. */}
                {!d.verified && (
                  <div className="domain-proof">
                    <p>Publish this TXT record, then verify:</p>
                    <div className="proof-row">
                      <code>{d.verification_host}</code>
                      <button onClick={() => copy(d.verification_host, `h${d.id}`)}>
                        {copied === `h${d.id}` ? <Check size={13} /> : <Copy size={13} />}
                      </button>
                    </div>
                    <div className="proof-row">
                      <code>{d.verification_token}</code>
                      <button onClick={() => copy(d.verification_token, `t${d.id}`)}>
                        {copied === `t${d.id}` ? <Check size={13} /> : <Copy size={13} />}
                      </button>
                    </div>
                    <button
                      className="btn btn-secondary w-full"
                      disabled={busy}
                      onClick={() => run(() => api.verifyClaimedDomain(token, d.id), `${d.domain} verified`)}
                    >
                      <RefreshCw size={14} /> Verify now
                    </button>
                  </div>
                )}
              </li>
            ))}
          </ul>

          <p className="team-note">
            <Info size={14} />
            Public providers such as Gmail or Outlook cannot be claimed — whoever claimed
            one would absorb every future sign-up using it.
          </p>
        </section>
      </div>
    </div>
  );
};

export default TeamAccess;
