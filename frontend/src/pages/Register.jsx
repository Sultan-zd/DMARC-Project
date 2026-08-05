import React, { useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { Building2, User, Mail, Lock, Eye, EyeOff, Loader2, CheckCircle2 } from 'lucide-react';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import './Login.css';

const MIN_PASSWORD = 10;

/**
 * Sign-up: creates an organization and its first administrator.
 *
 * Reuses the Login card styling — the same permanently dark surface, which is what
 * lets the white Teknologiia wordmark be shown at full size.
 */
const Register = () => {
  usePageTitle('Create your account');
  const navigate = useNavigate();
  const { showToast } = useToast();

  const [form, setForm] = useState({ organization: '', username: '', email: '', password: '' });
  const [showPassword, setShowPassword] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [done, setDone] = useState(false);

  const update = (field) => (event) => setForm({ ...form, [field]: event.target.value });

  const handleSubmit = async (event) => {
    event.preventDefault();
    setError('');

    if (form.password.length < MIN_PASSWORD) {
      setError(`Password must be at least ${MIN_PASSWORD} characters.`);
      return;
    }

    setSubmitting(true);
    try {
      await api.register(form);
      setDone(true);
      showToast('Account created — check your email to activate it', 'success');
    } catch (err) {
      setError(err.message);
      showToast(err.message, 'error');
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <div className="login-container">
        <div className="login-background"></div>
        <div className="login-card">
          <div className="login-header">
            <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
                 className="login-logo" width="576" height="64" />
            <CheckCircle2 size={40} className="register-done-icon" />
            <h1>Check your email</h1>
            <p className="login-eyebrow">Almost there</p>
          </div>

          <p className="register-note">
            We sent a verification link to <strong>{form.email}</strong>. Follow it to
            activate <strong>{form.organization}</strong> and sign in.
          </p>

          <button className="login-button" onClick={() => navigate('/login')}>
            Back to sign in
          </button>
        </div>
      </div>
    );
  }

  return (
    <div className="login-container">
      <div className="login-background"></div>
      <div className="login-card">
        <div className="login-header">
          <img src="/brand/logo-teknologiia.png" alt="Teknologiia"
               className="login-logo" width="576" height="64" />
          <h1>Create your account</h1>
          <p className="login-eyebrow">Start monitoring your domains</p>
        </div>

        <form onSubmit={handleSubmit} className="login-form">
          {error && <div className="error-message shake">{error}</div>}

          <div className="input-group">
            <input type="text" value={form.organization} onChange={update('organization')}
                   placeholder="Organization name" aria-label="Organization name"
                   autoComplete="organization" required />
            <Building2 size={18} className="input-icon" />
          </div>

          <div className="input-group">
            <input type="text" value={form.username} onChange={update('username')}
                   placeholder="Username" aria-label="Username"
                   autoComplete="username" required />
            <User size={18} className="input-icon" />
          </div>

          <div className="input-group">
            <input type="email" value={form.email} onChange={update('email')}
                   placeholder="Work email" aria-label="Work email"
                   autoComplete="email" required />
            <Mail size={18} className="input-icon" />
          </div>

          <div className="input-group">
            <input type={showPassword ? 'text' : 'password'} value={form.password}
                   onChange={update('password')} placeholder="Password"
                   aria-label="Password" autoComplete="new-password"
                   minLength={MIN_PASSWORD} required />
            <Lock size={18} className="input-icon" />
            <button type="button" className="password-toggle"
                    onClick={() => setShowPassword(!showPassword)}
                    aria-label={showPassword ? 'Hide password' : 'Show password'}>
              {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>

          <p className="register-hint">At least {MIN_PASSWORD} characters.</p>

          <button type="submit" className="login-button" disabled={submitting}>
            {submitting ? <Loader2 size={18} className="spin" /> : null}
            {submitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>

        <p className="register-switch">
          Already have an account? <Link to="/login">Sign in</Link>
        </p>
      </div>
    </div>
  );
};

export default Register;
