import React, { useState, useEffect } from 'react';
import './ui.css';

const AnimatedCounter = ({ value, duration = 1000, prefix = '', suffix = '', decimals = 0 }) => {
  const [count, setCount] = useState(0);

  useEffect(() => {
    let startTimestamp = null;
    const target = parseFloat(value);
    
    if (isNaN(target)) {
      setCount(value);
      return;
    }

    const step = (timestamp) => {
      if (!startTimestamp) startTimestamp = timestamp;
      const progress = Math.min((timestamp - startTimestamp) / duration, 1);
      
      // easeOutQuart
      const easeProgress = 1 - Math.pow(1 - progress, 4);
      setCount(target * easeProgress);
      
      if (progress < 1) {
        window.requestAnimationFrame(step);
      } else {
        setCount(target);
      }
    };
    
    window.requestAnimationFrame(step);
  }, [value, duration]);

  const formatNumber = (num) => {
    if (typeof num === 'string') return num;
    const formatter = new Intl.NumberFormat('fr-FR', {
      minimumFractionDigits: decimals,
      maximumFractionDigits: decimals
    });
    return formatter.format(num);
  };

  return (
    <span className="animated-counter fade-in">
      {prefix}{formatNumber(count)}{suffix}
    </span>
  );
};

export default AnimatedCounter;
