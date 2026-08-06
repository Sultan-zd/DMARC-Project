import React from 'react';

/**
 * Turning stored values into readable ones, without changing what they say.
 *
 * <p>A database console has one obligation a dashboard does not: it must not
 * improve on the data. Every function here is presentational only — a timestamp is
 * reformatted but never shifted, a boolean is given a word but not an
 * interpretation, and anything unrecognised falls through as its own text rather
 * than being guessed at.
 */

const numbers = new Intl.NumberFormat('en-GB');

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun',
  'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/**
 * Timestamps are stored in UTC and shown in UTC.
 *
 * The obvious approach — `new Date(value).toLocaleString()` — reads a value with no
 * zone marker as local time, so a row written at 14:32 UTC would display as 14:32
 * in Casablanca and 14:32 in Beirut while being neither. A console exists to show
 * what is in the column, so the components are read out directly and labelled.
 */
const TIMESTAMP = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})/;

export const parseStamp = (value) => {
  const match = TIMESTAMP.exec(String(value ?? ''));
  if (!match) return null;
  const [, year, month, dayOfMonth, hour, minute, second] = match;
  return {
    year, month, dayOfMonth, hour, minute, second,
    epoch: Date.UTC(+year, +month - 1, +dayOfMonth, +hour, +minute, +second),
  };
};

export const formatStamp = (value) => {
  const stamp = parseStamp(value);
  if (!stamp) return String(value);
  return `${stamp.dayOfMonth} ${MONTHS[+stamp.month - 1]} ${stamp.year}, `
    + `${stamp.hour}:${stamp.minute}`;
};

/** "3 days ago", "in 6 days" — the thing you actually want to know about a date. */
export const relative = (value) => {
  const stamp = parseStamp(value);
  if (!stamp) return null;

  const seconds = Math.round((stamp.epoch - Date.now()) / 1000);
  const ago = Math.abs(seconds);
  const [amount, unit] =
    ago < 60 ? [seconds, 'second']
      : ago < 3600 ? [Math.round(seconds / 60), 'minute']
        : ago < 86400 ? [Math.round(seconds / 3600), 'hour']
          : ago < 2592000 ? [Math.round(seconds / 86400), 'day']
            : ago < 31536000 ? [Math.round(seconds / 2592000), 'month']
              : [Math.round(seconds / 31536000), 'year'];

  return new Intl.RelativeTimeFormat('en-GB', { numeric: 'auto' }).format(amount, unit);
};

/**
 * Values that carry a verdict, decided per column rather than per word.
 *
 * The same token means opposite things in different columns: `reject` as a policy
 * is the strongest setting there is, while `reject` as a disposition means a real
 * message was thrown away. One shared lookup would have to pick one of those and be
 * wrong about the other.
 */
const TONES = {
  role: { admin: 'accent', analyst: 'accent', viewer: 'muted' },
  default_role: { admin: 'accent', analyst: 'accent', viewer: 'muted' },
  severity: { high: 'bad', medium: 'warn', low: 'muted' },
  // Policy: what the domain asks receivers to do. `none` is monitoring only.
  policy: { none: 'warn', quarantine: 'good', reject: 'good' },
  sp_policy: { none: 'warn', quarantine: 'good', reject: 'good' },
  // Disposition: what a receiver actually did to real messages.
  disposition: { none: 'muted', quarantine: 'warn', reject: 'bad' },
  spf_result: { pass: 'good', fail: 'bad', softfail: 'warn', neutral: 'warn', none: 'warn' },
  dkim_result: { pass: 'good', fail: 'bad', neutral: 'warn', none: 'warn' },
  grade: { 'a+': 'good', a: 'good', b: 'warn', c: 'warn', d: 'bad', f: 'bad' },
  alert_type: {},
};

export const chipTone = (column, value) => {
  const scale = TONES[String(column).toLowerCase()];
  if (!scale) return null;
  return scale[String(value).toLowerCase()] ?? 'muted';
};

/**
 * One cell.
 *
 * @param compact drop the secondary line — the grid has a row height to keep, the
 *                detail panel does not
 */
export const renderValue = (value, column, references, compact = true) => {
  if (value === null || value === undefined || value === '') {
    return <span className="db-null">null</span>;
  }

  if (column.credential) {
    return <span className="db-secret">{String(value)}</span>;
  }

  // A resolved foreign key: the name is what a person needs, the key is what the
  // other tables hold, so neither one replaces the other.
  const label = references?.[column.name]?.[String(value)];
  if (label) {
    return (
      <span className="db-ref">
        <span className="db-ref-label">{label}</span>
        <span className="db-ref-key">#{String(value)}</span>
      </span>
    );
  }

  if (column.kind === 'boolean') {
    const yes = value === true || value === 'true' || value === 1 || value === '1';
    return <span className={`db-flag ${yes ? 'yes' : 'no'}`}>{yes ? 'Yes' : 'No'}</span>;
  }

  if (column.kind === 'date') {
    // The stored value in the tooltip, because a console should always be able to
    // show exactly what is in the column. How long ago it was belongs to whoever
    // is laying out the field — the detail panel prints it beside the zone.
    return (
      <span className="db-date" title={String(value)}>{formatStamp(value)}</span>
    );
  }

  if (column.kind === 'number') {
    return <span className="db-number">{numbers.format(value)}</span>;
  }

  if (column.kind === 'json') {
    return <span className="db-json-chip">JSON · {String(value).length} chars</span>;
  }

  const tone = chipTone(column.name, value);
  if (tone) {
    return <span className={`db-chip tone-${tone}`}>{String(value)}</span>;
  }

  const text = String(value);
  if (compact && text.length > 64) {
    return <span title={text}>{text.slice(0, 64)}…</span>;
  }
  return text;
};

/** Pretty-prints JSON for the detail panel, leaving it alone if it will not parse. */
export const prettyJson = (value) => {
  try {
    return JSON.stringify(JSON.parse(value), null, 2);
  } catch {
    return String(value);
  }
};

export const formatBytes = (kb) => {
  if (!kb) return '—';
  return kb >= 1024 ? `${(kb / 1024).toFixed(1)} MB` : `${numbers.format(kb)} KB`;
};

export const count = (value) => numbers.format(value ?? 0);
