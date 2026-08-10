const API_BASE = '/api';

async function request(endpoint, options = {}) {
  const headers = {
    'Content-Type': 'application/json',
    ...options.headers,
  };

  if (options.token) {
    headers['Authorization'] = `Bearer ${options.token}`;
  }

  if (options.body instanceof FormData || headers['Content-Type'] === null) {
    delete headers['Content-Type'];
  }

  const config = {
    ...options,
    headers,
  };
  
  delete config.token;
  delete config.isDownload;

  const response = await fetch(`${API_BASE}${endpoint}`, config);

  if (!response.ok) {
    let errorMessage = `API Error: ${response.status} ${response.statusText}`;
    try {
      const errorData = await response.json();
      errorMessage = errorData.detail || errorMessage;
    } catch (e) {
      // Ignore json parsing errors
    }
    throw new Error(errorMessage);
  }
  
  if (options.isDownload) {
    return response.blob();
  }

  return response.json();
}

function buildQueryString(params = {}) {
  const query = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      query.append(key, value);
    }
  }
  const qs = query.toString();
  return qs ? `?${qs}` : '';
}

/**
 * First stage of signing in.
 *
 * Resolves either with a session or, for an account carrying a second factor, with
 * `{ mfa_required: true, mfa_token }` and no access token. Callers must look at
 * `mfa_required` rather than assuming a token is present.
 */
export async function login(username, password) {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.detail || 'Login failed');
  }
  return body;
}

/** Second stage: exchanges the challenge and a code for a session. */
export async function completeTwoFactor(mfaToken, code) {
  const response = await fetch(`${API_BASE}/auth/login/2fa`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ mfaToken, code }),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    throw new Error(body.detail || 'That code could not be verified.');
  }
  return body;
}

// ─── Two-factor enrolment ───────────────────────────────────────

export async function getTwoFactorStatus(token) {
  return request('/auth/2fa', { token });
}

export async function beginTwoFactorSetup(token) {
  return request('/auth/2fa/setup', { token, method: 'POST' });
}

export async function enableTwoFactor(token, code) {
  return request('/auth/2fa/enable', { token, method: 'POST', body: JSON.stringify({ code }) });
}

export async function disableTwoFactor(token, password) {
  return request('/auth/2fa/disable', { token, method: 'POST', body: JSON.stringify({ password }) });
}

export async function regenerateRecoveryCodes(token) {
  return request('/auth/2fa/recovery-codes', { token, method: 'POST' });
}

/**
 * Creates an organization and its first administrator. The account stays inactive
 * until the emailed link is followed, so this resolves with a message rather than
 * a session.
 */
export async function register(payload) {
  const response = await fetch(`${API_BASE}/auth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || body.message || `Registration failed (${response.status})`);
    error.status = response.status;
    throw error;
  }
  return body;
}

/**
 * Asks for a password reset link.
 *
 * Resolves the same way whether or not the address belongs to an account — the
 * server deliberately refuses to distinguish them, so the interface must not
 * pretend to know either.
 */
export async function requestPasswordReset(email) {
  const response = await fetch(`${API_BASE}/auth/forgot-password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ email }),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || 'Could not send a reset link. Try again.');
    error.status = response.status;
    throw error;
  }
  return body;
}

/** Redeems a reset link and sets the new password. No current password is asked for. */
export async function resetPassword(token, password) {
  const response = await fetch(`${API_BASE}/auth/reset-password`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ token, password }),
  });

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || 'This reset link is not valid.');
    error.status = response.status;
    throw error;
  }
  return body;
}

/** Redeems the emailed verification token and activates the account. */
export async function verifyEmail(token) {
  const response = await fetch(`${API_BASE}/auth/verify?token=${encodeURIComponent(token)}`);

  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || body.message || 'This verification link is not valid.');
    error.status = response.status;
    throw error;
  }
  return body;
}

export async function getMe(token) {
  return request('/auth/me', { token });
}

export async function getOverviewStats(token, params = {}) {
  return request(`/stats/overview${buildQueryString(params)}`, { token });
}

export async function getTimeline(token, params = {}) {
  return request(`/stats/timeline${buildQueryString(params)}`, { token });
}

export async function getTopSenders(token, params = {}) {
  return request(`/stats/top-senders${buildQueryString(params)}`, { token });
}

/** Per-domain configuration posture, from stored analyses. Weakest first. */
export async function getDomainPosture(token) {
  return request('/stats/domain-posture', { token });
}

export async function getDomainStats(token, params = {}) {
  return request(`/stats/domains${buildQueryString(params)}`, { token });
}

export async function getReports(token, params = {}) {
  return request(`/reports${buildQueryString(params)}`, { token });
}

export async function getReport(token, id) {
  return request(`/reports/${id}`, { token });
}

export async function getAlerts(token, params = {}) {
  return request(`/alerts${buildQueryString(params)}`, { token });
}

export async function getAlertCount(token) {
  return request('/alerts/count', { token });
}

export async function markAlertRead(token, id) {
  return request(`/alerts/${id}/read`, { token, method: 'PATCH' });
}

export async function markAllAlertsRead(token) {
  return request('/alerts/mark-all-read', { token, method: 'PATCH' });
}

export async function getUsers(token) {
  return request('/admin/users', { token });
}

export async function changeUserRole(token, id, role) {
  return request(`/admin/users/${id}/role`, {
    token, method: 'PATCH', body: JSON.stringify({ role }),
  });
}

export async function setUserActive(token, id, active) {
  return request(`/admin/users/${id}/active`, {
    token, method: 'PATCH', body: JSON.stringify({ active }),
  });
}

/** Issues a fresh generated password. Shown once — it is only stored hashed. */
export async function resetUserPassword(token, id) {
  return request(`/admin/users/${id}/reset-password`, { token, method: 'POST' });
}

export async function deleteUser(token, id) {
  return request(`/admin/users/${id}`, { token, method: 'DELETE' });
}

// ─── Team: invitations and claimed email domains ───────────────────────────

/** Preview an invitation without an account — the token is the credential. */
export async function getInvitation(token) {
  const response = await fetch(`${API_BASE}/public/invitation/${encodeURIComponent(token)}`);
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || 'This invitation link is not valid.');
    error.status = response.status;
    throw error;
  }
  return body;
}

export async function acceptInvitation(token, username, password) {
  const response = await fetch(`${API_BASE}/public/invitation/${encodeURIComponent(token)}/accept`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  });
  const body = await response.json().catch(() => ({}));
  if (!response.ok) {
    const error = new Error(body.detail || 'Could not accept the invitation.');
    error.status = response.status;
    throw error;
  }
  return body;
}

export async function getInvitations(token) {
  return request('/admin/team/invitations', { token });
}

export async function inviteMember(token, email, role) {
  return request('/admin/team/invitations', {
    token, method: 'POST', body: JSON.stringify({ email, role }),
  });
}

export async function revokeInvitation(token, id) {
  return request(`/admin/team/invitations/${id}`, { token, method: 'DELETE' });
}

export async function getClaimedDomains(token) {
  return request('/admin/team/domains', { token });
}

export async function claimDomain(token, domain, defaultRole) {
  return request('/admin/team/domains', {
    token, method: 'POST', body: JSON.stringify({ domain, defaultRole }),
  });
}

export async function verifyClaimedDomain(token, id) {
  return request(`/admin/team/domains/${id}/verify`, { token, method: 'POST' });
}

export async function releaseClaimedDomain(token, id) {
  return request(`/admin/team/domains/${id}`, { token, method: 'DELETE' });
}

/** Changes the signed-in user's own password. */
export async function changeOwnPassword(token, currentPassword, newPassword) {
  return request('/auth/change-password', {
    token, method: 'POST', body: JSON.stringify({ currentPassword, newPassword }),
  });
}

// ─── Platform console, for whoever runs the service ───

export async function getPlatformOverview(token) {
  return request('/platform/overview', { token });
}

/** Every organization, with its accounts. Volume only — never report contents. */
export async function getPlatformOrganizations(token) {
  return request('/platform/organizations', { token });
}

export async function setPlatformAccountActive(token, id, active) {
  return request(`/platform/accounts/${id}/active`, {
    token, method: 'PATCH', body: JSON.stringify({ active }),
  });
}

/** Refused unless the organization holds nothing at all. */
/** Where the backups stand: how many, how old, and whether that is acceptable. */
export async function getBackupStatus(token) {
  return request('/platform/backups', { token });
}

/** Takes one now rather than waiting for the schedule. */
export async function runBackup(token) {
  return request('/platform/backups', { token, method: 'POST' });
}

/**
 * Downloads a dump.
 *
 * Fetched rather than linked because the endpoint needs the bearer token, which an
 * anchor cannot carry. Resolves with a Blob for the caller to save.
 */
export async function downloadBackup(token, name) {
  return request(`/platform/backups/${encodeURIComponent(name)}`, {
    token, isDownload: true,
  });
}

/** Who did what, filtered. Operator only. */
export async function getAuditTrail(token, params = {}) {
  const query = new URLSearchParams(
    Object.entries(params).filter(([, v]) => v !== undefined && v !== '' && v !== null),
  ).toString();
  return request(`/platform/audit${query ? `?${query}` : ''}`, { token });
}

/** Ends every session an account holds, without disabling the account. */
export async function revokePlatformSessions(token, userId) {
  return request(`/platform/accounts/${userId}/revoke-sessions`, { token, method: 'POST' });
}

/** Ends every session on the caller's own account, this one included. */
export async function signOutEverywhere(token) {
  return request('/auth/sign-out-everywhere', { token, method: 'POST' });
}

export async function removePlatformOrganization(token, id) {
  return request(`/platform/organizations/${id}`, { token, method: 'DELETE' });
}

// ─── Direct database access, operator only ───────────────────────

export async function getDatabaseTables(token) {
  return request('/platform/database/tables', { token });
}

export async function getDatabaseRows(token, table, params = {}) {
  return request(
    `/platform/database/tables/${encodeURIComponent(table)}${buildQueryString(params)}`,
    { token },
  );
}

/** Destructive: the operator's own password is re-checked server-side. */
export async function deleteDatabaseRow(token, table, key, password) {
  return request(
    `/platform/database/tables/${encodeURIComponent(table)}/rows/${encodeURIComponent(key)}/delete`,
    { token, method: 'POST', body: JSON.stringify({ password }) },
  );
}

export async function clearDatabaseTable(token, table, password) {
  return request(`/platform/database/tables/${encodeURIComponent(table)}/clear`, {
    token, method: 'POST', body: JSON.stringify({ password }),
  });
}

export async function runPlatformCollection(token) {
  return request('/platform/collect', { token, method: 'POST' });
}

// ─── Mailbox collection, per organization ─────────────────────────

export async function getMailboxSettings(token) {
  return request('/admin/mailbox', { token });
}

/** Omit `password` to keep the stored one; it is never returned. */
export async function saveMailboxSettings(token, payload) {
  return request('/admin/mailbox', { token, method: 'PUT', body: JSON.stringify(payload) });
}

export async function deleteMailboxSettings(token) {
  return request('/admin/mailbox', { token, method: 'DELETE' });
}

export async function triggerIngestion(token) {
  return request('/admin/ingest', { token, method: 'POST' });
}

/**
 * Uploads DMARC aggregate reports (.xml, .xml.gz or .zip).
 * Resolves with a tally of what was stored, skipped, and rejected.
 */
export async function uploadReports(token, files) {
  const form = new FormData();
  for (const file of files) {
    form.append('files', file);
  }

  return request('/admin/reports/upload', { token, method: 'POST', body: form });
}

/** Names a download after what it contains, so saved files stay tellable apart. */
function exportFilename(extension, { domain } = {}) {
  const scope = domain ? domain.replace(/[^a-z0-9.-]/gi, '_') : 'all-domains';
  const day = new Date().toISOString().slice(0, 10);
  return `dmarc-${scope}-${day}.${extension}`;
}

function saveBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  window.URL.revokeObjectURL(url);
  document.body.removeChild(a);
}

export async function exportCSV(token, params = {}) {
  const blob = await request(`/export/csv${buildQueryString(params)}`, { token, isDownload: true });
  saveBlob(blob, exportFilename('csv', params));
}

export async function exportPDF(token, params = {}) {
  const blob = await request(`/export/pdf${buildQueryString(params)}`, { token, isDownload: true });
  saveBlob(blob, exportFilename('pdf', params));
}

/**
 * Runs an anonymous scan. No token: this is the public endpoint, which is rate
 * limited server-side. A 429 carries `retry_after_seconds`, so surface that
 * rather than a generic failure.
 */
export async function publicScan(domain) {
  const response = await fetch(`${API_BASE}/public/scan`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ domain }),
  });

  const body = await response.json().catch(() => ({}));

  if (!response.ok) {
    const error = new Error(body.detail || `Scan failed (${response.status})`);
    error.status = response.status;
    error.retryAfterSeconds = body.retry_after_seconds;
    throw error;
  }

  return body;
}

/** Reads the last stored result for a domain. Backs shareable links; never re-scans. */
export async function getPublicScan(domain) {
  const response = await fetch(`${API_BASE}/public/scan/${encodeURIComponent(domain)}`);
  const body = await response.json().catch(() => ({}));

  if (!response.ok) {
    const error = new Error(body.detail || `Not found (${response.status})`);
    error.status = response.status;
    throw error;
  }

  return body;
}

/**
 * Runs a live DNS check.
 *
 * @param dkimSelector optional. DKIM selectors cannot be listed from DNS, so a key
 *   is only found by guessing its name — naming yours is the difference between
 *   "we could not tell" and an answer.
 */
export async function analyzeDomain(token, domain, dkimSelector) {
  return request('/analysis/domain', {
    token,
    method: 'POST',
    body: JSON.stringify({ domain, dkimSelector: dkimSelector || undefined }),
  });
}

/**
 * Whether mail to this domain travels encrypted.
 *
 * Separate from analyzeDomain because it is slower by a different order: reaching
 * each MX server on port 25 takes seconds, and folding it in would make every
 * analysis wait for it.
 */
export async function checkTransportSecurity(token, domain) {
  return request('/analysis/transport', {
    token, method: 'POST', body: JSON.stringify({ domain }),
  });
}

export async function getAnalysisHistory(token, params = {}) {
  return request(`/analysis/history${buildQueryString(params)}`, { token });
}

export async function getAnalysis(token, id) {
  return request(`/analysis/${id}`, { token });
}

/**
 * The rules the score is built from, served by the scoring engine itself.
 *
 * The dashboard used to carry its own hand-written copy of these numbers, which
 * fell eight values behind the engine — including five points credited to BIMI
 * that were never awarded.
 */
export async function getScoringModel(token) {
  return request('/analysis/scoring-model', { token });
}

/** Operational counts for the caller's own organization. Administrators only. */
export async function getAdminOverview(token) {
  return request('/admin/overview', { token });
}

/** What this deployment is actually running, read from the process. */
export async function getSystemInfo(token) {
  return request('/system/info', { token });
}
