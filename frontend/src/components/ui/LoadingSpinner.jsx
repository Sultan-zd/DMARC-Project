import React from 'react';
import { Shield } from 'lucide-react';
import './ui.css';

const LoadingSpinner = () => {
  return (
    <div className="loading-spinner">
      <Shield className="spinner-icon" size={48} />
      <p>Loading...</p>
    </div>
  );
};

export default LoadingSpinner;
