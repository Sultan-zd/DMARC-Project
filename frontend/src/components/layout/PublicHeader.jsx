import React from 'react';
import { Link } from 'react-router-dom';
import { LogIn } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import './PublicHeader.css';

/**
 * Header for the unauthenticated pages. Shows the Teknologiia wordmark and one
 * action: sign in, or return to the dashboard when a session already exists.
 */
const PublicHeader = () => {
  const { token } = useAuth();

  return (
    <header className="public-header">
      <div className="public-header-inner">
        <Link to="/" className="public-brand" aria-label="Teknologiia home">
          <img
            src="/brand/logo-teknologiia.png"
            alt="Teknologiia"
            width="576"
            height="64"
          />
        </Link>

        {token ? (
          <Link to="/dashboard" className="public-header-action">
            Go to dashboard
          </Link>
        ) : (
          <Link to="/login" className="public-header-action">
            <LogIn size={16} />
            Sign in
          </Link>
        )}
      </div>
    </header>
  );
};

export default PublicHeader;
