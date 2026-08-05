import React, { useState } from 'react';
import { Link } from 'react-router-dom';
import { Menu, X } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import './PublicNav.css';

const LINKS = [
  { href: '#problem', label: 'Why DMARC' },
  { href: '#compliance', label: 'Requirements' },
  { href: '#how', label: 'How it works' },
  { href: '#faq', label: 'FAQ' },
];

/**
 * Navigation for the public pages.
 *
 * Two actions, deliberately unequal: signing in is for people who already have an
 * account, while the scanner is what a first-time visitor is here for — so that is
 * the filled button.
 */
const PublicNav = () => {
  const { token } = useAuth();
  const [open, setOpen] = useState(false);

  return (
    <header className="public-nav">
      <div className="public-nav-inner">
        <Link to="/" className="public-nav-brand" aria-label="Teknologiia home">
          <img src="/brand/logo-teknologiia.png" alt="Teknologiia" width="576" height="64" />
        </Link>

        <nav className={`public-nav-links ${open ? 'is-open' : ''}`} aria-label="Main">
          {LINKS.map((link) => (
            <a key={link.href} href={link.href} onClick={() => setOpen(false)}>
              {link.label}
            </a>
          ))}
        </nav>

        <div className="public-nav-actions">
          {token ? (
            <Link to="/dashboard" className="nav-btn nav-btn-solid">Go to dashboard</Link>
          ) : (
            <>
              <Link to="/login" className="nav-btn nav-btn-ghost">Sign in</Link>
              <a href="#scan" className="nav-btn nav-btn-solid">Check a domain</a>
            </>
          )}
        </div>

        <button
          className="public-nav-toggle"
          onClick={() => setOpen(!open)}
          aria-label={open ? 'Close menu' : 'Open menu'}
          aria-expanded={open}
        >
          {open ? <X size={22} /> : <Menu size={22} />}
        </button>
      </div>
    </header>
  );
};

export default PublicNav;
