import React, { useEffect, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { CheckCircle2, XCircle, Loader2, Info } from 'lucide-react';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

/**
 * One redemption per token, however many times the effect runs.
 *
 * React's StrictMode deliberately mounts, unmounts and remounts every component in
 * development, so this page's effect fired twice. The first call spent the token
 * and activated the account; the second was told the link had already been used,
 * and that was the result shown. Every single person verifying their address saw
 * "Verification failed" over an account that had just been activated correctly.
 *
 * Cancelling the stale response was not enough — the request itself had already
 * left. Keying the promise by token means both runs await the same one.
 */
const inFlight = new Map();

const redeem = (token) => {
  if (!inFlight.has(token)) {
    inFlight.set(token, api.verifyEmail(token));
  }
  return inFlight.get(token);
};

const VerifyEmail = () => {
  usePageTitle('Verify your account');
  const [params] = useSearchParams();
  const token = params.get('token');

  const [status, setStatus] = useState('working');
  const [message, setMessage] = useState('');

  useEffect(() => {
    if (!token) {
      setStatus('error');
      setMessage('This link is missing its verification token.');
      return undefined;
    }

    let cancelled = false;
    redeem(token)
      .then((response) => {
        if (cancelled) return;
        setStatus('done');
        setMessage(response.detail || 'Your account is now active.');
      })
      .catch((error) => {
        if (cancelled) return;
        // 409 means the link was spent, which only happens once somebody has
        // followed it — so the account exists and works. Not a failure.
        setStatus(error.status === 409 ? 'spent' : 'error');
        setMessage(error.message);
      });

    return () => { cancelled = true; };
  }, [token]);

  const succeeded = status === 'done' || status === 'spent';

  return (
    <div className="login-container">
      <div className="login-background"></div>
      <div className="login-card">
        <div className="login-header">
          <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
               className="login-logo" width="576" height="64" />

          {status === 'working' && <Loader2 size={40} className="spin register-done-icon" />}
          {status === 'done' && <CheckCircle2 size={40} className="register-done-icon" />}
          {status === 'spent' && <Info size={40} className="register-done-icon" />}
          {status === 'error' && <XCircle size={40} className="register-error-icon" />}

          <h1>
            {status === 'working' && 'Verifying…'}
            {status === 'done' && 'Account activated'}
            {status === 'spent' && 'Already activated'}
            {status === 'error' && 'Verification failed'}
          </h1>
        </div>

        {status === 'spent' ? (
          <p className="register-note">
            This link has already been used, which means the account is active.
            Sign in as usual.
          </p>
        ) : (
          message && <p className="register-note">{message}</p>
        )}

        {status !== 'working' && (
          <Link to="/login" className="login-button" style={{ textDecoration: 'none' }}>
            {succeeded ? 'Sign in' : 'Back to sign in'}
          </Link>
        )}
      </div>
    </div>
  );
};

export default VerifyEmail;
