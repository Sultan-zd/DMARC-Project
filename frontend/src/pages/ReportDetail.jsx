import React, { useState, useEffect } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { ArrowLeft, Shield, ShieldCheck, ShieldAlert, Info } from 'lucide-react';
import * as api from '../services/api';
import { useAuth } from '../context/AuthContext';
import './ReportDetail.css';

const ReportDetail = () => {
  const { id } = useParams();
  const navigate = useNavigate();
  const { token } = useAuth();
  const [report, setReport] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  useEffect(() => {
    const fetchReport = async () => {
      try {
        const data = await api.getReport(token, id);
        setReport(data);
        setLoading(false);
      } catch (err) {
        console.error("Failed to load report", err);
        setError("Report not found or error loading.");
        setLoading(false);
      }
    };
    fetchReport();
  }, [id]);

  if (loading) {
    return (
      <div className="report-detail-loading">
        <div className="spinner"></div>
        <p>Loading report...</p>
      </div>
    );
  }

  if (error || !report) {
    return (
      <div className="report-detail-error glass-card">
        <ShieldAlert size={48} color="var(--danger)" />
        <h2>Error</h2>
        <p>{error}</p>
        <button className="btn btn-ghost" onClick={() => navigate('/reports')}>
          <ArrowLeft size={18} /> Back to reports
        </button>
      </div>
    );
  }

  const formatDate = (dateString) => {
    if (!dateString) return 'N/A';
    return new Date(dateString).toLocaleString('en-US');
  };

  const getStatusBadge = (status) => {
    let className = 'status-badge ';
    let label = status || 'unknown';
    
    switch (label.toLowerCase()) {
      case 'pass':
      case 'none':
        className += 'status-success';
        break;
      case 'fail':
      case 'reject':
        className += 'status-danger';
        break;
      case 'quarantine':
        className += 'status-warning';
        break;
      default:
        className += 'status-neutral';
    }
    
    return <span className={className}>{label}</span>;
  };

  return (
    <div className="report-detail-page">
      <div className="report-detail-header">
        <button className="btn btn-ghost" onClick={() => navigate('/reports')}>
          <ArrowLeft size={18} /> Back
        </button>
        <h1>DMARC Report Details</h1>
      </div>

      <div className="report-metadata-card glass-card">
        <div className="meta-header">
          <Shield className="meta-icon" size={24} />
          <h2>Report Metadata</h2>
        </div>
        <div className="meta-grid">
          <div className="meta-item">
            <span className="meta-label">Report ID</span>
            <span className="meta-value">{report.report_id || report.id}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">Organization</span>
            <span className="meta-value">{report.org_name || 'N/A'}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">Org Email</span>
            <span className="meta-value">{report.email || 'N/A'}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">Domain</span>
            <span className="meta-value">{report.domain || 'N/A'}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">Period</span>
            <span className="meta-value">
              {formatDate(report.date_begin)} - {formatDate(report.date_end)}
            </span>
          </div>
          <div className="meta-item">
            <span className="meta-label">DMARC Policy</span>
            <span className="meta-value">{getStatusBadge(report.policy_p)}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">SPF Alignment (aspf)</span>
            <span className="meta-value">{report.policy_aspf || 'N/A'}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">DKIM Alignment (adkim)</span>
            <span className="meta-value">{report.policy_adkim || 'N/A'}</span>
          </div>
          <div className="meta-item">
            <span className="meta-label">Percentage</span>
            <span className="meta-value">{report.policy_pct ? `${report.policy_pct}%` : 'N/A'}</span>
          </div>
        </div>
      </div>

      <div className="report-records-card glass-card">
        <h2>Authentication Records</h2>
        <div className="table-responsive">
          <table className="records-table">
            <thead>
              <tr>
                <th>Source IP</th>
                <th>Emails</th>
                <th>SPF Result</th>
                <th>DKIM Result</th>
                <th>Disposition</th>
                <th>SPF Domain</th>
                <th>DKIM Domain</th>
                <th>DKIM Selector</th>
                <th>Header From</th>
              </tr>
            </thead>
            <tbody>
              {report.records && report.records.length > 0 ? (
                report.records.map((record, index) => (
                  <tr key={index}>
                    <td>{record.source_ip}</td>
                    <td>{record.count}</td>
                    <td>{getStatusBadge(record.spf_result)}</td>
                    <td>{getStatusBadge(record.dkim_result)}</td>
                    <td>{getStatusBadge(record.disposition)}</td>
                    <td>{record.spf_domain || '-'}</td>
                    <td>{record.dkim_domain || '-'}</td>
                    <td>{record.dkim_selector || '-'}</td>
                    <td>{record.header_from || '-'}</td>
                  </tr>
                ))
              ) : (
                <tr>
                  <td colSpan="9" className="text-center">No records found</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
};

export default ReportDetail;
