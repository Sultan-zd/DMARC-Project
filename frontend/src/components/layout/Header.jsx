import React from 'react';
import './Header.css';

const Header = ({ title, subtitle }) => {
  return (
    <div className="page-header">
      <h1>{title}</h1>
      {subtitle && <p className="subtitle">{subtitle}</p>}
    </div>
  );
};

export default Header;
