import React, { useState, useEffect } from 'react';
import Header from '../components/layout/Header';
import StatCard from '../components/ui/StatCard';
import DomainPosture from '../components/dashboard/DomainPosture';
import AnimatedCounter from '../components/ui/AnimatedCounter';
import LoadingSpinner from '../components/ui/LoadingSpinner';
import { useAuth } from '../context/AuthContext';
import { useToast } from '../context/ToastContext';
import { useDomain } from '../context/DomainContext';
import usePageTitle from '../hooks/usePageTitle';
import * as api from '../services/api';
import { AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer, PieChart, Pie, Cell, Legend } from 'recharts';
import { FileText, Mail, ShieldCheck, ShieldAlert, Shield, Server, RefreshCw } from 'lucide-react';
import './Dashboard.css';

const Dashboard = () => {
  usePageTitle('Dashboard');
  const { token } = useAuth();
  const { showToast } = useToast();
  const { selectedDomain } = useDomain();
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const [stats, setStats] = useState(null);
  const [timeline, setTimeline] = useState([]);
  const [topSenders, setTopSenders] = useState([]);
  const [posture, setPosture] = useState([]);

  const loadData = async (isRefresh = false) => {
    if (isRefresh) setRefreshing(true);
    else setLoading(true);

    try {
      const apiParams = selectedDomain ? { domain: selectedDomain } : {};
      const [overviewData, timelineData, sendersData, postureData] = await Promise.all([
        api.getOverviewStats(token, apiParams),
        api.getTimeline(token, { days: 30, ...apiParams }),
        api.getTopSenders(token, { limit: 10, ...apiParams }),
        api.getDomainPosture(token)
      ]);
      setStats(overviewData);
      setTimeline(timelineData);
      setTopSenders(sendersData);
      setPosture(postureData || []);
    } catch (error) {
      console.error("Error loading dashboard data:", error);
      showToast('Failed to load dashboard data', 'error');
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  };

  useEffect(() => {
    if (token) {
      loadData();
    }
  }, [token, selectedDomain]);

  if (loading && !stats) {
    return <LoadingSpinner />;
  }

  const formatNumber = (num) => new Intl.NumberFormat('en-US').format(num || 0);
  const formatPercent = (num) => `${(num || 0).toFixed(1)}%`;

  const getRateColor = (rate) => {
    if (rate >= 90) return 'success';
    if (rate >= 75) return 'warning';
    return 'danger';
  };

  const pieData = stats?.policy_distribution ? [
    // Policy strength, weakest to strongest: p=none applies no enforcement at all,
    // so it is flagged amber rather than treated as a neutral category.
    { name: 'None', value: stats.policy_distribution.none, color: '#E09400' },
    { name: 'Quarantine', value: stats.policy_distribution.quarantine, color: '#045cb4' },
    { name: 'Reject', value: stats.policy_distribution.reject, color: '#00AE4E' }
  ].filter(d => d.value > 0) : [];

  const CustomTooltip = ({ active, payload, label }) => {
    if (active && payload && payload.length) {
      return (
        <div className="custom-tooltip dark-tooltip">
          <p className="tooltip-label">{label}</p>
          {payload.map((entry, index) => (
            <p key={index} style={{ color: entry.color }}>
              {entry.name}: {formatNumber(entry.value)}
            </p>
          ))}
        </div>
      );
    }
    return null;
  };

  return (
    <div className="dashboard">
      <div className="dashboard-header-container">
        <Header
          title="Dashboard"
          subtitle="What was actually sent as your domains"
          note="Aggregated from the DMARC reports mailbox providers send you. These figures describe real traffic — not how your DNS is configured. See Analysis for that."
        />
        <button 
          className="refresh-btn" 
          onClick={() => loadData(true)}
          disabled={refreshing}
          title="Refresh"
        >
          <RefreshCw className={refreshing ? 'spin' : ''} size={20} />
        </button>
      </div>

      <div className="posture-row-wrap">
        <DomainPosture domains={posture} />
      </div>

      {stats && stats.total_reports === 0 && (
        <div className="glass-card" style={{ textAlign: 'center', padding: '4rem 2rem', marginTop: '2rem' }}>
          <div style={{ display: 'inline-flex', padding: '1rem', background: 'var(--bg-secondary)', borderRadius: '50%', marginBottom: '1.5rem' }}>
            <FileText size={48} color="var(--text-muted)" />
          </div>
          <h2 style={{ marginBottom: '1rem' }}>No DMARC Reports Received Yet</h2>
          <p style={{ color: 'var(--text-muted)', maxWidth: '600px', margin: '0 auto 2rem', lineHeight: '1.6' }}>
            The Dashboard is designed to display daily aggregated DMARC XML reports sent by email providers (like Gmail or Microsoft). 
            Unlike the instant Domain Analysis tool, this requires you to configure your DMARC DNS record with an <code>rua=mailto:your-email@domain.com</code> address, and then ingest those XML files via the Admin panel.
          </p>
        </div>
      )}

      {stats && stats.total_reports > 0 && (
        <div className="dashboard-grid">
          <div className="kpi-row">
            <div className="fade-in stagger-1"><StatCard icon={FileText} value={<AnimatedCounter value={stats.total_reports} />} label="Total Reports" color="accent" /></div>
            <div className="fade-in stagger-2"><StatCard icon={Mail} value={<AnimatedCounter value={stats.total_emails} />} label="Total Emails" color="info" /></div>
            <div className="fade-in stagger-3"><StatCard icon={ShieldCheck} value={<AnimatedCounter value={stats.spf_pass_rate} decimals={1} suffix="%" />} label="SPF Rate" color={getRateColor(stats.spf_pass_rate)} /></div>
            <div className="fade-in stagger-4"><StatCard icon={ShieldCheck} value={<AnimatedCounter value={stats.dkim_pass_rate} decimals={1} suffix="%" />} label="DKIM Rate" color={getRateColor(stats.dkim_pass_rate)} /></div>
            <div className="fade-in stagger-5"><StatCard icon={Shield} value={<AnimatedCounter value={stats.dmarc_pass_rate} decimals={1} suffix="%" />} label="DMARC Rate" color={getRateColor(stats.dmarc_pass_rate)} /></div>
            <div className="fade-in stagger-6"><StatCard icon={Server} value={<AnimatedCounter value={stats.unique_sources} />} label="Unique Sources" color="accent" /></div>
          </div>

          <div className="charts-row">
            <div className="chart-card glass-card">
              <h3>Email Volume (Last 30 Days)</h3>
              <div className="chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <AreaChart data={timeline} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                    <defs>
                      <linearGradient id="colorSpfPass" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#00AE4E" stopOpacity={0.35}/>
                        <stop offset="95%" stopColor="#00AE4E" stopOpacity={0}/>
                      </linearGradient>
                      <linearGradient id="colorSpfFail" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="5%" stopColor="#c62828" stopOpacity={0.35}/>
                        <stop offset="95%" stopColor="#c62828" stopOpacity={0}/>
                      </linearGradient>
                    </defs>
                    <CartesianGrid strokeDasharray="3 3" stroke="var(--border-primary)" vertical={false} />
                    <XAxis dataKey="date" stroke="var(--text-muted)" fontSize={12} tickLine={false} />
                    <YAxis stroke="var(--text-muted)" fontSize={12} tickLine={false} axisLine={false} tickFormatter={formatNumber} />
                    <Tooltip content={<CustomTooltip />} />
                    <Area type="monotone" dataKey="spf_pass" name="SPF Pass" stroke="#00AE4E" fillOpacity={1} fill="url(#colorSpfPass)" strokeWidth={2} />
                    <Area type="monotone" dataKey="spf_fail" name="SPF Fail" stroke="#c62828" fillOpacity={1} fill="url(#colorSpfFail)" strokeWidth={2} />
                  </AreaChart>
                </ResponsiveContainer>
              </div>
            </div>

            <div className="chart-card glass-card">
              <h3>Policy Distribution</h3>
              <div className="chart-container">
                <ResponsiveContainer width="100%" height="100%">
                  <PieChart>
                    <Pie
                      data={pieData}
                      cx="50%"
                      cy="45%"
                      innerRadius={60}
                      outerRadius={80}
                      paddingAngle={5}
                      dataKey="value"
                      stroke="none"
                    >
                      {pieData.map((entry, index) => (
                        <Cell key={`cell-${index}`} fill={entry.color} />
                      ))}
                    </Pie>
                    <Tooltip content={<CustomTooltip />} />
                    <Legend wrapperStyle={{ paddingTop: '20px' }} />
                    <text x="50%" y="45%" textAnchor="middle" dominantBaseline="middle" fill="var(--text-primary)" fontSize="20" fontWeight="600">
                      {formatNumber(stats.total_reports)}
                    </text>
                  </PieChart>
                </ResponsiveContainer>
              </div>
            </div>
          </div>

          <div className="top-senders-card glass-card">
            <h3>Top 10 Senders</h3>
            <div className="table-responsive">
              <table className="senders-table">
                <thead>
                  <tr>
                    <th>Source IP</th>
                    <th>Total Emails</th>
                    <th>SPF Rate</th>
                    <th>DKIM Rate</th>
                    <th>Records</th>
                  </tr>
                </thead>
                <tbody>
                  {topSenders.map((sender, idx) => (
                    <tr key={idx}>
                      <td className="font-mono">{sender.source_ip}</td>
                      <td>{formatNumber(sender.total_emails)}</td>
                      <td>
                        <div className="progress-cell">
                          <span>{formatPercent(sender.spf_pass_rate)}</span>
                          <div className="progress-bar">
                            <div 
                              className="progress-fill" 
                              style={{ 
                                width: `${sender.spf_pass_rate}%`,
                                backgroundColor: `var(--${getRateColor(sender.spf_pass_rate)})`
                              }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td>
                        <div className="progress-cell">
                          <span>{formatPercent(sender.dkim_pass_rate)}</span>
                          <div className="progress-bar">
                            <div 
                              className="progress-fill" 
                              style={{ 
                                width: `${sender.dkim_pass_rate}%`,
                                backgroundColor: `var(--${getRateColor(sender.dkim_pass_rate)})`
                              }}
                            ></div>
                          </div>
                        </div>
                      </td>
                      <td>{formatNumber(sender.record_count)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
};

export default Dashboard;
