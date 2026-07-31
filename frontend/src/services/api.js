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

export async function login(username, password) {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ username, password }),
  });

  if (!response.ok) {
    throw new Error('Login failed');
  }

  return response.json();
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

export async function createUser(token, userData) {
  return request('/admin/users', { 
    token, 
    method: 'POST',
    body: JSON.stringify(userData)
  });
}

export async function triggerIngestion(token) {
  return request('/admin/ingest', { token, method: 'POST' });
}

export async function exportCSV(token, params = {}) {
  const qs = buildQueryString(params);
  const blob = await request(`/export/csv${qs}`, { token, isDownload: true });
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'export.csv';
  document.body.appendChild(a);
  a.click();
  window.URL.revokeObjectURL(url);
  document.body.removeChild(a);
}

export async function exportPDF(token, params = {}) {
  const qs = buildQueryString(params);
  const blob = await request(`/export/pdf${qs}`, { token, isDownload: true });
  const url = window.URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = 'export.pdf';
  document.body.appendChild(a);
  a.click();
  window.URL.revokeObjectURL(url);
  document.body.removeChild(a);
}

export async function analyzeDomain(token, domain) {
  return request('/analysis/domain', { token, method: 'POST', body: JSON.stringify({ domain }) });
}

export async function getAnalysisHistory(token, params = {}) {
  return request(`/analysis/history${buildQueryString(params)}`, { token });
}

export async function getAnalysis(token, id) {
  return request(`/analysis/${id}`, { token });
}
