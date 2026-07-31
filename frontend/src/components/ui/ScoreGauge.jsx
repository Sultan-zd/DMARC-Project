import React, { useEffect, useState } from 'react';
import './ui.css';

const ScoreGauge = ({ score = 0, grade = 'F', color = 'red', size = 160 }) => {
  const [animatedScore, setAnimatedScore] = useState(0);

  useEffect(() => {
    const timer = setTimeout(() => {
      setAnimatedScore(score);
    }, 100);
    return () => clearTimeout(timer);
  }, [score]);

  const strokeWidth = 12;
  const radius = (size - strokeWidth) / 2;
  const circumference = 2 * Math.PI * radius;
  const strokeDashoffset = circumference - (animatedScore / 100) * circumference;

  const colorMap = {
    emerald: '#10b981',
    green: '#22c55e',
    blue: '#3b82f6',
    yellow: '#eab308',
    orange: '#f97316',
    red: '#ef4444'
  };

  const actualColor = colorMap[color] || color;

  return (
    <div className="score-gauge" style={{ width: size, height: size }}>
      <svg width={size} height={size} viewBox={`0 0 ${size} ${size}`}>
        <circle
          className="score-gauge-circle-bg"
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke="rgba(255, 255, 255, 0.1)"
          strokeWidth={strokeWidth}
        />
        <circle
          className="score-gauge-circle"
          cx={size / 2}
          cy={size / 2}
          r={radius}
          fill="none"
          stroke={actualColor}
          strokeWidth={strokeWidth}
          strokeDasharray={circumference}
          strokeDashoffset={strokeDashoffset}
          strokeLinecap="round"
          transform={`rotate(-90 ${size / 2} ${size / 2})`}
        />
      </svg>
      <div className="score-gauge-text">
        <div className="score-gauge-grade" style={{ color: actualColor }}>{grade}</div>
        <div className="score-gauge-value">{score}/100</div>
      </div>
    </div>
  );
};

export default ScoreGauge;
