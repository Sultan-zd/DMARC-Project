import React from 'react';
import { useNavigate } from 'react-router-dom';
import { ShieldOff, ArrowLeft } from 'lucide-react';
import usePageTitle from '../hooks/usePageTitle';
import './NotFound.css';

const NotFound = () => {
  usePageTitle('Page Not Found');
  const navigate = useNavigate();

  return (
    <div className="not-found-page">
      <div className="not-found-card">
        <div className="not-found-icon-wrapper">
          <ShieldOff size={64} />
        </div>
        <h1 className="not-found-code">404</h1>
        <h2 className="not-found-title">Page Not Found</h2>
        <p className="not-found-description">
          The page you're looking for doesn't exist or has been moved.
        </p>
        <button className="not-found-btn" onClick={() => navigate('/')}>
          <ArrowLeft size={18} />
          Back to Dashboard
        </button>
      </div>
    </div>
  );
};

export default NotFound;
