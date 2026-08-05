/**
 * Role helpers.
 *
 * The API returns roles uppercase ("ADMIN"); the UI compared against lowercase
 * literals, so `role !== 'admin'` was always true — administrators were redirected
 * away from the admin page and the sidebar link never appeared at all. Comparing
 * through here removes the chance of that recurring.
 */
export const ROLE = { ADMIN: 'ADMIN', ANALYST: 'ANALYST', VIEWER: 'VIEWER' };

const normalise = (role) => String(role ?? '').trim().toUpperCase();

export const isAdmin = (user) => normalise(user?.role) === ROLE.ADMIN;

/** Admins and analysts may change things; viewers are strictly read-only. */
export const canEdit = (user) =>
  [ROLE.ADMIN, ROLE.ANALYST].includes(normalise(user?.role));

export const roleLabel = (role) => {
  switch (normalise(role)) {
    case ROLE.ADMIN: return 'Administrator';
    case ROLE.ANALYST: return 'Analyst';
    case ROLE.VIEWER: return 'Viewer';
    default: return role ?? 'Unknown';
  }
};

export const roleDescription = (role) => {
  switch (normalise(role)) {
    case ROLE.ADMIN:
      return 'Full access, including managing accounts.';
    case ROLE.ANALYST:
      return 'Can run analyses, import reports and act on alerts.';
    case ROLE.VIEWER:
      return 'Read-only. Can see everything, change nothing.';
    default:
      return '';
  }
};
