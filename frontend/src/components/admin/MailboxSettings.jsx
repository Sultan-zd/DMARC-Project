import React, { useCallback, useEffect, useState } from 'react';
import {
  Inbox, Play, Loader2, Trash2, CheckCircle2, AlertTriangle, Info,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import * as api from '../../services/api';
import './MailboxSettings.css';

/**
 * The two ways in, and they are not interchangeable.
 *
 * This used to be a list of IMAP host presets with Microsoft 365 among them,
 * which was wrong in a way that could not be configured around: Microsoft removed
 * Basic authentication from Exchange Online, so no password — app password
 * included — opens an IMAP session against a Microsoft 365 mailbox. The old form
 * accepted the settings and the first collection run then failed with an
 * authentication error nobody could act on.
 *
 * A provider is therefore chosen first, and the fields follow from it.
 */
const PROVIDERS = [
  {
    kind: 'MICROSOFT_GRAPH',
    label: 'Microsoft 365',
    blurb: 'Read as a registered application through Microsoft Graph.',
  },
  {
    kind: 'IMAP',
    label: 'Gmail, or any IMAP server',
    blurb: 'Read over IMAP with an app password.',
  },
];

const IMAP_PRESETS = [
  { label: 'Google Workspace / Gmail', host: 'imap.gmail.com', port: 993 },
  { label: 'Zoho', host: 'imap.zoho.com', port: 993 },
];

const BLANK = {
  kind: 'IMAP',
  host: '', port: 993, username: '', password: '',
  tenantId: '', clientId: '',
  useSsl: true, pollingEnabled: true,
};

const formatWhen = (value) =>
  value
    ? new Date(value).toLocaleString('en-GB', {
      day: '2-digit', month: 'short', hour: '2-digit', minute: '2-digit',
    })
    : null;

/**
 * The mailbox this organization collects its reports from.
 *
 * <p>Per organization, and configured here rather than in a file on the server —
 * which is what makes mailbox collection something a customer can use at all. The
 * secret is written, never read back: it is stored encrypted, and this form offers
 * no way to retrieve it.
 */
const MailboxSettings = ({ onChange }) => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [settings, setSettings] = useState(null);
  const [form, setForm] = useState(BLANK);
  const [editing, setEditing] = useState(false);
  const [busy, setBusy] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState(null);

  const load = useCallback(async () => {
    try {
      const data = await api.getMailboxSettings(token);
      setSettings(data);
      if (data.configured) {
        setForm({
          kind: data.kind ?? 'IMAP',
          host: data.host ?? '', port: data.port ?? 993, username: data.username ?? '',
          password: '',
          tenantId: data.tenantId ?? '', clientId: data.clientId ?? '',
          useSsl: data.useSsl, pollingEnabled: data.pollingEnabled,
        });
      }
    } catch {
      // The upload path beside it works without this.
    }
  }, [token]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const save = async (event) => {
    event.preventDefault();
    setBusy(true);
    try {
      await api.saveMailboxSettings(token, form);
      await load();
      setEditing(false);
      onChange?.();
      showToast('Mailbox saved', 'success');
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  const collectNow = async () => {
    setRunning(true);
    setResult(null);
    try {
      const run = await api.triggerIngestion(token);
      setResult(run);
      await load();
      onChange?.();
      if (run.reportsStored > 0) {
        showToast(`${run.reportsStored} report(s) imported`, 'success');
      } else if (run.errors?.length) {
        showToast(run.errors[0], 'warning');
      } else {
        showToast('Mailbox checked — nothing new', 'info');
      }
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setRunning(false);
    }
  };

  const remove = async () => {
    setBusy(true);
    try {
      await api.deleteMailboxSettings(token);
      setSettings(null);
      setForm(BLANK);
      setEditing(false);
      await load();
      onChange?.();
      showToast('Mailbox removed', 'info');
    } catch (err) {
      showToast(err.message, 'error');
    } finally {
      setBusy(false);
    }
  };

  if (!settings) return null;

  const showForm = editing || !settings.configured;
  const isGraph = form.kind === 'MICROSOFT_GRAPH';
  // Changing provider makes the stored secret meaningless — an IMAP password is
  // not a client secret — so the server asks for a new one, and so does the form.
  const changedProvider = settings.configured && form.kind !== settings.kind;
  const secretRequired = !settings.configured || changedProvider;

  return (
    <div className="admin-card mailbox-card">
      <div className="card-header-icon">
        <Inbox size={20} className="icon-blue" />
        <h2>Mailbox collection</h2>
      </div>

      <p className="description">
        Point this at the mailbox your DMARC record names in <code>rua=</code>.
        Reports are collected from it automatically every {settings.pollIntervalMinutes} minutes.
      </p>

      {!settings.serverCanStoreSecrets && (
        <p className="mailbox-blocked">
          <AlertTriangle size={15} />
          This server has no encryption key configured, so it cannot store a mailbox
          secret. Ask whoever runs it to set <code>SECRETS_KEY</code>. Uploading
          report files still works.
        </p>
      )}

      {/* ── Configured, at rest ── */}
      {settings.configured && !showForm && (
        <>
          <dl className="mailbox-facts">
            <div><dt>Mailbox</dt><dd className="mono">{settings.username}</dd></div>
            <div>
              <dt>Read through</dt>
              <dd>{settings.kind === 'MICROSOFT_GRAPH'
                ? 'Microsoft Graph'
                : <span className="mono">IMAP · {settings.host}:{settings.port}</span>}</dd>
            </div>
            {settings.kind === 'MICROSOFT_GRAPH' && (
              <div><dt>Directory</dt><dd className="mono">{settings.tenantId}</dd></div>
            )}
            <div>
              <dt>Automatic collection</dt>
              <dd>{settings.pollingEnabled
                ? `every ${settings.pollIntervalMinutes} minutes`
                : 'off — collect by hand'}</dd>
            </div>
            {settings.lastRunAt && (
              <div>
                <dt>Last run</dt>
                <dd className={settings.lastRunOk ? 'run-ok' : 'run-failed'}>
                  {settings.lastRunOk ? <CheckCircle2 size={14} /> : <AlertTriangle size={14} />}
                  {formatWhen(settings.lastRunAt)} — {settings.lastRunSummary}
                </dd>
              </div>
            )}
          </dl>

          <div className="mailbox-actions">
            <button className="btn btn-primary" onClick={collectNow} disabled={running}>
              {running
                ? <><Loader2 size={15} className="spin" /> Collecting…</>
                : <><Play size={15} fill="currentColor" /> Collect now</>}
            </button>
            <button className="btn btn-secondary" onClick={() => setEditing(true)}>Change</button>
            <button className="btn-text-danger" onClick={remove} disabled={busy}>
              <Trash2 size={14} /> Remove
            </button>
          </div>

          {result && (
            <div className={`ingestion-result ${result.reportsStored > 0 ? 'success' : 'error'}`}>
              <ul>
                <li>Reports imported: <strong>{result.reportsStored ?? 0}</strong></li>
                <li>Records stored: <strong>{result.recordsStored ?? 0}</strong></li>
                <li>Already known: <strong>{result.duplicatesSkipped ?? 0}</strong></li>
              </ul>
              {result.errors?.length > 0 && (
                <ul className="ingestion-errors">
                  {result.errors.map((e, i) => <li key={i}>{e}</li>)}
                </ul>
              )}
            </div>
          )}
        </>
      )}

      {/* ── Configuring ── */}
      {showForm && (
        <form onSubmit={save} className="mailbox-form">
          <div className="provider-choice">
            {PROVIDERS.map((p) => (
              <button
                type="button"
                key={p.kind}
                className={`provider-option ${form.kind === p.kind ? 'is-chosen' : ''}`}
                onClick={() => setForm({ ...form, kind: p.kind })}
              >
                <strong>{p.label}</strong>
                <span>{p.blurb}</span>
              </button>
            ))}
          </div>

          {isGraph ? (
            <>
              <p className="mailbox-note">
                <Info size={14} />
                <span>
                  Microsoft removed password sign-in for IMAP on Exchange Online, so a
                  Microsoft 365 mailbox is read as a <strong>registered application</strong> instead. Register one in Entra&nbsp;ID, grant it the
                  <code>Mail.Read</code> application permission with admin consent, and
                  restrict it to this one mailbox with an application access policy.
                </span>
              </p>

              <div className="mailbox-fields is-single">
                <label>
                  <span>Mailbox address</span>
                  <input
                    type="text" value={form.username} required
                    placeholder="dmarcreports@yourcompany.com"
                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                  />
                </label>
                <label>
                  <span>Directory (tenant) ID</span>
                  <input
                    type="text" value={form.tenantId} required
                    placeholder="00000000-0000-0000-0000-000000000000"
                    onChange={(e) => setForm({ ...form, tenantId: e.target.value })}
                  />
                </label>
                <label>
                  <span>Application (client) ID</span>
                  <input
                    type="text" value={form.clientId} required
                    placeholder="00000000-0000-0000-0000-000000000000"
                    onChange={(e) => setForm({ ...form, clientId: e.target.value })}
                  />
                </label>
                <label>
                  <span>{secretRequired ? 'Client secret' : 'New client secret (leave empty to keep)'}</span>
                  <input
                    type="password" value={form.password}
                    required={secretRequired}
                    autoComplete="new-password"
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                  />
                </label>
              </div>

              <p className="mailbox-hint">
                Entra ID client secrets expire — often after six or twelve months. When
                one does, collection stops and the last run says so; create a new secret
                and save it here.
              </p>
            </>
          ) : (
            <>
              <div className="preset-row">
                <span>Start from</span>
                {IMAP_PRESETS.map((p) => (
                  <button
                    type="button"
                    key={p.label}
                    className="preset-chip"
                    onClick={() => setForm({ ...form, host: p.host, port: p.port })}
                  >
                    {p.label}
                  </button>
                ))}
              </div>

              <div className="mailbox-fields">
                <label>
                  <span>IMAP server</span>
                  <input
                    type="text" value={form.host} required
                    placeholder="imap.gmail.com"
                    onChange={(e) => setForm({ ...form, host: e.target.value })}
                  />
                </label>
                <label className="narrow">
                  <span>Port</span>
                  <input
                    type="number" value={form.port} required min="1" max="65535"
                    onChange={(e) => setForm({ ...form, port: Number(e.target.value) })}
                  />
                </label>
                <label>
                  <span>Mailbox address</span>
                  <input
                    type="text" value={form.username} required
                    placeholder="dmarcreports@yourdomain.com"
                    onChange={(e) => setForm({ ...form, username: e.target.value })}
                  />
                </label>
                <label>
                  <span>{secretRequired ? 'App password' : 'New app password (leave empty to keep)'}</span>
                  <input
                    type="password" value={form.password}
                    required={secretRequired}
                    autoComplete="new-password"
                    onChange={(e) => setForm({ ...form, password: e.target.value })}
                  />
                </label>
              </div>

              <p className="mailbox-note">
                <Info size={14} />
                <span>
                  Gmail refuses an ordinary account password over IMAP — create an
                  <strong> app password</strong> instead. It is stored encrypted and is
                  never shown again.
                </span>
              </p>
            </>
          )}

          <label className="mailbox-toggle">
            <input
              type="checkbox" checked={form.pollingEnabled}
              onChange={(e) => setForm({ ...form, pollingEnabled: e.target.checked })}
            />
            Collect automatically every {settings.pollIntervalMinutes} minutes
          </label>

          {changedProvider && (
            <p className="mailbox-hint">
              Switching provider replaces the stored credential, so the new one has to be
              entered here.
            </p>
          )}

          <div className="mailbox-actions">
            <button type="submit" className="btn btn-primary" disabled={busy || !settings.serverCanStoreSecrets}>
              {busy ? <Loader2 size={15} className="spin" /> : 'Save mailbox'}
            </button>
            {settings.configured && (
              <button type="button" className="btn btn-secondary" onClick={() => setEditing(false)}>
                Cancel
              </button>
            )}
          </div>
        </form>
      )}
    </div>
  );
};

export default MailboxSettings;
