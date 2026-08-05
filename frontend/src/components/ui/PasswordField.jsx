import React, { useMemo, useState } from 'react';
import { Lock, Eye, EyeOff, Check, X } from 'lucide-react';
import './PasswordField.css';

const MIN_LENGTH = 12;

/**
 * The rules the server enforces, restated so they can be checked while typing.
 *
 * These mirror PasswordPolicy on the backend. The server remains the authority —
 * this only saves the user a round trip to discover a rule they have already broken.
 */
const RULES = [
  {
    id: 'length',
    label: `At least ${MIN_LENGTH} characters`,
    test: (value) => value.length >= MIN_LENGTH,
  },
  {
    id: 'classes',
    label: 'Three of: uppercase, lowercase, digits, symbols',
    test: (value) => {
      let classes = 0;
      if (/[A-Z]/.test(value)) classes++;
      if (/[a-z]/.test(value)) classes++;
      if (/[0-9]/.test(value)) classes++;
      if (/[^A-Za-z0-9]/.test(value)) classes++;
      return classes >= 3;
    },
  },
  {
    id: 'runs',
    label: 'No long repeated or sequential runs',
    test: (value) => {
      const lower = value.toLowerCase();
      let repeat = 1;
      let up = 1;
      let down = 1;
      for (let i = 1; i < lower.length; i++) {
        const a = lower.charCodeAt(i - 1);
        const b = lower.charCodeAt(i);
        repeat = b === a ? repeat + 1 : 1;
        up = b === a + 1 ? up + 1 : 1;
        down = b === a - 1 ? down + 1 : 1;
        if (repeat >= 4 || up >= 4 || down >= 4) return false;
      }
      return true;
    },
  },
];

const STRENGTH = ['Too weak', 'Weak', 'Fair', 'Strong'];

/**
 * Password input with live rule feedback.
 *
 * The checklist is shown only once typing starts: presenting a wall of red crosses
 * to someone who has not typed anything reads as failure rather than guidance.
 */
const PasswordField = ({
  value,
  onChange,
  placeholder = 'Password',
  label = 'Password',
  autoComplete = 'new-password',
  showChecklist = true,
  id,
}) => {
  const [visible, setVisible] = useState(false);

  const results = useMemo(
    () => RULES.map((rule) => ({ ...rule, ok: rule.test(value || '') })),
    [value],
  );

  const met = results.filter((r) => r.ok).length;
  const bonus = (value || '').length >= 16 ? 1 : 0;
  const level = value ? Math.min(3, Math.max(0, met - 3 + bonus + (met === 3 ? 2 : 0))) : 0;

  return (
    <div className="password-field">
      <div className="input-group">
        <input
          id={id}
          type={visible ? 'text' : 'password'}
          value={value}
          onChange={onChange}
          placeholder={placeholder}
          aria-label={label}
          autoComplete={autoComplete}
          required
        />
        <Lock size={18} className="input-icon" />
        <button
          type="button"
          className="password-toggle"
          onClick={() => setVisible(!visible)}
          aria-label={visible ? 'Hide password' : 'Show password'}
        >
          {visible ? <EyeOff size={18} /> : <Eye size={18} />}
        </button>
      </div>

      {showChecklist && value && (
        <>
          <div className="strength" aria-hidden="true">
            {[0, 1, 2, 3].map((i) => (
              <span key={i} className={i <= level ? `bar lvl-${level}` : 'bar'} />
            ))}
          </div>
          <p className={`strength-label lvl-${level}`}>{STRENGTH[level]}</p>

          <ul className="password-rules">
            {results.map((rule) => (
              <li key={rule.id} className={rule.ok ? 'ok' : ''}>
                {rule.ok ? <Check size={13} /> : <X size={13} />}
                <span>{rule.label}</span>
              </li>
            ))}
          </ul>
        </>
      )}
    </div>
  );
};

export default PasswordField;
