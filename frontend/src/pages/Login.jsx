import React, { useState, useEffect } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import usePageTitle from '../hooks/usePageTitle';
import { User, Lock, Eye, EyeOff, Loader2, ShieldCheck, ArrowLeft } from 'lucide-react';
import './Login.css';

const Login = () => {
  usePageTitle('Sign In');
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);

  // Set once the password is accepted on an account carrying a second factor.
  // Holding it in state rather than storage keeps a half-finished sign-in from
  // surviving a refresh.
  const [mfaToken, setMfaToken] = useState(null);
  const [code, setCode] = useState('');

  const { login, completeTwoFactor, token } = useAuth();
  const { showToast } = useToast();
  const navigate = useNavigate();

  // "/" is now the public landing page, so an authenticated visitor belongs on
  // the dashboard instead.
  useEffect(() => {
    if (token) {
      navigate('/dashboard', { replace: true });
    }
  }, [token, navigate]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setIsSubmitting(true);
    try {
      const result = await login(username, password);
      if (result.done) {
        navigate(result.user?.platform_operator ? '/platform' : '/dashboard', { replace: true });
      } else {
        setMfaToken(result.mfaToken);
      }
    } catch (err) {
      // The server distinguishes an unverified account from a wrong password, and
      // saying so is what stops somebody waiting forever for a dashboard that will
      // never load until they follow the emailed link.
      const message = err.message && err.message !== 'Login failed'
        ? err.message
        : 'Invalid username or password.';
      setError(message);
      showToast(message, 'error');
    } finally {
      setIsSubmitting(false);
    }
  };

  const handleCode = async (e) => {
    e.preventDefault();
    setError('');
    setIsSubmitting(true);
    try {
      const result = await completeTwoFactor(mfaToken, code);
      navigate(result.user?.platform_operator ? '/platform' : '/dashboard', { replace: true });
    } catch (err) {
      setError(err.message);
      setCode('');
    } finally {
      setIsSubmitting(false);
    }
  };

  const startOver = () => {
    setMfaToken(null);
    setCode('');
    setError('');
    setPassword('');
  };

  return (
    <div className="login-container">
      <div className="login-background"></div>
      <div className="login-card">
        <div className="login-header">
          {/* The card is deliberately dark in both themes, which is what lets the
              white Teknologiia wordmark be used here at full size. */}
          <img
            src="/brand/logo-teknologiia.png"
            alt="Teknologiia"
            className="login-logo"
            width="576"
            height="64"
          />
          <h1>{mfaToken ? 'Two-step verification' : 'DMARC Dashboard'}</h1>
          <p className="login-eyebrow">
            {mfaToken ? `Signed in as ${username}` : 'Email Security Platform'}
          </p>
        </div>

        {mfaToken ? (
          <form onSubmit={handleCode} className="login-form">
            {error && <div className="error-message shake">{error}</div>}

            <p className="register-note">
              Enter the six-digit code from your authenticator app. If you no longer
              have it, one of your recovery codes works instead.
            </p>

            <div className="input-group">
              <ShieldCheck className="input-icon" size={20} />
              <input
                type="text"
                className="mfa-code"
                placeholder="000000"
                value={code}
                onChange={(e) => setCode(e.target.value)}
                autoComplete="one-time-code"
                inputMode="text"
                aria-label="Authentication code"
                autoFocus
                required
              />
            </div>

            <button type="submit" className="login-button" disabled={isSubmitting}>
              {isSubmitting ? <Loader2 className="spinner" size={20} /> : 'Verify'}
            </button>

            <button type="button" className="mfa-back" onClick={startOver}>
              <ArrowLeft size={14} /> Sign in as someone else
            </button>
          </form>
        ) : (
        <form onSubmit={handleSubmit} className="login-form">
          {error && <div className="error-message shake">{error}</div>}
          
          <div className="input-group">
            <User className="input-icon" size={20} />
            <input
              type="text"
              placeholder="Username"
              value={username}
              onChange={(e) => setUsername(e.target.value)}
              required
            />
          </div>
          
          <div className="input-group">
            <Lock className="input-icon" size={20} />
            <input
              type={showPassword ? "text" : "password"}
              placeholder="Password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              required
            />
            <button 
              type="button"
              className="password-toggle"
              onClick={() => setShowPassword(!showPassword)}
            >
              {showPassword ? <EyeOff size={20} /> : <Eye size={20} />}
            </button>
          </div>
          
          <button 
            type="submit" 
            className="login-button"
            disabled={isSubmitting}
          >
            {isSubmitting ? <Loader2 className="spinner" size={20} /> : "Sign In"}
          </button>
        </form>
        )}

        {!mfaToken && (
          <>
            {/* Under the form rather than beside the password field: someone who
                needs it is not mid-typing, they have already failed once. */}
            <p className="login-forgot">
              <Link to="/forgot-password">Forgot your password?</Link>
            </p>
            <p className="register-switch">
              No account yet? <Link to="/register">Create one</Link>
            </p>
          </>
        )}
      </div>
    </div>
  );
};

export default Login;
