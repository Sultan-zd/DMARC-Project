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

  // Grade colours run from the Teknologiia green at the top of the scale down to
  // red, so a strong posture reads as "on brand" and a weak one visibly is not.
  const colorMap = {
    emerald: '#00AE4E',
    green: '#3FBF6E',
    blue: '#045cb4',
    yellow: '#d9a300',
    orange: '#e06c00',
    red: '#c62828'
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
          stroke="var(--gauge-track, rgba(0, 0, 0, 0.06))"
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
