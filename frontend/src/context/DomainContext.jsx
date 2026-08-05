import React, { createContext, useContext, useState, useEffect } from 'react';

const DomainContext = createContext();

export const DomainProvider = ({ children }) => {
  // Try to load from localStorage so the filter persists across reloads
  const [selectedDomain, setSelectedDomain] = useState(() => {
    return localStorage.getItem('dmarc_selected_domain') || null;
  });

  useEffect(() => {
    if (selectedDomain) {
      localStorage.setItem('dmarc_selected_domain', selectedDomain);
    } else {
      localStorage.removeItem('dmarc_selected_domain');
    }
  }, [selectedDomain]);

  return (
    <DomainContext.Provider value={{ selectedDomain, setSelectedDomain }}>
      {children}
    </DomainContext.Provider>
  );
};

export const useDomain = () => useContext(DomainContext);
