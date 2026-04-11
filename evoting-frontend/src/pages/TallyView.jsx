import { useState, useEffect, useCallback, useRef } from "react";
import {
  BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer,
  Cell, LabelList,
} from "recharts";
import { getNationalTally, getRegionalBreakdown } from "../api/tally.js";
import { getElections } from "../api/elections.js";
import { useWebSocket } from "../hooks/useWebSocket.js";
import { StatCard, Pbar, SectionHeader, ChartTooltip, Spinner, EmptyState } from "../components/ui.jsx";
import { Ic } from "../components/ui.jsx";

const CAND_COLORS   = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED","#6D28D9","#5B21B6"];
const REGION_COLORS = ["#8B5CF6","#A78BFA","#DDD6FE","#7C3AED","#6D28D9"];

// ─── Helpers ──────────────────────────────────────────────────────────────────

function regionLabel(regionType, variant = "noun") {
  const map = {
    STATE:        { noun:"State",        plural:"States",        reporting:"States Reporting",  top:"Top States",        chart:"Votes by State",  more:"more states" },
    LGA:          { noun:"LGA",          plural:"LGAs",          reporting:"LGAs Reporting",    top:"Top LGAs",          chart:"Votes by LGA",    more:"more LGAs"   },
    POLLING_UNIT: { noun:"Polling Unit", plural:"Polling Units", reporting:"Wards Reporting",   top:"Top Wards",         chart:"Votes by Ward",   more:"more wards"  },
  };
  return (map[regionType] || map.STATE)[variant];
}

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

// ─── Animated Face Bar Chart ──────────────────────────────────────────────────
/**
 * Custom recharts bar with candidate face clipped to a circle at the top.
 * When no photo is available, falls back to a party initial badge.
 *
 * Why custom shape: recharts Bar only supports rectangle shapes natively.
 * We render the bar rect ourselves and add a <foreignObject> (or <image>)
 * at the top for the photo. SVG <image> is used for URLs; <text> for initials.
 */
function FaceBar(props) {
  const { x, y, width, height, color, imageUrl, party, animated, fill } = props;
  if (!width || !height || height <= 0) return null;

  const barColor  = color || fill || "#8B5CF6";
  const faceSize  = Math.min(width * 0.85, 48);
  const faceR     = faceSize / 2;
  const faceCx    = x + width / 2;
  const faceCy    = y - faceR - 4;  // sits just above the bar

  return (
    <g>
      {/* Bar rectangle */}
      <rect
        x={x} y={y} width={width} height={height}
        fill={barColor}
        rx={4} ry={4}
        opacity={0.9}
      />

      {/* Face circle */}
      <clipPath id={`clip-${x}-${y}`}>
        <circle cx={faceCx} cy={faceCy} r={faceR} />
      </clipPath>

      {imageUrl ? (
        <>
          {/* Photo */}
          <image
            href={imageUrl}
            x={faceCx - faceR}
            y={faceCy - faceR}
            width={faceSize}
            height={faceSize}
            clipPath={`url(#clip-${x}-${y})`}
            preserveAspectRatio="xMidYMid slice"
          />
          {/* Border ring */}
          <circle cx={faceCx} cy={faceCy} r={faceR}
            fill="none" stroke={barColor} strokeWidth={2} />
        </>
      ) : (
        <>
          {/* Fallback: initial badge */}
          <circle cx={faceCx} cy={faceCy} r={faceR}
            fill={barColor} stroke="#1E1B4B" strokeWidth={2} opacity={0.85} />
          <text
            x={faceCx} y={faceCy + 1}
            textAnchor="middle" dominantBaseline="middle"
            fontSize={faceR * 0.9} fontWeight="bold" fill="#fff" fontFamily="Arial">
            {(party || "?")[0]}
          </text>
        </>
      )}
    </g>
  );
}

// ─── Component ────────────────────────────────────────────────────────────────

export default function TallyView() {
  const [elections,    setElections]    = useState([]);
  const [selectedId,   setSelectedId]   = useState(null);
  const [candidates,   setCandidates]   = useState([]);
  const [regionData,   setRegionData]   = useState([]);
  const [regionType,   setRegionType]   = useState("STATE");
  const [merkle,       setMerkle]       = useState(null);
  const [loading,      setLoading]      = useState(true);
  const [elecLoading,  setElecLoading]  = useState(true);
  const [drillRegion,  setDrillRegion]  = useState(null);
  const [drillOpen,    setDrillOpen]    = useState(false);
  const [chartView,    setChartView]    = useState("face"); // "face" | "bar"

  // ─── Load elections ────────────────────────────────────────────────────────
  useEffect(() => {
    setElecLoading(true);
    getElections()
      .then(data => {
        const elecs    = Array.isArray(data) ? data : [];
        setElections(elecs);
        const eligible = elecs.filter(e => ["ACTIVE","CLOSED"].includes(e.status));
        const first    = eligible[0] || elecs[0];
        if (first) setSelectedId(first.id);
      })
      .catch(() => {})
      .finally(() => setElecLoading(false));
  }, []);

  // ─── Load tally + regional data when election changes ──────────────────────
  const loadTally = useCallback(async (id) => {
    if (!id) return;
    setLoading(true);
    try {
      const [tally, regions] = await Promise.all([
        getNationalTally(id),
        getRegionalBreakdown(id).catch(() => []),
      ]);

      const cands = (tally?.candidates || []).map((c, i) => ({
        ...c,
        id:       c.id       || c.candidateId || String(i),
        votes:    c.votes    || c.voteCount   || 0,
        fullName: c.fullName || c.name        || "Unknown",
        color:    CAND_COLORS[i % CAND_COLORS.length],
        imageUrl: c.imageUrl || null,
      }));
      setCandidates(cands);
      setRegionData(Array.isArray(regions) ? regions : []);
      setRegionType(regions?.[0]?.regionType || "STATE");
      setMerkle(tally?.merkleRoot || null);
    } catch (e) {
      setCandidates([]); setRegionData([]);
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { if (selectedId) loadTally(selectedId); }, [selectedId, loadTally]);

  // ─── WebSocket live updates ────────────────────────────────────────────────
  const { lastMessage } = useWebSocket();
  useEffect(() => {
    if (!lastMessage) return;
    try {
      const msg = typeof lastMessage === "string" ? JSON.parse(lastMessage) : lastMessage;
      if (msg.type === "TALLY_UPDATE" && msg.candidates) {
        setCandidates(prev => prev.map(c => {
          const upd = msg.candidates.find(x => x.id === c.id || x.party === c.party);
          return upd ? { ...c, votes: upd.votes || upd.voteCount || c.votes } : c;
        }));
      }
    } catch (_) {}
  }, [lastMessage]);

  // ─── Drill-down ───────────────────────────────────────────────────────────
  const openDrilldown = useCallback((row) => {
    const tally    = row.candidateTally || {};
    const enriched = candidates
      .map(c => ({ ...c, votes: tally[c.id] || tally[c.candidateId] || 0 }))
      .sort((a, b) => b.votes - a.votes);
    setDrillRegion({ ...row, candidates: enriched });
    setDrillOpen(true);
  }, [candidates]);

  // ─── Derived values ───────────────────────────────────────────────────────
  const safeCands  = Array.isArray(candidates) ? candidates : [];
  const sorted     = [...safeCands].sort((a, b) => (b.votes||0) - (a.votes||0));
  const totalVotes = safeCands.reduce((s, c) => s + (c.votes || 0), 0);
  const barData    = regionData
    .map(r => ({ name: r.regionName?.split(" ")[0] || "—", votes: r.totalVotes || 0 }))
    .sort((a, b) => b.votes - a.votes)
    .slice(0, 6);

  // Face chart data — add extra top padding for face circles
  const faceChartData = sorted.map(c => ({
    name:     c.party || c.fullName?.split(" ").pop() || "?",
    votes:    c.votes || 0,
    color:    c.color,
    imageUrl: c.imageUrl,
    party:    c.party,
  }));

  const selectedElec = elections.find(e => e.id === selectedId);

  // ─── Render ────────────────────────────────────────────────────────────────
  if (elecLoading) return (
    <div className="p-7 flex items-center justify-center h-64">
      <Spinner s={32} />
    </div>
  );

  if (elections.length === 0) return (
    <div className="p-7">
      <EmptyState icon="tally" title="No elections"
        sub="Create an election first, then results will appear here." />
    </div>
  );

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Election selector */}
      <div className="flex items-center gap-3 flex-wrap">
        <SectionHeader title="Live Tally" sub="Real-time vote counts — WebSocket-driven" />
        <div className="flex gap-2 flex-wrap ml-auto">
          {elections
            .filter(e => ["ACTIVE","CLOSED"].includes(e.status))
            .map(e => (
              <button key={e.id}
                className={`px-4 py-2 rounded-xl text-xs font-bold border transition-all
                  ${selectedId === e.id
                    ? "bg-purple-500/20 border-purple-500/40 text-purple-300"
                    : "bg-elevated border-border text-sub hover:border-purple-500/20"}`}
                onClick={() => setSelectedId(e.id)}>
                {e.name}
                <span className={`ml-2 text-[9px] px-1.5 py-0.5 rounded-full
                  ${e.status === "ACTIVE" ? "bg-green-500/20 text-green-400" : "bg-slate-500/20 text-slate-400"}`}>
                  {e.status}
                </span>
              </button>
            ))}
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center py-20"><Spinner s={32} /></div>
      ) : (
        <>
          {/* Stats row */}
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            <StatCard label="Total Votes"   value={fmtVotes(totalVotes)}           icon="voters"   accent="purple" delay={0} />
            <StatCard label="Candidates"    value={safeCands.length}               icon="voters"   accent="blue"   delay={50} />
            <StatCard label="Leading Party" value={sorted[0]?.party || "—"}        icon="shield"   accent="green"  delay={100} />
            <StatCard label="Merkle Root"   value={merkle ? "Anchored" : "Pending"} icon="database" accent={merkle ? "green" : "amber"} delay={150} />
          </div>

          {/* Main panels */}
          <div className="grid grid-cols-1 xl:grid-cols-[1fr_300px] gap-5">

            {/* ── Candidate standings with animated face chart ── */}
            <div className="c-card p-6 animate-fade-up">
              <div className="flex items-center justify-between mb-4">
                <SectionHeader title="Candidate Standings"
                  sub="Live vote counts — updates via WebSocket" />
                {/* Toggle chart view */}
                <div className="flex gap-1 bg-elevated rounded-xl p-1 border border-border">
                  <button
                    className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-all
                      ${chartView === "face" ? "bg-purple-500/20 text-purple-300" : "text-sub hover:text-ink"}`}
                    onClick={() => setChartView("face")}>
                    <Ic n="voters" s={11} className="mr-1" /> Faces
                  </button>
                  <button
                    className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-all
                      ${chartView === "bar" ? "bg-purple-500/20 text-purple-300" : "text-sub hover:text-ink"}`}
                    onClick={() => setChartView("bar")}>
                    <Ic n="trend" s={11} className="mr-1" /> Bars
                  </button>
                </div>
              </div>

              {safeCands.length === 0 ? (
                <EmptyState icon="tally" title="No candidates"
                  sub="Add candidates to this election to see standings" />
              ) : (
                <>
                  {/* ── Face chart ── */}
                  {chartView === "face" && (
                    <div className="mb-6">
                      <ResponsiveContainer width="100%" height={260}>
                        <BarChart
                          data={faceChartData}
                          margin={{ top: 56, right: 16, bottom: 8, left: 0 }}
                          barCategoryGap="25%">
                          <XAxis
                            dataKey="name"
                            tick={{ fill:"#6B7280", fontSize:11, fontFamily:"Arial" }}
                            axisLine={false} tickLine={false}
                          />
                          <YAxis
                            tick={{ fill:"#6B7280", fontSize:10 }}
                            axisLine={false} tickLine={false}
                            tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""}
                          />
                          <Tooltip
                            formatter={(v, name, props) => [fmtVotes(v), "Votes"]}
                            contentStyle={{ background:"#0F0F1A", border:"1px solid #2D2D4E", borderRadius:8 }}
                            labelStyle={{ color:"#A78BFA", fontWeight:"bold" }}
                            itemStyle={{ color:"#E2E8F0" }}
                          />
                          <Bar
                            dataKey="votes"
                            radius={[6,6,0,0]}
                            isAnimationActive={true}
                            animationDuration={900}
                            animationEasing="ease-out"
                            shape={(props) => {
                              const entry = faceChartData[props.index] || {};
                              return (
                                <FaceBar
                                  {...props}
                                  color={entry.color}
                                  imageUrl={entry.imageUrl}
                                  party={entry.party}
                                />
                              );
                            }}>
                            {faceChartData.map((entry, i) => (
                              <Cell key={i} fill={entry.color} />
                            ))}
                            <LabelList
                              dataKey="votes"
                              position="insideTop"
                              formatter={v => v > 0 ? fmtVotesShort(v) : ""}
                              style={{ fill:"#fff", fontSize:10, fontWeight:"bold" }}
                            />
                          </Bar>
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  )}

                  {/* ── Standard bar chart ── */}
                  {chartView === "bar" && (
                    <div className="mb-6">
                      <ResponsiveContainer width="100%" height={200}>
                        <BarChart data={faceChartData} margin={{ top:4, right:4, bottom:0, left:-20 }}>
                          <XAxis dataKey="name"
                            tick={{ fill:"#4A4464", fontSize:10, fontFamily:"JetBrains Mono" }}
                            axisLine={false} tickLine={false} />
                          <YAxis tick={{ fill:"#4A4464", fontSize:10 }}
                            axisLine={false} tickLine={false}
                            tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""} />
                          <Tooltip content={<ChartTooltip />} />
                          <Bar dataKey="votes" radius={[4,4,0,0]} isAnimationActive animationDuration={700}>
                            {faceChartData.map((e, i) => <Cell key={i} fill={e.color} />)}
                          </Bar>
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  )}

                  {/* Progress bars list */}
                  <div className="flex flex-col gap-5">
                    {sorted.map((c, i) => {
                      const pct = totalVotes > 0
                        ? ((c.votes || 0) / totalVotes * 100).toFixed(1) : "0.0";
                      return (
                        <div key={c.id || i}>
                          <div className="flex items-center justify-between mb-2">
                            <div className="flex items-center gap-2.5">
                              {/* Candidate photo thumbnail */}
                              {c.imageUrl ? (
                                <img
                                  src={c.imageUrl}
                                  alt={c.fullName}
                                  className="w-8 h-8 rounded-full object-cover border-2"
                                  style={{ borderColor: c.color }}
                                  onError={e => { e.target.style.display="none"; }}
                                />
                              ) : (
                                <div
                                  className="w-8 h-8 rounded-full flex items-center justify-center
                                             text-[11px] font-bold text-white flex-shrink-0"
                                  style={{ background: c.color }}>
                                  {(c.party || "?")[0]}
                                </div>
                              )}
                              {i === 0 && <span title="Leading">🏆</span>}
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
                </>
              )}
            </div>

            {/* ── Regional breakdown ── */}
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
                  <BarChart data={barData} margin={{ top:4, right:4, bottom:0, left:-20 }}>
                    <XAxis dataKey="name"
                      tick={{ fill:"#4A4464", fontSize:10, fontFamily:"JetBrains Mono" }}
                      axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill:"#4A4464", fontSize:10 }}
                      axisLine={false} tickLine={false}
                      tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""} />
                    <Tooltip content={<ChartTooltip />} />
                    <Bar dataKey="votes" fill="#8B5CF6" radius={[4,4,0,0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}

              {regionData.length > 0 && (
                <div className="flex flex-col gap-1.5 mt-3">
                  {regionData.slice(0, 8).map((r, i) => (
                    <butto  const [elections,    setElections]    = useState([]);
  const [selectedId,   setSelectedId]   = useState(null);
  const [candidates,   setCandidates]   = useState([]);
  const [regionData,   setRegionData]   = useState([]);
  const [regionType,   setRegionType]   = useState("STATE");
  const [merkle,       setMerkle]       = useState(null);
  const [loading,      setLoading]      = useState(true);
  const [elecLoading,  setElecLoading]  = useState(true);
  const [drillRegion,  setDrillRegion]  = useState(null);
  const [drillOpen,    setDrillOpen]    = useState(false);
  const [chartView,    setChartView]    = useState("face"); // "face" | "bar"

  // ─── Load elections ────────────────────────────────────────────────────────
  useEffect(() => {
    setElecLoading(true);
    getElections()
      .then(data => {
        const elecs    = Array.isArray(data) ? data : [];
        setElections(elecs);
        const eligible = elecs.filter(e => ["ACTIVE","CLOSED"].includes(e.status));
        const first    = eligible[0] || elecs[0];
        if (first) setSelectedId(first.id);
      })
      .catch(() => {})
      .finally(() => setElecLoading(false));
  }, []);

  // ─── Load tally + regional data when election changes ──────────────────────
  const loadTally = useCallback(async (id) => {
    if (!id) return;
    setLoading(true);
    try {
      const [tally, regions] = await Promise.all([
        getNationalTally(id),
        getRegionalBreakdown(id).catch(() => []),
      ]);

      const cands = (tally?.candidates || []).map((c, i) => ({
        ...c,
        id:       c.id       || c.candidateId || String(i),
        votes:    c.votes    || c.voteCount   || 0,
        fullName: c.fullName || c.name        || "Unknown",
        color:    CAND_COLORS[i % CAND_COLORS.length],
        imageUrl: c.imageUrl || null,
      }));
      setCandidates(cands);
      setRegionData(Array.isArray(regions) ? regions : []);
      setRegionType(regions?.[0]?.regionType || "STATE");
      setMerkle(tally?.merkleRoot || null);
    } catch (e) {
      setCandidates([]); setRegionData([]);
    } finally { setLoading(false); }
  }, []);

  useEffect(() => { if (selectedId) loadTally(selectedId); }, [selectedId, loadTally]);

  // ─── WebSocket live updates ────────────────────────────────────────────────
  const { lastMessage } = useWebSocket();
  useEffect(() => {
    if (!lastMessage) return;
    try {
      const msg = typeof lastMessage === "string" ? JSON.parse(lastMessage) : lastMessage;
      if (msg.type === "TALLY_UPDATE" && msg.candidates) {
        setCandidates(prev => prev.map(c => {
          const upd = msg.candidates.find(x => x.id === c.id || x.party === c.party);
          return upd ? { ...c, votes: upd.votes || upd.voteCount || c.votes } : c;
        }));
      }
    } catch (_) {}
  }, [lastMessage]);

  // ─── Drill-down ───────────────────────────────────────────────────────────
  const openDrilldown = useCallback((row) => {
    const tally    = row.candidateTally || {};
    const enriched = candidates
      .map(c => ({ ...c, votes: tally[c.id] || tally[c.candidateId] || 0 }))
      .sort((a, b) => b.votes - a.votes);
    setDrillRegion({ ...row, candidates: enriched });
    setDrillOpen(true);
  }, [candidates]);

  // ─── Derived values ───────────────────────────────────────────────────────
  const safeCands  = Array.isArray(candidates) ? candidates : [];
  const sorted     = [...safeCands].sort((a, b) => (b.votes||0) - (a.votes||0));
  const totalVotes = safeCands.reduce((s, c) => s + (c.votes || 0), 0);
  const barData    = regionData
    .map(r => ({ name: r.regionName?.split(" ")[0] || "—", votes: r.totalVotes || 0 }))
    .sort((a, b) => b.votes - a.votes)
    .slice(0, 6);

  // Face chart data — add extra top padding for face circles
  const faceChartData = sorted.map(c => ({
    name:     c.party || c.fullName?.split(" ").pop() || "?",
    votes:    c.votes || 0,
    color:    c.color,
    imageUrl: c.imageUrl,
    party:    c.party,
  }));

  const selectedElec = elections.find(e => e.id === selectedId);

  // ─── Render ────────────────────────────────────────────────────────────────
  if (elecLoading) return (
    <div className="p-7 flex items-center justify-center h-64">
      <Spinner s={32} />
    </div>
  );

  if (elections.length === 0) return (
    <div className="p-7">
      <EmptyState icon="tally" title="No elections"
        sub="Create an election first, then results will appear here." />
    </div>
  );

  return (
    <div className="p-7 flex flex-col gap-5">

      {/* Election selector */}
      <div className="flex items-center gap-3 flex-wrap">
        <SectionHeader title="Live Tally" sub="Real-time vote counts — WebSocket-driven" />
        <div className="flex gap-2 flex-wrap ml-auto">
          {elections
            .filter(e => ["ACTIVE","CLOSED"].includes(e.status))
            .map(e => (
              <button key={e.id}
                className={`px-4 py-2 rounded-xl text-xs font-bold border transition-all
                  ${selectedId === e.id
                    ? "bg-purple-500/20 border-purple-500/40 text-purple-300"
                    : "bg-elevated border-border text-sub hover:border-purple-500/20"}`}
                onClick={() => setSelectedId(e.id)}>
                {e.name}
                <span className={`ml-2 text-[9px] px-1.5 py-0.5 rounded-full
                  ${e.status === "ACTIVE" ? "bg-green-500/20 text-green-400" : "bg-slate-500/20 text-slate-400"}`}>
                  {e.status}
                </span>
              </button>
            ))}
        </div>
      </div>

      {loading ? (
        <div className="flex justify-center py-20"><Spinner s={32} /></div>
      ) : (
        <>
          {/* Stats row */}
          <div className="grid grid-cols-2 xl:grid-cols-4 gap-4">
            <StatCard label="Total Votes"   value={fmtVotes(totalVotes)}           icon="voters"   accent="purple" delay={0} />
            <StatCard label="Candidates"    value={safeCands.length}               icon="voters"   accent="blue"   delay={50} />
            <StatCard label="Leading Party" value={sorted[0]?.party || "—"}        icon="shield"   accent="green"  delay={100} />
            <StatCard label="Merkle Root"   value={merkle ? "Anchored" : "Pending"} icon="database" accent={merkle ? "green" : "amber"} delay={150} />
          </div>

          {/* Main panels */}
          <div className="grid grid-cols-1 xl:grid-cols-[1fr_300px] gap-5">

            {/* ── Candidate standings with animated face chart ── */}
            <div className="c-card p-6 animate-fade-up">
              <div className="flex items-center justify-between mb-4">
                <SectionHeader title="Candidate Standings"
                  sub="Live vote counts — updates via WebSocket" />
                {/* Toggle chart view */}
                <div className="flex gap-1 bg-elevated rounded-xl p-1 border border-border">
                  <button
                    className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-all
                      ${chartView === "face" ? "bg-purple-500/20 text-purple-300" : "text-sub hover:text-ink"}`}
                    onClick={() => setChartView("face")}>
                    <Ic n="voters" s={11} className="mr-1" /> Faces
                  </button>
                  <button
                    className={`px-3 py-1.5 text-xs font-bold rounded-lg transition-all
                      ${chartView === "bar" ? "bg-purple-500/20 text-purple-300" : "text-sub hover:text-ink"}`}
                    onClick={() => setChartView("bar")}>
                    <Ic n="trend" s={11} className="mr-1" /> Bars
                  </button>
                </div>
              </div>

              {safeCands.length === 0 ? (
                <EmptyState icon="tally" title="No candidates"
                  sub="Add candidates to this election to see standings" />
              ) : (
                <>
                  {/* ── Face chart ── */}
                  {chartView === "face" && (
                    <div className="mb-6">
                      <ResponsiveContainer width="100%" height={260}>
                        <BarChart
                          data={faceChartData}
                          margin={{ top: 56, right: 16, bottom: 8, left: 0 }}
                          barCategoryGap="25%">
                          <XAxis
                            dataKey="name"
                            tick={{ fill:"#6B7280", fontSize:11, fontFamily:"Arial" }}
                            axisLine={false} tickLine={false}
                          />
                          <YAxis
                            tick={{ fill:"#6B7280", fontSize:10 }}
                            axisLine={false} tickLine={false}
                            tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""}
                          />
                          <Tooltip
                            formatter={(v, name, props) => [fmtVotes(v), "Votes"]}
                            contentStyle={{ background:"#0F0F1A", border:"1px solid #2D2D4E", borderRadius:8 }}
                            labelStyle={{ color:"#A78BFA", fontWeight:"bold" }}
                            itemStyle={{ color:"#E2E8F0" }}
                          />
                          <Bar
                            dataKey="votes"
                            radius={[6,6,0,0]}
                            isAnimationActive={true}
                            animationDuration={900}
                            animationEasing="ease-out"
                            shape={(props) => {
                              const entry = faceChartData[props.index] || {};
                              return (
                                <FaceBar
                                  {...props}
                                  color={entry.color}
                                  imageUrl={entry.imageUrl}
                                  party={entry.party}
                                />
                              );
                            }}>
                            {faceChartData.map((entry, i) => (
                              <Cell key={i} fill={entry.color} />
                            ))}
                            <LabelList
                              dataKey="votes"
                              position="insideTop"
                              formatter={v => v > 0 ? fmtVotesShort(v) : ""}
                              style={{ fill:"#fff", fontSize:10, fontWeight:"bold" }}
                            />
                          </Bar>
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  )}

                  {/* ── Standard bar chart ── */}
                  {chartView === "bar" && (
                    <div className="mb-6">
                      <ResponsiveContainer width="100%" height={200}>
                        <BarChart data={faceChartData} margin={{ top:4, right:4, bottom:0, left:-20 }}>
                          <XAxis dataKey="name"
                            tick={{ fill:"#4A4464", fontSize:10, fontFamily:"JetBrains Mono" }}
                            axisLine={false} tickLine={false} />
                          <YAxis tick={{ fill:"#4A4464", fontSize:10 }}
                            axisLine={false} tickLine={false}
                            tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""} />
                          <Tooltip content={<ChartTooltip />} />
                          <Bar dataKey="votes" radius={[4,4,0,0]} isAnimationActive animationDuration={700}>
                            {faceChartData.map((e, i) => <Cell key={i} fill={e.color} />)}
                          </Bar>
                        </BarChart>
                      </ResponsiveContainer>
                    </div>
                  )}

                  {/* Progress bars list */}
                  <div className="flex flex-col gap-5">
                    {sorted.map((c, i) => {
                      const pct = totalVotes > 0
                        ? ((c.votes || 0) / totalVotes * 100).toFixed(1) : "0.0";
                      return (
                        <div key={c.id || i}>
                          <div className="flex items-center justify-between mb-2">
                            <div className="flex items-center gap-2.5">
                              {/* Candidate photo thumbnail */}
                              {c.imageUrl ? (
                                <img
                                  src={c.imageUrl}
                                  alt={c.fullName}
                                  className="w-8 h-8 rounded-full object-cover border-2"
                                  style={{ borderColor: c.color }}
                                  onError={e => { e.target.style.display="none"; }}
                                />
                              ) : (
                                <div
                                  className="w-8 h-8 rounded-full flex items-center justify-center
                                             text-[11px] font-bold text-white flex-shrink-0"
                                  style={{ background: c.color }}>
                                  {(c.party || "?")[0]}
                                </div>
                              )}
                              {i === 0 && <span title="Leading">🏆</span>}
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
                </>
              )}
            </div>

            {/* ── Regional breakdown ── */}
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
                  <BarChart data={barData} margin={{ top:4, right:4, bottom:0, left:-20 }}>
                    <XAxis dataKey="name"
                      tick={{ fill:"#4A4464", fontSize:10, fontFamily:"JetBrains Mono" }}
                      axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill:"#4A4464", fontSize:10 }}
                      axisLine={false} tickLine={false}
                      tickFormatter={v => v > 0 ? fmtVotesShort(v) : ""} />
                    <Tooltip content={<ChartTooltip />} />
                    <Bar dataKey="votes" fill="#8B5CF6" radius={[4,4,0,0]} />
                  </BarChart>
                </ResponsiveContainer>
              )}

              {regionData.length > 0 && (
                <div className="flex flex-col gap-1.5 mt-3">
                  {regionData.slice(0, 8).map((r, i) => (
                    <button key={r.regionId || r.regionName || i}
                      className="flex items-center gap-2 w-full text-left px-2 py-1.5
                                 rounded-lg hover:bg-white/5 transition-colors group cursor-pointer"
                      onClick={() => openDrilldown(r)}
                      title={`Drill down: ${r.regionName}`}>
                      <div className="w-2 h-2 rounded-[2px] flex-shrink-0"
                        style={{ background: REGION_COLORS[i % REGION_COLORS.length] }} />
                      <span className="text-xs text-sub flex-1 truncate group-hover:text-ink transition-colors">
                        {r.regionName}
                      </span>
                      <span className="mono text-[10px] text-muted">{fmtVotesShort(r.totalVotes||0)}</span>
                      <Ic n="chevron" s={10} c="#3D3A5C" className="group-hover:text-purple-400 transition-colors" />
                    </button>
                  ))}
                  {regionData.length > 8 && (
                    <p className="text-[10px] text-muted text-center pt-1">
                      +{regionData.length - 8} {regionLabel(regionType, "more")}
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>

          {/* Merkle root */}
          {merkle && (
            <div className="c-card p-4 flex items-start gap-3 border border-green-500/15 animate-fade-up">
              <Ic n="shield" s={16} c="#34D399" />
              <div>
                <div className="text-xs font-bold text-success mb-0.5">Merkle Root Anchored</div>
                <div className="mono text-[11px] text-muted break-all">{merkle}</div>
              </div>
            </div>
          )}

          {/* ── Drill-down modal ── */}
          {drillOpen && drillRegion && (
            <div className="fixed inset-0 z-50 flex items-center justify-center p-4"
              style={{ background:"rgba(0,0,0,0.7)", backdropFilter:"blur(4px)" }}
              onClick={e => { if (e.target === e.currentTarget) setDrillOpen(false); }}>
              <div className="bg-[#0F0F1A] border border-white/10 rounded-2xl p-6 w-full max-w-md
                              shadow-2xl animate-fade-up max-h-[80vh] overflow-y-auto">
                <div className="flex items-center justify-between mb-5">
                  <div>
                    <div className="text-sm font-bold text-ink">{drillRegion.regionName}</div>
                    <div className="text-[11px] text-muted mt-0.5">
                      {regionLabel(drillRegion.regionType, "noun")} level · {fmtVotes(drillRegion.totalVotes||0)} total votes
                    </div>
                  </div>
                  <button onClick={() => setDrillOpen(false)} className="text-muted hover:text-sub">
                    <Ic n="close" s={16} />
                  </button>
                </div>

                {drillRegion.candidates.length === 0 ? (
                  <p className="text-xs text-muted text-center py-6">No votes yet in this region</p>
                ) : (
                  <div className="flex flex-col gap-4">
                    {drillRegion.candidates.map((c, i) => {
                      const regTotal = drillRegion.candidates.reduce((s, x) => s + (x.votes||0), 0);
                      const pct = regTotal > 0 ? ((c.votes||0)/regTotal*100).toFixed(1) : "0.0";
                      return (
                        <div key={c.id || i}>
                          <div className="flex items-center justify-between mb-1.5">
                            <div className="flex items-center gap-2">
                              {c.imageUrl ? (
                                <img src={c.imageUrl} alt={c.fullName}
                                  className="w-7 h-7 rounded-full object-cover"
                                  style={{ border:`1.5px solid ${c.color}` }} />
                              ) : (
                                <div className="w-7 h-7 rounded-full flex items-center justify-center
                                                text-[10px] font-bold text-white"
                                  style={{ background: c.color }}>
                                  {(c.party||"?")[0]}
                                </div>
                              )}
                              <span className="text-xs font-bold text-ink">{c.fullName||c.name||"—"}</span>
                              <span className="badge badge-purple text-[9px]">{c.party}</span>
                            </div>
                            <div className="flex items-center gap-2">
                              <span className="mono text-sm font-bold" style={{color:c.color}}>
                                {fmtVotes(c.votes||0)}
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
            </div>
          )}
        </>
      )}
    </div>
  );
}
