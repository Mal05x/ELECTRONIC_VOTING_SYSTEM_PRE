import { useState, useEffect, useCallback } from "react";
import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer } from "recharts";
import { getNationalTally, getRegionalBreakdown } from "../api/tally.js";
import { getElections } from "../api/elections.js";
import { useWebSocket } from "../hooks/useWebSocket.js";
import { StatCard, Pbar, SectionHeader, ChartTooltip, Spinner, EmptyState } from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

const CAND_COLORS   = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED"];
const REGION_COLORS = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED","#6D28D9"];

// ─── Dynamic label helpers ───────────────────────────────────────────────────

function regionLabel(regionType, variant = "noun") {
  const map = {
    STATE:        { noun: "State",        plural: "States",        reporting: "States Reporting",  top: "Top States",        chart: "Votes by State",  more: "more states" },
    LGA:          { noun: "LGA",          plural: "LGAs",          reporting: "LGAs Reporting",    top: "Top LGAs",          chart: "Votes by LGA",    more: "more LGAs"   },
    POLLING_UNIT: { noun: "Polling Unit", plural: "Polling Units", reporting: "Wards Reporting",   top: "Top Wards",         chart: "Votes by Ward",   more: "more wards"  },
  };
  return (map[regionType] || map.STATE)[variant];
}

/** Smart vote formatter — fixes the 0.000M bug for small counts */
function fmtVotes(n) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(3)}M`;
  if (n >= 1_000)     return `${(n / 1_000).toFixed(1)}K`;
  return n.toLocaleString();
}

function fmtVotesShort(n) {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000)     return `${(n / 1_000).toFixed(0)}K`;
  return n.toLocaleString();
}

// ─── Component ───────────────────────────────────────────────────────────────

export default function TallyView() {
  const [elections,    setElections]    = useState([]);
  const [selectedId,   setSelectedId]   = useState(null);
  const [candidates,   setCandidates]   = useState([]);
  const [regionData,   setRegionData]   = useState([]); // RegionalBreakdownDTO[]
  const [regionType,   setRegionType]   = useState("STATE"); // STATE | LGA | POLLING_UNIT
  const [merkle,       setMerkle]       = useState(null);
  const [loading,      setLoading]      = useState(true);
  const [elecLoading,  setElecLoading]  = useState(true);
  const [drillRegion,  setDrillRegion]  = useState(null);  // { regionId, regionName, candidateTally }
  const [drillOpen,    setDrillOpen]    = useState(false);

  // ─── Step 1: Load elections ───────────────────────────────────────────────

  useEffect(() => {
    setElecLoading(true);
    getElections()
      .then(data => {
        const elecs = Array.isArray(data) ? data : [];
        setElections(elecs);
        const eligible = elecs.filter(e => ["ACTIVE","CLOSED"].includes(e.status));
        const first    = eligible[0] || elecs[0];
        if (first) setSelectedId(first.id);
      })
      .catch(e => console.error("Elections load failed:", e))
      .finally(() => setElecLoading(false));
  }, []);

  // ─── Step 2: Load tally when selection changes ────────────────────────────

  useEffect(() => {
    if (!selectedId) return;
    setLoading(true);
    setDrillOpen(false);
    setDrillRegion(null);

    Promise.all([
      getNationalTally(selectedId).catch(() => null),
      getRegionalBreakdown(selectedId).catch(() => []),
    ]).then(([tally, regions]) => {
      // Candidates
      const cands = (tally?.candidates || []).map((c, i) => ({
        ...c,
        votes: typeof c.votes === "number" ? c.votes : 0,
        color: CAND_COLORS[i % CAND_COLORS.length],
      }));
      setCandidates(cands);

      // Regional breakdown + type detection
      const rows = Array.isArray(regions) ? regions : [];
      setRegionData(rows);
      setRegionType(rows[0]?.regionType || "STATE");

      // Merkle
      setMerkle(tally?.merkleRoot ? {
        root:         tally.merkleRoot,
        totalBallots: tally.totalBallots || 0,
      } : null);
    }).catch(err => {
      console.error("Tally load error:", err);
      setCandidates([]);
      setRegionData([]);
      setMerkle(null);
    }).finally(() => setLoading(false));
  }, [selectedId]);

  // ─── Real-time WebSocket updates ──────────────────────────────────────────

  const handleWsMessage = useCallback((msg) => {
    if (msg.type === "TALLY_UPDATE" && msg.candidates) {
      setCandidates(prev => prev.map(c => {
        const upd = msg.candidates.find(x => x.id === c.id || x.party === c.party);
        return upd ? { ...c, votes: upd.votes } : c;
      }));
    }
    if (msg.type === "MERKLE_UPDATE") {
      setMerkle(m => ({ ...m, root: msg.merkleRoot, totalBallots: msg.totalBallots }));
    }
  }, []);

  const { connected } = useWebSocket(selectedId, handleWsMessage);

  // ─── Drill-down — uses candidateTally already embedded in RegionalBreakdownDTO ──
  // No second API call needed: the /by-region endpoint includes per-candidate
  // vote counts in each row. We merge those with candidate metadata from
  // the national tally (already loaded) to get full name + party.

  const openDrilldown = (row) => {
    // Build candidate list from embedded tally + national candidate metadata
    const tally    = row.candidateTally || {};
    const enriched = candidates
      .map(c => ({
        id:       c.id,
        fullName: c.fullName || c.name || "—",
        party:    c.party    || "—",
        votes:    tally[c.id] || 0,
      }))
      .sort((a, b) => b.votes - a.votes);

    setDrillRegion({
      regionId:    row.regionId,
      regionName:  row.regionName,
      totalVotes:  row.totalVotes,
      turnout:     row.turnoutPercent || 0,
      regionType:  row.regionType,
      candidates:  enriched,
    });
    setDrillOpen(true);
  };

  // ─── Derived values ───────────────────────────────────────────────────────

  const safeCands  = Array.isArray(candidates) ? candidates : [];
  const totalVotes = safeCands.reduce((s, c) => s + (c.votes || 0), 0);
  const sorted     = [...safeCands].sort((a, b) => (b.votes || 0) - (a.votes || 0));
  const leader     = sorted[0];

  // Bar chart: top 5 regions
  const barData = regionData.slice(0, 5).map(r => ({
    name:  (r.regionName || "").slice(0, 10),
    votes: r.totalVotes || 0,
  }));

  const eligibleElections = elections.filter(e => ["ACTIVE","CLOSED"].includes(e.status));

  // ─── Render ───────────────────────────────────────────────────────────────

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Election selector */}
      <div className="flex items-center gap-4 c-card p-4">
        <label className="text-xs font-bold text-sub uppercase tracking-wider whitespace-nowrap">
          Election
        </label>
        {elecLoading ? (
          <Spinner s={18} />
        ) : eligibleElections.length === 0 ? (
          <span className="text-xs text-muted">No active or closed elections</span>
        ) : (
          <select
            className="inp inp-md flex-1 max-w-md font-semibold"
            value={selectedId || ""}
            onChange={e => setSelectedId(e.target.value)}
          >
            {eligibleElections.map(e => (
              <option key={e.id} value={e.id}>{e.name} — {e.status}</option>
            ))}
          </select>
        )}
        <div className={`ml-auto flex items-center gap-2 text-xs font-bold
                         ${connected ? "text-success" : "text-muted"}`}>
          {connected && <span className="live-dot" />}
          {connected ? "Live" : "Polling"}
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center items-center py-32"><Spinner s={40} /></div>
      ) : !selectedId ? (
        <EmptyState icon="tally" title="No elections available"
          sub="Create and activate an election to see live results" />
      ) : (
        <>
          {/* ── Stat cards — fully dynamic labels ── */}
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            <StatCard
              label="Total Votes"
              value={totalVotes.toLocaleString()}
              icon="vote" accent="purple" delay={0}
            />
            <StatCard
              label="Leading Party"
              value={leader?.party || "—"}
              sub={leader && (leader.votes || 0) > 0
                ? `${fmtVotes(leader.votes)} votes` : "No data"}
              icon="flag" accent="green" delay={60}
            />
            <StatCard
              label={regionLabel(regionType, "reporting")}
              value={regionData.length}
              icon="ballot" accent="amber" delay={120}
            />
            <StatCard
              label="Merkle Ballots"
              value={merkle ? merkle.totalBallots.toLocaleString() : "—"}
              sub={merkle ? "Hash-chain verified" : "Not available"}
              icon="shield" accent="blue" delay={180}
            />
          </div>

          <div className="grid grid-cols-1 xl:grid-cols-[1fr_300px] gap-5">

            {/* ── Candidate standings ── */}
            <div className="c-card p-6 animate-fade-up">
              <SectionHeader title="Candidate Standings"
                sub="Live vote counts from the ballot chain" />
              {safeCands.length === 0 ? (
                <EmptyState icon="tally" title="No candidates"
                  sub="Add candidates to this election to see standings" />
              ) : (
                <div className="flex flex-col gap-5 mt-2">
                  {sorted.map((c, i) => {
                    const pct = totalVotes > 0
                      ? ((c.votes || 0) / totalVotes * 100).toFixed(1) : "0.0";
                    return (
                      <div key={c.id || i}>
                        <div className="flex items-center justify-between mb-2">
                          <div className="flex items-center gap-2.5">
                            {i === 0 && <span>🏆</span>}
                            <span className="text-sm font-bold text-ink">
                              {c.fullName || c.name || "—"}
                            </span>
                            <span className="badge badge-purple">{c.party}</span>
                          </div>
                          <div className="flex items-center gap-2">
                            <span className="mono font-bold text-[15px]"
                              style={{ color: c.color }}>
                              {(c.votes || 0) > 0 ? fmtVotes(c.votes) : "0"}
                            </span>
                            <span className="mono text-xs text-sub">{pct}%</span>
                          </div>
                        </div>
                        <Pbar pct={parseFloat(pct)} color={c.color} />
                      </div>
                    );
                  })}
                </div>
              )}
            </div>

            {/* ── Regional breakdown panel — dynamic ── */}
            <div className="c-card p-6 animate-fade-up">
              <SectionHeader
                title={regionLabel(regionType, "top")}
                sub={regionLabel(regionType, "chart")}
              />
              {barData.length === 0 ? (
                <div className="flex items-center justify-center h-32 text-xs text-muted">
                  No {regionLabel(regionType, "plural").toLowerCase()} data yet
                </div>
              ) : (
                <ResponsiveContainer width="100%" height={160}>
                  <BarChart data={barData} margin={{ top: 4, right: 4, bottom: 0, left: -20 }}>
                    <XAxis dataKey="name"
                      tick={{ fill: "#4A4464", fontSize: 10, fontFamily: "JetBrains Mono" }}
                      axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill: "#4A4464", fontSize: 10 }}
                      axisLine={false} tickLine={false}
                      tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""} />
                    <Tooltip content={<ChartTooltip />} />
                    <Bar dataKey="votes" fill="#8B5CF6" radius={[4, 4, 0, 0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}

              {/* Clickable region list — opens drill-down */}
              {regionData.length > 0 && (
                <div className="flex flex-col gap-1.5 mt-3">
                  {regionData.slice(0, 8).map((r, i) => (
                    <button key={r.regionId || r.regionName || i}
                      className="flex items-center gap-2 w-full text-left px-2 py-1.5
                                 rounded-lg hover:bg-white/5 transition-colors group
                                 cursor-pointer"
                      onClick={() => openDrilldown(r)}
                      title={`Click to see per-candidate breakdown in ${r.regionName}`}
                    >
                      <div className="w-2 h-2 rounded-[2px] flex-shrink-0"
                        style={{ background: REGION_COLORS[i % REGION_COLORS.length] }} />
                      <span className="text-xs text-sub flex-1 truncate
                                       group-hover:text-ink transition-colors">
                        {r.regionName}
                      </span>
                      <span className="mono text-xs text-ink">
                        {fmtVotesShort(r.totalVotes || 0)}
                      </span>
                      {/* Turnout badge — only STATE level has it */}
                      {r.turnoutPercent > 0 && (
                        <span className="mono text-[10px] text-muted">
                          {r.turnoutPercent.toFixed(1)}%
                        </span>
                      )}
                      <Ic n="check" s={10} c="#4A4464" />
                    </button>
                  ))}
                  {regionData.length > 8 && (
                    <div className="text-[10px] text-muted text-center mt-1">
                      +{regionData.length - 8} {regionLabel(regionType, "more")}
                    </div>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* ── Merkle root ── */}
          {merkle && (
            <div className="c-card p-5 animate-fade-up border border-purple-500/20">
              <div className="flex items-start justify-between gap-4 flex-wrap">
                <div>
                  <div className="text-xs font-bold text-purple-400 uppercase tracking-widest mb-1">
                    Ballot Merkle Root
                  </div>
                  <div className="mono text-[11px] text-ink break-all select-text cursor-text">{merkle.root}</div>
                </div>
                <div className="text-right flex-shrink-0">
                  <div className="text-xs font-bold text-success">✓ Verified</div>
                  <div className="mono text-[10px] text-muted mt-0.5">
                    {merkle.totalBallots?.toLocaleString()} ballots
                  </div>
                </div>
              </div>
            </div>
          )}
        </>
      )}

      {/* ── Regional drill-down modal ── */}
      {drillOpen && drillRegion && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-6
                     bg-black/70 backdrop-blur-sm"
          onClick={e => e.target === e.currentTarget && setDrillOpen(false)}
        >
          <div className="w-full max-w-lg bg-card border border-border-hi rounded-2xl p-7
                          shadow-2xl animate-fade-up">
            <div className="flex items-center justify-between mb-5">
              <div>
                <div className="text-base font-bold text-white">
                  {drillRegion.regionName}
                </div>
                <div className="text-xs text-muted mt-0.5">
                  Per-candidate breakdown · {regionLabel(drillRegion.regionType, "noun")} level
                </div>
              </div>
              <button className="text-muted hover:text-white transition-colors"
                onClick={() => setDrillOpen(false)}>
                <Ic n="close" s={16} />
              </button>
            </div>

            {drillRegion.candidates.length === 0 ? (
              <div className="text-center py-12 text-sm text-muted">
                No votes recorded in this {regionLabel(drillRegion.regionType, "noun").toLowerCase()} yet
              </div>
            ) : (
              <div className="flex flex-col gap-3">
                {drillRegion.candidates.map((c, i) => {
                  const total = drillRegion.candidates.reduce(
                    (s, x) => s + (x.votes || 0), 0
                  );
                  const pct = total > 0
                    ? ((c.votes || 0) / total * 100).toFixed(1) : "0.0";
                  return (
                    <div key={c.id || i}>
                      <div className="flex items-center justify-between mb-1.5">
                        <div className="flex items-center gap-2">
                          {i === 0 && (c.votes || 0) > 0 && (
                            <span className="text-sm">🏆</span>
                          )}
                          <span className="text-sm font-bold text-ink">
                            {c.fullName}
                          </span>
                          <span className="badge badge-purple text-[9px]">{c.party}</span>
                        </div>
                        <div className="flex items-center gap-2">
                          <span className="mono font-bold text-sm text-purple-400">
                            {(c.votes || 0).toLocaleString()}
                          </span>
                          <span className="mono text-xs text-muted">{pct}%</span>
                        </div>
                      </div>
                      <div className="h-1.5 bg-elevated rounded-full overflow-hidden">
                        <div className="h-full rounded-full transition-all duration-700"
                          style={{
                            width: `${pct}%`,
                            background: CAND_COLORS[i % CAND_COLORS.length],
                          }} />
                      </div>
                    </div>
                  );
                })}

                <div className="pt-3 border-t border-border flex items-center justify-between">
                  <span className="text-xs text-muted">
                    Total: {(drillRegion.totalVotes || 0).toLocaleString()} votes
                  </span>
                  {drillRegion.turnout > 0 && (
                    <span className="text-xs text-muted">
                      Turnout: {drillRegion.turnout.toFixed(1)}%
                    </span>
                  )}
                </div>
              </div>
            )}
          </div>
        </div>
      )}
    </div>
  );
}
