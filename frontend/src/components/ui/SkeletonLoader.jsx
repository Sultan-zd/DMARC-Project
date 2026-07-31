import React from 'react';
import './ui.css';

const SkeletonLoader = ({ lines = 3, type = 'text' }) => {
  if (type === 'card') {
    return (
      <div className="skeleton-card glass-card">
        <div className="skeleton skeleton-title"></div>
        <div className="skeleton skeleton-text"></div>
        <div className="skeleton skeleton-text short"></div>
      </div>
    );
  }

  if (type === 'table') {
    return (
      <div className="skeleton-table">
        <div className="skeleton skeleton-header"></div>
        {Array.from({ length: lines }).map((_, i) => (
          <div key={i} className="skeleton skeleton-row"></div>
        ))}
      </div>
    );
  }

  return (
    <div className="skeleton-container">
      {Array.from({ length: lines }).map((_, i) => (
        <div key={i} className={`skeleton skeleton-line line-${(i % 3) + 1}`}></div>
      ))}
    </div>
  );
};

export default SkeletonLoader;
