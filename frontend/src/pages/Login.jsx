import React, { useState, useEffect } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../context/AuthContext';
import { Shield, User, Lock, Eye, EyeOff, Loader2 } from 'lucide-react';
import './Login.css';

const Login = () => {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  
  const { login, token } = useAuth();
  const navigate = useNavigate();

  useEffect(() => {
    if (token) {
      navigate('/');
    }
  }, [token, navigate]);

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError('');
    setIsSubmitting(true);
    try {
      await login(username, password);
      navigate('/');
    } catch (err) {
      setError('Invalid username or password.');
    } finally {
      setIsSubmitting(false);
    }
  };

  return (
    <div className="login-container">
      <div className="login-background" style={{
        background: 'radial-gradient(circle at 50% 50%, var(--bg-card) 0%, var(--bg-primary) 100%)',
        backgroundSize: '200% 200%',
        animation: 'shimmer 15s ease infinite'
      }}></div>
      <div className="login-card glass-panel" style={{ animation: 'pulse-glow 4s infinite' }}>
        <div className="login-header">
          <div className="shield-icon-wrapper">
            <Shield className="shield-icon" size={48} />
          </div>
          <h1>DMARC Dashboard</h1>
          <p className="subtitle" style={{ fontSize: '1.2rem', fontWeight: '500', color: 'var(--accent-primary)', marginBottom: '0.25rem' }}>Email Security</p>
          <p className="subtitle" style={{ fontSize: '0.9rem' }}>Teknologiia</p>
        </div>
        
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
      </div>
    </div>
  );
};

export default Login;
