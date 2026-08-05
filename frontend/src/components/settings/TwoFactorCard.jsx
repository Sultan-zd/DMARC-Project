import React, { useCallback, useEffect, useState } from 'react';
import {
  ShieldCheck, ShieldOff, Loader2, Copy, Check, KeyRound, RefreshCw, AlertTriangle,
} from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { useToast } from '../../context/ToastContext';
import * as api from '../../services/api';
import './TwoFactorCard.css';

/**
 * Turning a second factor on, and off.
 *
 * The order on screen follows the order of the decision: what it is, then the QR
 * code, then the confirmation that proves the app can read it. Nothing takes effect
 * until that last step — an enrolment abandoned halfway leaves the account exactly
 * as it was, rather than locked out of its own sign-in.
 */
const TwoFactorCard = () => {
  const { token } = useAuth();
  const { showToast } = useToast();

  const [status, setStatus] = useState(null);
  const [setup, setSetup] = useState(null);
  const [code, setCode] = useState('');
  const [password, setPassword] = useState('');
  const [recoveryCodes, setRecoveryCodes] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);
  const [confirmingOff, setConfirmingOff] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await api.getTwoFactorStatus(token));
    } catch {
      // The rest of Settings works without it.
    }
  }, [token]);

  useEffect(() => { if (token) load(); }, [token, load]);

  const run = async (fn, onDone) => {
    setBusy(true);
    setError('');
    try {
      const result = await fn();
      await load();
      onDone?.(result);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
    }
  };

  const copyCodes = async () => {
    try {
      await navigator.clipboard.writeText(recoveryCodes.join('\n'));
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      // They are on screen either way.
    }
  };

  if (!status) return null;

  return (
    <section className="settings-card glass-card two-factor-card">
      <h2 className="settings-card-title">
        {status.enabled ? <ShieldCheck size={20} /> : <ShieldOff size={20} />}
        Two-step verification
      </h2>

      {error && <div className="settings-error">{error}</div>}

      {/* ── Already on ── */}
      {status.enabled && !recoveryCodes && (
        <>
          <p className="two-factor-state on">
            <ShieldCheck size={16} /> On since{' '}
            {new Date(status.enabledAt).toLocaleDateString('en-GB', {
              day: 'numeric', month: 'long', year: 'numeric',
            })}
          </p>
          <p className="settings-description">
            Signing in now asks for a code from your authenticator app after your
            password. Somebody who learns your password still cannot get in.
          </p>

          <div className={`recovery-count ${status.recoveryCodesLeft <= 2 ? 'low' : ''}`}>
            {status.recoveryCodesLeft <= 2 && <AlertTriangle size={14} />}
            <strong>{status.recoveryCodesLeft}</strong> recovery{' '}
            {status.recoveryCodesLeft === 1 ? 'code' : 'codes'} left
            {status.recoveryCodesLeft <= 2 && ' — issue a new set before you run out'}
          </div>

          <div className="two-factor-actions">
            <button
              className="btn btn-secondary"
              disabled={busy}
              onClick={() => run(
                () => api.regenerateRecoveryCodes(token),
                (r) => {
                  setRecoveryCodes(r.recovery_codes);
                  showToast('New recovery codes issued', 'success');
                },
              )}
            >
              <RefreshCw size={15} /> New recovery codes
            </button>

            {confirmingOff ? (
              <form
                className="disable-form"
                onSubmit={(e) => {
                  e.preventDefault();
                  run(
                    () => api.disableTwoFactor(token, password),
                    () => {
                      setPassword('');
                      setConfirmingOff(false);
                      showToast('Two-step verification turned off', 'info');
                    },
                  );
                }}
              >
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="Your password"
                  aria-label="Password, to turn off two-step verification"
                  autoComplete="current-password"
                  required
                />
                <button type="submit" className="btn btn-danger" disabled={busy}>
                  Turn off
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => { setConfirmingOff(false); setPassword(''); setError(''); }}
                >
                  Cancel
                </button>
              </form>
            ) : (
              <button className="btn btn-text-danger" onClick={() => setConfirmingOff(true)}>
                Turn off
              </button>
            )}
          </div>
        </>
      )}

      {/* ── Off, and not yet enrolling ── */}
      {!status.enabled && !setup && !recoveryCodes && (
        <>
          <p className="two-factor-state off">
            <ShieldOff size={16} /> Off
          </p>
          <p className="settings-description">
            Adds a six-digit code from your phone to every sign-in. A stolen password
            on its own stops being enough — which matters here, because this account
            can read who is sending mail as your domains.
          </p>
          <button
            className="btn btn-primary"
            disabled={busy}
            onClick={() => run(() => api.beginTwoFactorSetup(token), setSetup)}
          >
            {busy ? <><Loader2 size={15} className="spin" /> Preparing…</> : 'Turn on'}
          </button>
        </>
      )}

      {/* ── Enrolling ── */}
      {setup && !recoveryCodes && (
        <div className="enrolment">
          <ol className="enrolment-steps">
            <li>
              <strong>Scan this with your authenticator app.</strong>
              <span>Google Authenticator, Microsoft Authenticator, 1Password — any of them.</span>
              {setup.qrDataUri && (
                <img src={setup.qrDataUri} alt="Enrolment QR code" width="200" height="200" />
              )}
              <details className="manual-entry">
                <summary>No camera? Enter this key by hand</summary>
                <code>{setup.secret}</code>
              </details>
            </li>
            <li>
              <strong>Enter the code it shows.</strong>
              <span>This proves the app can read the key before anything takes effect.</span>
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  run(
                    () => api.enableTwoFactor(token, code),
                    (r) => {
                      setRecoveryCodes(r.recovery_codes);
                      setSetup(null);
                      setCode('');
                      showToast('Two-step verification is on', 'success');
                    },
                  );
                }}
              >
                <input
                  type="text"
                  className="enrolment-code"
                  value={code}
                  onChange={(e) => setCode(e.target.value)}
                  placeholder="000000"
                  aria-label="Code from your authenticator app"
                  autoComplete="one-time-code"
                  inputMode="numeric"
                  maxLength={6}
                  required
                />
                <button type="submit" className="btn btn-primary" disabled={busy}>
                  {busy ? <Loader2 size={15} className="spin" /> : 'Confirm'}
                </button>
                <button
                  type="button"
                  className="btn btn-secondary"
                  onClick={() => { setSetup(null); setCode(''); setError(''); }}
                >
                  Cancel
                </button>
              </form>
            </li>
          </ol>
        </div>
      )}

      {/* ── The codes, shown once ── */}
      {recoveryCodes && (
        <div className="recovery-codes">
          <h3><KeyRound size={16} /> Save your recovery codes</h3>
          <p>
            Each one signs you in once if you lose your phone. They are stored
            hashed, so <strong>this is the only time they are shown</strong>. Keep
            them somewhere other than the device running your authenticator app.
          </p>
          <ul>
            {recoveryCodes.map((c) => <li key={c}>{c}</li>)}
          </ul>
          <div className="two-factor-actions">
            <button className="btn btn-secondary" onClick={copyCodes}>
              {copied ? <><Check size={15} /> Copied</> : <><Copy size={15} /> Copy all</>}
            </button>
            <button className="btn btn-primary" onClick={() => setRecoveryCodes(null)}>
              I have saved them
            </button>
          </div>
        </div>
      )}
    </section>
  );
};

export default TwoFactorCard;
