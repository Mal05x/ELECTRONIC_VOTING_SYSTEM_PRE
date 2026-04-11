import { useState, useEffect, useCallback } from "react";
import {
  AreaChart, Area, XAxis, YAxis, Tooltip, ResponsiveContainer,
  PieChart, Pie, Cell,
} from "recharts";
import client from "../api/client.js";
import { useAuth } from "../context/AuthContext.jsx";
import { useKeypair } from "../context/KeypairContext.jsx";
import { getAnomalyAlerts } from "../api/terminals.js";
import { getElections } from "../api/elections.js";
import { getAuditLog } from "../api/audit.js";
import { getNationalTally } from "../api/tally.js";
import { StatCard, Pbar, SectionHeader, ChartTooltip, Spinner, EmptyState, Ic } from "../components/ui.jsx";

const PIE_COLORS  = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED","#6D28D9","#4C1D95"];
const CAND_COLORS = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED"];

export default function DashboardView() {
  const { user } = useAuth();
  const { needsSetup, hasLocalKey } = useKeypair();
  const isSuperAdmin = user?.role === "SUPER_ADMIN";
  const [bannerDismissed, setBannerDismissed] = useState(
    () => sessionStorage.getItem("keypair_banner_dismissed") === "true"
  );
  const dismissBanner = useCallback(() => {
    sessionStorage.setItem("keypair_banner_dismissed", "true");
    setBannerDismissed(true);
  }, []);
  const showKeypairBanner = isSuperAdmin && needsSetup && !bannerDismissed;

  const [stats,       setStats]       = useState(null);
  const [elections,   setElections]   = useState([]);
  const [auditLogs,   setAuditLogs]   = useState([]);
  const [candidates,  setCandidates]  = useState([]);
  const [stateTally,  setStateTally]  = useState([]);
  const [regionType,  setRegionType]  = useState("STATE"); // STATE | LGA | POLLING_UNIT
  const [anomalies,   setAnomalies]   = useState([]);
  const [loading,     setLoading]     = useState(true);

  useEffect(() => {
    const load = async () => {
      setLoading(true);
      try {
        // Fire all requests in parallel
        const [statsRes, elecsRes, auditRes, anomalyRes] = await Promise.all([
          client.get("/admin/stats/overview").catch(() => null),
          getElections().catch(() => []),
          getAuditLog({ page: 0, size: 6 }).catch(() => []),
          getAnomalyAlerts().catch(() => []),
        ]);

        if (statsRes?.data) setStats(statsRes.data);
        setElections(Array.isArray(elecsRes) ? elecsRes : []);
        setAuditLogs(Array.isArray(auditRes) ? auditRes : []);
        setAnomalies(Array.isArray(anomalyRes) ? anomalyRes : []);

        // Load tally for active election
        const activeElec = (Array.isArray(elecsRes) ? elecsRes : [])
          .find(e => e.status === "ACTIVE");
        if (activeElec) {
          const tally = await getNationalTally(activeElec.id).catch(() => null);
          if (tally?.candidates) {
            setCandidates(tally.candidates.map((c, i) => ({
              ...c, color: CAND_COLORS[i % CAND_COLORS.length],
            })));
          }
          // Regional breakdown — endpoint adapts to election type automatically
          const regionRes = await client.get(`/results/${activeElec.id}/by-region`).catch(() => null);
          if (regionRes?.data && Array.isArray(regionRes.data)) {
            setStateTally(regionRes.data.slice(0, 6));
            // Store regionType for dynamic chart labelling
            const firstRow = regionRes.data[0];
            setRegionType(firstRow?.regionType || "STATE");
          }
        }
      } catch (err) {
        console.error("Dashboard load error:", err);
      } finally {
        setLoading(false);
      }
    };
    load();
  }, []);

 const formatVotes = (n) => {
     if (!n) return "0";
     if (n >= 1_000_000) return (n / 1_000_000).toFixed(2) + "M";
     if (n >= 1_000)     return (n / 1_000).toFixed(1) + "K";
     return n.toLocaleString();
   };
  const active   = elections.filter(e => e.status === "ACTIVE").length;
  const regVoters = stats?.registeredVoters ?? 0;
  const votesCast = stats?.votesCast        ?? 0;
  const turnout  = regVoters > 0 ? ((votesCast / regVoters) * 100).toFixed(1) : "0.0";
  const totalVotes = candidates.reduce((s, c) => s + (c.votes || 0), 0);

  const typeColor = t => {
    if (!t) return "text-purple-300";
    if (t.includes("FAIL") || t.includes("TAMPER") || t.includes("ERROR")) return "text-danger";
    if (t.includes("SUCCESS") || t.includes("COMPLETED") || t.includes("CAST")) return "text-success";
    if (t.includes("LOCK") || t.includes("CLOSE") || t.includes("WARN")) return "text-warning";
    return "text-purple-300";
  };

  if (loading) return (
    <div className="flex justify-center items-center h-full py-32"><Spinner s={40} /></div>
  );

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* ── Signing key setup banner (SUPER_ADMIN, dismissible per session) ── */}
      {showKeypairBanner && (
        <div className="relative flex items-start gap-4 p-5 rounded-2xl border
                        border-purple-500/25 bg-purple-500/6 animate-fade-up">
          {/* Icon */}
          <div className="w-10 h-10 rounded-xl bg-purple-500/15 border border-purple-500/25
                          flex items-center justify-center flex-shrink-0 mt-0.5">
            <Ic n="shield" s={18} c="#A78BFA" />
          </div>
          {/* Text */}
          <div className="flex-1 min-w-0">
            <div className="text-sm font-bold text-purple-300">Set up your signing key</div>
            <div className="text-xs text-sub mt-1 leading-relaxed max-w-lg">
              Sensitive actions — importing candidates, queuing enrollment, creating elections —
              require your cryptographic signing key. It takes under a minute to set up and
              stays in this browser.
            </div>
            <button
              onClick={() =>
                window.dispatchEvent(new CustomEvent("evoting:navigate",
                  { detail: { view: "settings", tab: "security" } }))
              }
              className="mt-3 btn btn-sm gap-2 bg-purple-500/20 border border-purple-500/40
                         text-purple-300 hover:bg-purple-500/30 rounded-xl
                         text-xs font-semibold transition-colors">
              <Ic n="shield" s={13} c="#A78BFA" /> Set Up Key in Settings
            </button>
          </div>
          {/* Dismiss */}
          <button
            onClick={dismissBanner}
            className="absolute top-3 right-3 text-muted hover:text-sub
                       transition-colors p-1 rounded-lg hover:bg-white/5"
            title="Dismiss for this session">
            <Ic n="close" s={14} />
          </button>
        </div>
      )}


      {/* Stat cards — live from /admin/stats/overview */}
      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
       <StatCard label="Registered Voters" value={formatVotes(regVoters)}
           sub={`${turnout}% turnout`} icon="voters" accent="purple" delay={0} />
         <StatCard label="Votes Cast" value={formatVotes(votesCast)}
           sub={votesCast > 0 ? "Verified on-chain" : "No votes yet"} icon="vote" accent="green" delay={60} />
        <StatCard label="Active Elections" value={active}
          sub={active > 0 ? "Running now" : "None active"} icon="ballot" accent="amber" delay={120} />
        <StatCard label="Online Terminals" value={(stats?.onlineTerminals ?? 0).toLocaleString()}
          sub={stats?.totalTerminals > 0
            ? `${((stats.onlineTerminals / stats.totalTerminals) * 100).toFixed(0)}% heartbeat OK`
            : "No terminals yet"}
          icon="chip" accent="blue" delay={180} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[1fr_300px] gap-5">

        {/* Candidate standings — real data from /api/results/{id} */}
        <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "200ms" }}>
          <SectionHeader title="Candidate Standings" sub="Live aggregated results from the ballot chain" />
          {candidates.length === 0 ? (
            <EmptyState icon="tally" title="No results yet"
              sub="Results appear here once an election is active and votes are cast" />
          ) : (
            <div className="flex flex-col gap-4 mt-2">
              {[...candidates].sort((a, b) => (b.votes || 0) - (a.votes || 0)).map((c, i) => {
                const pct = totalVotes > 0 ? ((c.votes || 0) / totalVotes * 100).toFixed(1) : "0.0";
                return (
                  <div key={c.id || i}>
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2.5">
                        {c.imageUrl ? (
                          <img src={c.imageUrl} alt={c.fullName}
                            className="w-7 h-7 rounded-full object-cover flex-shrink-0"
                            style={{ border: `2px solid ${c.color}` }}
                            onError={e => { e.target.style.display="none"; }} />
                        ) : (
                          <div className="w-7 h-7 rounded-full flex items-center justify-center
                                          text-[10px] font-bold text-white flex-shrink-0"
                            style={{ background: c.color }}>
                            {(c.party||"?")[0]}
                          </div>
                        )}
                        {i === 0 && <span className="text-base">🏆</span>}
                        <span className="text-sm font-bold text-ink">{c.fullName || c.name || "—"}</span>
                        <span className="badge badge-purple">{c.party}</span>
                      </div>
                      <div className="flex items-center gap-2.5">
                       {/* Replace the hardcoded 1e6 math with formatVotes */}
                         <span className="mono text-[15px] font-bold" style={{ color: c.color }}>
                           {(c.votes || 0) > 0 ? formatVotes(c.votes) : "0"}
                         </span>
                        <span className="mono text-xs text-sub w-10 text-right">{pct}%</span>
                      </div>
                    </div>
                    <Pbar pct={parseFloat(pct)} color={c.color} />
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* State pie chart — real data from /api/results/{id}/by-state */}
        <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "250ms" }}>
          <SectionHeader
            title={regionType === "STATE" ? "Votes by State"
                 : regionType === "LGA"   ? "Votes by LGA"
                                          : "Votes by Ward"}
            sub={regionType === "STATE" ? "Top contributing states"
               : regionType === "LGA"   ? "Top contributing LGAs"
                                        : "Top contributing polling units"}
          />
          {stateTally.length === 0 ? (
            <div className="flex items-center justify-center h-32 text-xs text-muted">
              No {regionType === "STATE" ? "state" : regionType === "LGA" ? "LGA" : "ward"} data yet
            </div>
          ) : (
            <>
              <ResponsiveContainer width="100%" height={140}>
                <PieChart>
                  <Pie data={stateTally} cx="50%" cy="50%"
                    innerRadius={38} outerRadius={58}
                    dataKey="totalVotes" nameKey="regionName"
                    paddingAngle={3} startAngle={90} endAngle={-270}>
                    {stateTally.map((_, i) => (
                      <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} stroke="none" />
                    ))}
                  </Pie>
                  <Tooltip content={<ChartTooltip />} />
                </PieChart>
              </ResponsiveContainer>
              <div className="flex flex-col gap-1.5 mt-2">
                {stateTally.slice(0, 5).map((s, i) => (
                  <div key={s.regionName || s.stateName || i} className="flex items-center gap-2.5">
                    <div className="w-2 h-2 rounded-[3px] flex-shrink-0"
                      style={{ background: PIE_COLORS[i % PIE_COLORS.length] }} />
                    <span className="text-xs font-medium text-sub flex-1 truncate">
                      {s.regionName || s.stateName}
                    </span>
                    <span className="mono text-xs text-ink">
                      {(s.totalVotes || 0) >= 1_000_000
                        ? `${((s.totalVotes || 0) / 1e6).toFixed(2)}M`
                        : (s.totalVotes || 0) >= 1_000
                          ? `${((s.totalVotes || 0) / 1e3).toFixed(1)}K`
                          : (s.totalVotes || 0).toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Recent audit events — live from /admin/audit-log */}
      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "320ms" }}>
        <SectionHeader title="Recent Events" sub="Latest audit log entries" />
        {auditLogs.length === 0 ? (
          <div className="py-6 text-center text-xs text-muted">No events recorded yet.</div>
        ) : (
          <div className="flex flex-col mt-2">
            {auditLogs.slice(0, 6).map((a, i) => (
              <div key={a.id || a.sequenceNumber || i}
                className="trow hover:bg-white/5 transition-colors rounded-lg
                           border-b border-border/50 last:border-0"
                style={{ gridTemplateColumns: "56px 180px 120px 1fr 90px", gap: "12px" }}>
                <span className="mono text-[10px] text-muted">
                  #{a.sequenceNumber || i + 1}
                </span>
                <span className={`mono text-[10px] font-bold truncate ${typeColor(a.eventType)}`}>
                  {a.eventType}
                </span>
                <span className="mono text-[10px] text-sub truncate">
                  {a.actor || "SYSTEM"}
                </span>
                <span className="text-xs text-sub truncate select-text">
                  {a.eventData || a.details || "—"}
                </span>
                <span className="mono text-[10px] text-muted text-right">
                  {a.createdAt ? new Date(a.createdAt).toLocaleTimeString("en-NG") : "—"}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Anomaly Alerts — V2 real-time detection */}
      {anomalies.length > 0 && (
        <div className="c-card p-6 border border-red-500/20 bg-red-500/5 animate-fade-up"
             style={{ animationDelay: "360ms" }}>
          <div className="flex items-center gap-3 mb-4">
            <div className="w-8 h-8 rounded-xl bg-red-500/15 border border-red-500/25 flex items-center justify-center flex-shrink-0">
              <Ic n="warning" s={16} c="#F87171" />
            </div>
            <div>
              <h2 className="text-sm font-extrabold text-danger">Anomaly Alerts</h2>
              <p className="text-[11px] text-muted">Suspicious voting patterns detected by the server</p>
            </div>
            <span className="ml-auto badge badge-red text-[10px]">{anomalies.length} active</span>
          </div>
          <div className="flex flex-col gap-2">
            {anomalies.slice(0, 5).map((a, i) => (
              <div key={i}
                className="flex items-start gap-3 px-4 py-3 rounded-xl bg-red-500/8 border border-red-500/15">
                <Ic n="shield" s={13} c="#F87171" className="mt-0.5 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="mono text-[10px] font-bold text-danger uppercase tracking-wide">
                      {a.type?.replace(/_/g, " ")}
                    </span>
                    <span className="mono text-[10px] text-muted">
                      {a.terminalId}
                    </span>
                  </div>
                  <p className="text-[11px] text-sub mt-0.5 leading-relaxed">{a.detail}</p>
                  <p className="mono text-[10px] text-muted mt-1">
                    {a.timestamp ? new Date(a.timestamp).toLocaleTimeString("en-NG") : ""}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
      }  );

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* ── Signing key setup banner (SUPER_ADMIN, dismissible per session) ── */}
      {showKeypairBanner && (
        <div className="relative flex items-start gap-4 p-5 rounded-2xl border
                        border-purple-500/25 bg-purple-500/6 animate-fade-up">
          {/* Icon */}
          <div className="w-10 h-10 rounded-xl bg-purple-500/15 border border-purple-500/25
                          flex items-center justify-center flex-shrink-0 mt-0.5">
            <Ic n="shield" s={18} c="#A78BFA" />
          </div>
          {/* Text */}
          <div className="flex-1 min-w-0">
            <div className="text-sm font-bold text-purple-300">Set up your signing key</div>
            <div className="text-xs text-sub mt-1 leading-relaxed max-w-lg">
              Sensitive actions — importing candidates, queuing enrollment, creating elections —
              require your cryptographic signing key. It takes under a minute to set up and
              stays in this browser.
            </div>
            <button
              onClick={() =>
                window.dispatchEvent(new CustomEvent("evoting:navigate",
                  { detail: { view: "settings", tab: "security" } }))
              }
              className="mt-3 btn btn-sm gap-2 bg-purple-500/20 border border-purple-500/40
                         text-purple-300 hover:bg-purple-500/30 rounded-xl
                         text-xs font-semibold transition-colors">
              <Ic n="shield" s={13} c="#A78BFA" /> Set Up Key in Settings
            </button>
          </div>
          {/* Dismiss */}
          <button
            onClick={dismissBanner}
            className="absolute top-3 right-3 text-muted hover:text-sub
                       transition-colors p-1 rounded-lg hover:bg-white/5"
            title="Dismiss for this session">
            <Ic n="close" s={14} />
          </button>
        </div>
      )}


      {/* Stat cards — live from /admin/stats/overview */}
      <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
       <StatCard label="Registered Voters" value={formatVotes(regVoters)}
           sub={`${turnout}% turnout`} icon="voters" accent="purple" delay={0} />
         <StatCard label="Votes Cast" value={formatVotes(votesCast)}
           sub={votesCast > 0 ? "Verified on-chain" : "No votes yet"} icon="vote" accent="green" delay={60} />
        <StatCard label="Active Elections" value={active}
          sub={active > 0 ? "Running now" : "None active"} icon="ballot" accent="amber" delay={120} />
        <StatCard label="Online Terminals" value={(stats?.onlineTerminals ?? 0).toLocaleString()}
          sub={stats?.totalTerminals > 0
            ? `${((stats.onlineTerminals / stats.totalTerminals) * 100).toFixed(0)}% heartbeat OK`
            : "No terminals yet"}
          icon="chip" accent="blue" delay={180} />
      </div>

      <div className="grid grid-cols-1 xl:grid-cols-[1fr_300px] gap-5">

        {/* Candidate standings — real data from /api/results/{id} */}
        <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "200ms" }}>
          <SectionHeader title="Candidate Standings" sub="Live aggregated results from the ballot chain" />
          {candidates.length === 0 ? (
            <EmptyState icon="tally" title="No results yet"
              sub="Results appear here once an election is active and votes are cast" />
          ) : (
            <div className="flex flex-col gap-4 mt-2">
              {[...candidates].sort((a, b) => (b.votes || 0) - (a.votes || 0)).map((c, i) => {
                const pct = totalVotes > 0 ? ((c.votes || 0) / totalVotes * 100).toFixed(1) : "0.0";
                return (
                  <div key={c.id || i}>
                    <div className="flex items-center justify-between mb-2">
                      <div className="flex items-center gap-2.5">
                        {i === 0 && <span className="text-base">🏆</span>}
                        <span className="text-sm font-bold text-ink">{c.fullName || c.name || "—"}</span>
                        <span className="badge badge-purple">{c.party}</span>
                      </div>
                      <div className="flex items-center gap-2.5">
                       {/* Replace the hardcoded 1e6 math with formatVotes */}
                         <span className="mono text-[15px] font-bold" style={{ color: c.color }}>
                           {(c.votes || 0) > 0 ? formatVotes(c.votes) : "0"}
                         </span>
                        <span className="mono text-xs text-sub w-10 text-right">{pct}%</span>
                      </div>
                    </div>
                    <Pbar pct={parseFloat(pct)} color={c.color} />
                  </div>
                );
              })}
            </div>
          )}
        </div>

        {/* State pie chart — real data from /api/results/{id}/by-state */}
        <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "250ms" }}>
          <SectionHeader
            title={regionType === "STATE" ? "Votes by State"
                 : regionType === "LGA"   ? "Votes by LGA"
                                          : "Votes by Ward"}
            sub={regionType === "STATE" ? "Top contributing states"
               : regionType === "LGA"   ? "Top contributing LGAs"
                                        : "Top contributing polling units"}
          />
          {stateTally.length === 0 ? (
            <div className="flex items-center justify-center h-32 text-xs text-muted">
              No state data yet
            </div>
          ) : (
            <>
              <ResponsiveContainer width="100%" height={140}>
                <PieChart>
                  <Pie data={stateTally} cx="50%" cy="50%"
                    innerRadius={38} outerRadius={58}
                    dataKey="totalVotes" nameKey="regionName"
                    paddingAngle={3} startAngle={90} endAngle={-270}>
                    {stateTally.map((_, i) => (
                      <Cell key={i} fill={PIE_COLORS[i % PIE_COLORS.length]} stroke="none" />
                    ))}
                  </Pie>
                  <Tooltip content={<ChartTooltip />} />
                </PieChart>
              </ResponsiveContainer>
              <div className="flex flex-col gap-1.5 mt-2">
                {stateTally.slice(0, 5).map((s, i) => (
                  <div key={s.regionName || s.stateName || i} className="flex items-center gap-2.5">
                    <div className="w-2 h-2 rounded-[3px] flex-shrink-0"
                      style={{ background: PIE_COLORS[i % PIE_COLORS.length] }} />
                    <span className="text-xs font-medium text-sub flex-1 truncate">
                      {s.regionName || s.stateName}
                    </span>
                    <span className="mono text-xs text-ink">
                      {(s.totalVotes || 0) >= 1_000_000
                        ? `${((s.totalVotes || 0) / 1e6).toFixed(2)}M`
                        : (s.totalVotes || 0) >= 1_000
                          ? `${((s.totalVotes || 0) / 1e3).toFixed(1)}K`
                          : (s.totalVotes || 0).toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      </div>

      {/* Recent audit events — live from /admin/audit-log */}
      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "320ms" }}>
        <SectionHeader title="Recent Events" sub="Latest audit log entries" />
        {auditLogs.length === 0 ? (
          <div className="py-6 text-center text-xs text-muted">No events recorded yet.</div>
        ) : (
          <div className="flex flex-col mt-2">
            {auditLogs.slice(0, 6).map((a, i) => (
              <div key={a.id || a.sequenceNumber || i}
                className="trow hover:bg-white/5 transition-colors rounded-lg
                           border-b border-border/50 last:border-0"
                style={{ gridTemplateColumns: "56px 180px 120px 1fr 90px", gap: "12px" }}>
                <span className="mono text-[10px] text-muted">
                  #{a.sequenceNumber || i + 1}
                </span>
                <span className={`mono text-[10px] font-bold truncate ${typeColor(a.eventType)}`}>
                  {a.eventType}
                </span>
                <span className="mono text-[10px] text-sub truncate">
                  {a.actor || "SYSTEM"}
                </span>
                <span className="text-xs text-sub truncate select-text">
                  {a.eventData || a.details || "—"}
                </span>
                <span className="mono text-[10px] text-muted text-right">
                  {a.createdAt ? new Date(a.createdAt).toLocaleTimeString("en-NG") : "—"}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* Anomaly Alerts — V2 real-time detection */}
      {anomalies.length > 0 && (
        <div className="c-card p-6 border border-red-500/20 bg-red-500/5 animate-fade-up"
             style={{ animationDelay: "360ms" }}>
          <div className="flex items-center gap-3 mb-4">
            <div className="w-8 h-8 rounded-xl bg-red-500/15 border border-red-500/25 flex items-center justify-center flex-shrink-0">
              <Ic n="warning" s={16} c="#F87171" />
            </div>
            <div>
              <h2 className="text-sm font-extrabold text-danger">Anomaly Alerts</h2>
              <p className="text-[11px] text-muted">Suspicious voting patterns detected by the server</p>
            </div>
            <span className="ml-auto badge badge-red text-[10px]">{anomalies.length} active</span>
          </div>
          <div className="flex flex-col gap-2">
            {anomalies.slice(0, 5).map((a, i) => (
              <div key={i}
                className="flex items-start gap-3 px-4 py-3 rounded-xl bg-red-500/8 border border-red-500/15">
                <Ic n="shield" s={13} c="#F87171" className="mt-0.5 flex-shrink-0" />
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="mono text-[10px] font-bold text-danger uppercase tracking-wide">
                      {a.type?.replace(/_/g, " ")}
                    </span>
                    <span className="mono text-[10px] text-muted">
                      {a.terminalId}
                    </span>
                  </div>
                  <p className="text-[11px] text-sub mt-0.5 leading-relaxed">{a.detail}</p>
                  <p className="mono text-[10px] text-muted mt-1">
                    {a.timestamp ? new Date(a.timestamp).toLocaleTimeString("en-NG") : ""}
                  </p>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
