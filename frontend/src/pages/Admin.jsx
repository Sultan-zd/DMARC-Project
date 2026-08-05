import React, { useCallback, useEffect, useState } from 'react';
import { ShieldAlert, UploadCloud } from 'lucide-react';
import ReportUpload from '../components/reports/ReportUpload';
import UserManagement from '../components/admin/UserManagement';
import TeamAccess from '../components/admin/TeamAccess';
import OperationsOverview from '../components/admin/OperationsOverview';
import MailboxSettings from '../components/admin/MailboxSettings';
import { isAdmin } from '../utils/roles';
import * as api from '../services/api';
import { useAuth } from '../context/AuthContext';
import usePageTitle from '../hooks/usePageTitle';
import './Admin.css';

const Admin = () => {
  usePageTitle('Administration');
  const { user, token } = useAuth();
  const [overview, setOverview] = useState(null);

  const loadOverview = useCallback(async () => {
    if (!token) return;
    try {
      setOverview(await api.getAdminOverview(token));
    } catch {
      // The overview is informational; the controls below work without it.
    }
  }, [token]);

  // Hooks must run on every render, so the role check comes after them.
  useEffect(() => { loadOverview(); }, [loadOverview]);

  if (!isAdmin(user)) {
    return (
      <div className="admin-access-denied glass-card">
        <ShieldAlert size={64} color="var(--danger)" />
        <h2>Access Denied</h2>
        <p>You do not have the necessary admin rights to view this page.</p>
      </div>
    );
  }

  return (
    <div className="admin-page">
      <div className="admin-header">
        <h1>Administration</h1>
        <p>Who is in this organization, and where its data comes from</p>
      </div>

      {/* What the organization looks like, before the controls that change it. */}
      <OperationsOverview overview={overview} />

      {/* Named sections rather than a stack of cards: an administrator arrives with
          one of three questions — who is in, how do they get in, where does the data
          come from — and the page answers them in that order. */}
      <section className="admin-section">
        <header className="admin-section-head">
          <h2>Access</h2>
          <p>How somebody becomes a member of this organization.</p>
        </header>
        <TeamAccess onChange={loadOverview} />
      </section>

      <section className="admin-section">
        <header className="admin-section-head">
          <h2>Members</h2>
          <p>The accounts that exist today, and what each one may do.</p>
        </header>
        <UserManagement />
      </section>

      <section className="admin-section">
        <header className="admin-section-head">
          <h2>Report intake</h2>
          <p>The two ways DMARC aggregate reports reach this dashboard.</p>
        </header>

      <div className="admin-grid">
        <div className="admin-card upload-card">
          <div className="card-header-icon">
            <UploadCloud size={20} className="icon-blue" />
            <h2>Import Reports</h2>
          </div>
          <p className="description">
            Upload DMARC aggregate reports received from mailbox providers. Archives are
            unpacked automatically and reports already imported are skipped.
          </p>

          <ReportUpload onUploaded={loadOverview} />
        </div>

        <MailboxSettings onChange={loadOverview} />

      </div>
      </section>
    </div>
  );
};

export default Admin;
