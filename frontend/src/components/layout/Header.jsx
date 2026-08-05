import React from 'react';
import { Info } from 'lucide-react';
import './Header.css';

/**
 * Page heading.
 *
 * The optional `note` states which data source the page draws on. Analysis reads
 * live DNS; the Dashboard and Reports aggregate the DMARC reports mailbox providers
 * send you. Those answer different questions and are not expected to agree — saying
 * so on the page stops that being read as a defect.
 */
const Header = ({ title, subtitle, note }) => {
  return (
    <div className="page-header">
      <h1>{title}</h1>
      {subtitle && <p className="subtitle">{subtitle}</p>}
      {note && (
        <p className="page-header-note">
          <Info size={14} aria-hidden="true" />
          <span>{note}</span>
        </p>
      )}
    </div>
  );
};

export default Header;
