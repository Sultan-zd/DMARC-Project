import React from 'react';
import useSystemInfo from '../../hooks/useSystemInfo';
import './Footer.css';

const Footer = () => {
  const currentYear = new Date().getFullYear();
  // Was hard-coded to v1.0.0 while the build reported something else entirely.
  const info = useSystemInfo();

  return (
    <footer className="app-footer">
      <div className="footer-inner">
        <div className="footer-brand">
          <span className="footer-bars" aria-hidden="true" />
          <span>DMARC Dashboard</span>
        </div>
        <div className="footer-meta">
          {info && <span className="footer-version">v{info.version}</span>}
          {info && <span className="footer-separator">·</span>}
          <span>© {currentYear} Teknologiia</span>
        </div>
      </div>
    </footer>
  );
};

export default Footer;
