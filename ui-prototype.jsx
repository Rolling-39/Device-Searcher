import React, { useState, useEffect, useRef } from "react";

// ─── Design Tokens ───
const T = {
  bg: "#0A0E17",
  surface: "#111827",
  card: "#151D2E",
  cardHover: "#1B2640",
  border: "#1E2A42",
  cyan: "#00E5FF",
  cyanDim: "#00E5FF33",
  cyanMid: "#00E5FF66",
  purple: "#7C6AFF",
  purpleDim: "#7C6AFF33",
  pink: "#FF3D7F",
  green: "#00E676",
  orange: "#FF9F43",
  textPrimary: "#E8ECF4",
  textSecondary: "#5A6A8A",
  textDim: "#3A4A6A",
};

// ─── Mock Data ───
const MOCK_DEVICES = [
  { id: 1, name: "小明", avatar: "M", color: "#00E5FF", distance: 12, angle: 45, signal: 82, status: "stable" },
  { id: 2, name: "Luna", avatar: "L", color: "#7C6AFF", distance: 28, angle: 150, signal: 56, status: "stable" },
  { id: 3, name: "阿杰", avatar: "A", color: "#FF9F43", distance: 5, angle: -30, signal: 95, status: "nearby" },
  { id: 4, name: "Sophia", avatar: "S", color: "#FF3D7F", distance: 42, angle: 210, signal: 38, status: "weak" },
  { id: 5, name: "大伟", avatar: "D", color: "#00E676", distance: 18, angle: -80, signal: 74, status: "stable" },
];

// ─── Utility ───
const deg2rad = (d) => (d * Math.PI) / 180;
const polarX = (angle, radius, cx) => cx + radius * Math.sin(deg2rad(angle));
const polarY = (angle, radius, cy) => cy - radius * Math.cos(deg2rad(angle));

// ─── Animated CSS injected once ───
const GLOBAL_CSS = `
  @import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');
  * { box-sizing: border-box; margin: 0; padding: 0; }
  @keyframes radarSweep { from { transform: rotate(0deg); } to { transform: rotate(360deg); } }
  @keyframes pulse { 0%,100% { opacity:0.15; transform:scale(1); } 50% { opacity:0.35; transform:scale(1.05); } }
  @keyframes pulseRing {
    0% { transform: scale(0.3); opacity: 0.6; }
    100% { transform: scale(1.5); opacity: 0; }
  }
  @keyframes float { 0%,100% { transform:translateY(0); } 50% { transform:translateY(-6px); } }
  @keyframes glow { 0%,100% { filter: drop-shadow(0 0 6px currentColor); } 50% { filter: drop-shadow(0 0 16px currentColor); } }
  @keyframes fadeInUp { from { opacity:0; transform:translateY(20px); } to { opacity:1; transform:translateY(0); } }
  @keyframes dotPulse { 0%,100% { r: 4; } 50% { r: 7; } }
  @keyframes scanLine { 0% { opacity: 0; } 10% { opacity: 0.8; } 100% { opacity: 0; } }
  @keyframes arrowGlow {
    0%,100% { filter: drop-shadow(0 0 8px ${T.cyan}); }
    50% { filter: drop-shadow(0 0 20px ${T.cyan}); }
  }
`;

// ─── Sub-components ───

const StatusBar = () => (
  <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", padding: "12px 20px 8px", fontSize: 12, color: T.textSecondary }}>
    <span style={{ fontWeight: 600 }}>9:41</span>
    <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
      <span>BLE</span>
      <svg width="16" height="12" viewBox="0 0 16 12" fill="none">
        <rect x="0" y="3" width="3" height="6" rx="1" fill={T.cyan} opacity="0.4" />
        <rect x="4" y="2" width="3" height="8" rx="1" fill={T.cyan} opacity="0.6" />
        <rect x="8" y="1" width="3" height="10" rx="1" fill={T.cyan} opacity="0.8" />
        <rect x="12" y="0" width="3" height="12" rx="1" fill={T.cyan} />
      </svg>
      <span>86%</span>
    </div>
  </div>
);

const AvatarCircle = ({ letter, color, size = 40, glow = false }) => (
  <div style={{
    width: size, height: size, borderRadius: "50%",
    background: `linear-gradient(135deg, ${color}22, ${color}44)`,
    border: `2px solid ${color}`,
    display: "flex", alignItems: "center", justifyContent: "center",
    fontSize: size * 0.4, fontWeight: 700, color,
    boxShadow: glow ? `0 0 20px ${color}44` : "none",
  }}>
    {letter}
  </div>
);

const SignalDots = ({ level, color = T.cyan }) => (
  <div style={{ display: "flex", gap: 3, alignItems: "flex-end" }}>
    {[1,2,3,4].map(i => (
      <div key={i} style={{
        width: 4, height: 4 + i * 3, borderRadius: 2,
        background: i <= Math.ceil(level / 25) ? color : T.border,
        transition: "background 0.3s"
      }} />
    ))}
  </div>
);

// ─── Screen: Scanning ───
const ScanScreen = ({ devices, onSelectDevice, onSwitchMode }) => {
  const [scanAngle, setScanAngle] = useState(0);
  const [pulseScale, setPulseScale] = useState(1);

  useEffect(() => {
    const id = setInterval(() => {
      setScanAngle(a => (a + 2) % 360);
      setPulseScale(s => s === 1 ? 1.03 : 1);
    }, 30);
    return () => clearInterval(id);
  }, []);

  const cx = 160, cy = 160, maxR = 140;

  return (
    <div style={{ animation: "fadeInUp 0.4s ease-out", display: "flex", flexDirection: "column", alignItems: "center", padding: "0 16px" }}>
      {/* Radar */}
      <div style={{ position: "relative", width: 320, height: 320, marginBottom: 24 }}>
        <svg width="320" height="320" viewBox="0 0 320 320">
          {/* Rings */}
          {[0.3, 0.6, 1.0].map((s, i) => (
            <circle key={i} cx={cx} cy={cy} r={maxR * s}
              fill="none" stroke={T.border} strokeWidth="1" strokeDasharray="4 4" opacity={0.5} />
          ))}
          {/* Ring labels */}
          <text x={cx + 4} y={cy - maxR * 0.3 + 4} fill={T.textDim} fontSize="9">~10m</text>
          <text x={cx + 4} y={cy - maxR * 0.6 + 4} fill={T.textDim} fontSize="9">~30m</text>
          <text x={cx + 4} y={cy - maxR + 4} fill={T.textDim} fontSize="9">~50m</text>
          {/* Cross lines */}
          <line x1={cx} y1={cy - maxR} x2={cx} y2={cy + maxR} stroke={T.border} strokeWidth="0.5" opacity="0.3" />
          <line x1={cx - maxR} y1={cy} x2={cx + maxR} y2={cy} stroke={T.border} strokeWidth="0.5" opacity="0.3" />
          {/* Sweep beam */}
          <g style={{ transform: `rotate(${scanAngle}deg)`, transformOrigin: `${cx}px ${cy}px`, transition: "none" }}>
            <defs>
              <linearGradient id="sweepGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stopColor={T.cyan} stopOpacity="0.4" />
                <stop offset="100%" stopColor={T.cyan} stopOpacity="0" />
              </linearGradient>
            </defs>
            <path d={`M${cx},${cy} L${cx},${cy - maxR} A${maxR},${maxR} 0 0,1 ${polarX(30, maxR, cx)},${polarY(-30 + 360, maxR, cy)} Z`}
              fill="url(#sweepGrad)" />
            <line x1={cx} y1={cy} x2={cx} y2={cy - maxR} stroke={T.cyan} strokeWidth="1.5" opacity="0.7" />
          </g>
          {/* Center dot (you) */}
          <circle cx={cx} cy={cy} r="6" fill={T.cyan} opacity="0.9" />
          <circle cx={cx} cy={cy} r="12" fill="none" stroke={T.cyan} strokeWidth="1" opacity="0.3"
            style={{ animation: "pulseRing 2s ease-out infinite" }} />
          {/* Device dots */}
          {devices.map(d => {
            const normDist = Math.min(d.distance / 50, 1);
            const dx = polarX(d.angle, maxR * normDist, cx);
            const dy = polarY(d.angle, maxR * normDist, cy);
            return (
              <g key={d.id} onClick={() => onSelectDevice(d)} style={{ cursor: "pointer" }}>
                <circle cx={dx} cy={dy} r="16" fill={d.color} opacity="0.08" />
                <circle cx={dx} cy={dy} r="5" fill={d.color} opacity="0.9"
                  style={{ animation: "dotPulse 2s ease-in-out infinite" }} />
                <circle cx={dx} cy={dy} r="9" fill="none" stroke={d.color} strokeWidth="1" opacity="0.4" />
              </g>
            );
          })}
        </svg>
      </div>

      {/* Scanning label */}
      <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 20 }}>
        <div style={{ width: 8, height: 8, borderRadius: "50%", background: T.green, animation: "pulse 1.5s ease-in-out infinite" }} />
        <span style={{ color: T.textSecondary, fontSize: 13, fontWeight: 500, letterSpacing: 1 }}>
          扫描中 · 发现 {devices.length} 台设备
        </span>
      </div>

      {/* Device list */}
      <div style={{ width: "100%", maxWidth: 360 }}>
        {devices.map((d, i) => (
          <div key={d.id}
            onClick={() => onSelectDevice(d)}
            style={{
              display: "flex", alignItems: "center", gap: 14, padding: "14px 16px",
              background: T.card, borderRadius: 14, marginBottom: 8, cursor: "pointer",
              border: `1px solid ${T.border}`,
              transition: "all 0.2s",
              animation: `fadeInUp 0.3s ease-out ${i * 0.08}s both`,
            }}
            onMouseEnter={e => { e.currentTarget.style.background = T.cardHover; e.currentTarget.style.borderColor = d.color + "44"; }}
            onMouseLeave={e => { e.currentTarget.style.background = T.card; e.currentTarget.style.borderColor = T.border; }}
          >
            <AvatarCircle letter={d.avatar} color={d.color} size={38} />
            <div style={{ flex: 1 }}>
              <div style={{ fontSize: 15, fontWeight: 600, color: T.textPrimary }}>{d.name}</div>
              <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 2 }}>
                {d.status === "nearby" ? "就在附近" : d.status === "weak" ? "信号较弱" : "连接稳定"}
              </div>
            </div>
            <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 4 }}>
              <span style={{ fontSize: 18, fontWeight: 800, color: d.color }}>{d.distance}<span style={{ fontSize: 11, fontWeight: 500, opacity: 0.7 }}>m</span></span>
              <SignalDots level={d.signal} color={d.color} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
};

// ─── Screen: Focus (Single Target Tracking) ───
const FocusScreen = ({ device, onBack }) => {
  const [arrowAngle, setArrowAngle] = useState(device.angle);
  const [distance, setDistance] = useState(device.distance);
  const [confidence, setConfidence] = useState("high");

  useEffect(() => {
    const id = setInterval(() => {
      setArrowAngle(a => a + (Math.random() - 0.5) * 3);
      setDistance(d => Math.max(1, d + (Math.random() - 0.5) * 0.8));
    }, 800);
    return () => clearInterval(id);
  }, []);

  const confLevel = distance < 10 ? "high" : distance < 30 ? "mid" : "low";
  const confColors = { high: T.green, mid: T.orange, low: T.pink };
  const confLabels = { high: "精确", mid: "较准", low: "粗略" };

  return (
    <div style={{ animation: "fadeInUp 0.4s ease-out", display: "flex", flexDirection: "column", alignItems: "center", padding: "0 16px" }}>
      {/* Back button */}
      <div style={{ width: "100%", display: "flex", alignItems: "center", marginBottom: 12 }}>
        <div onClick={onBack} style={{
          width: 36, height: 36, borderRadius: 10, background: T.card, border: `1px solid ${T.border}`,
          display: "flex", alignItems: "center", justifyContent: "center", cursor: "pointer",
        }}>
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none">
            <path d="M11 4L6 9L11 14" stroke={T.textSecondary} strokeWidth="2" strokeLinecap="round" />
          </svg>
        </div>
        <div style={{ flex: 1, textAlign: "center" }}>
          <span style={{ fontSize: 14, fontWeight: 600, color: T.textPrimary }}>{device.name}</span>
        </div>
        <div style={{ width: 36 }} />
      </div>

      {/* Target info card */}
      <div style={{
        display: "flex", alignItems: "center", gap: 14, padding: "12px 20px",
        background: `linear-gradient(135deg, ${device.color}0A, ${device.color}15)`,
        borderRadius: 16, border: `1px solid ${device.color}22`, marginBottom: 28, width: "100%", maxWidth: 360,
      }}>
        <AvatarCircle letter={device.avatar} color={device.color} size={48} glow />
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 18, fontWeight: 700, color: T.textPrimary }}>{device.name}</div>
          <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 2, display: "flex", alignItems: "center", gap: 6 }}>
            <span style={{ display: "inline-block", width: 6, height: 6, borderRadius: "50%", background: confColors[confLevel] }} />
            信号{confLabels[confLevel]} · {device.signal}%
          </div>
        </div>
        <SignalDots level={device.signal} color={device.color} />
      </div>

      {/* Arrow compass area */}
      <div style={{ position: "relative", width: 280, height: 280, marginBottom: 28 }}>
        {/* Outer ring */}
        <svg width="280" height="280" viewBox="0 0 280 280" style={{ position: "absolute" }}>
          {/* Compass ring */}
          <circle cx="140" cy="140" r="130" fill="none" stroke={T.border} strokeWidth="1" />
          <circle cx="140" cy="140" r="110" fill="none" stroke={T.border} strokeWidth="0.5" strokeDasharray="3 6" />
          {/* Cardinal directions */}
          {["N","E","S","W"].map((dir, i) => {
            const a = i * 90;
            const x = polarX(a, 135, 140);
            const y = polarY(a, 135, 140);
            return <text key={dir} x={x} y={y} textAnchor="middle" dominantBaseline="central"
              fill={dir === "N" ? T.cyan : T.textDim} fontSize="11" fontWeight={dir === "N" ? 700 : 500}>{dir}</text>;
          })}
          {/* Tick marks */}
          {Array.from({ length: 36 }, (_, i) => {
            const a = i * 10;
            const r1 = i % 3 === 0 ? 122 : 126;
            const r2 = 130;
            return (
              <line key={i}
                x1={polarX(a, r1, 140)} y1={polarY(a, r1, 140)}
                x2={polarX(a, r2, 140)} y2={polarY(a, r2, 140)}
                stroke={i % 3 === 0 ? T.textDim : T.border} strokeWidth={i % 3 === 0 ? 1.5 : 0.5} />
            );
          })}
          {/* Direction cone (confidence visualization) */}
          {(() => {
            const coneWidth = confLevel === "high" ? 20 : confLevel === "mid" ? 45 : 80;
            const a1 = arrowAngle - coneWidth / 2;
            const a2 = arrowAngle + coneWidth / 2;
            const x1 = polarX(a1, 100, 140);
            const y1 = polarY(a1, 100, 140);
            const x2 = polarX(a2, 100, 140);
            const y2 = polarY(a2, 100, 140);
            return (
              <path d={`M140,140 L${x1},${y1} A100,100 0 0,0 ${x2},${y2} Z`}
                fill={device.color} opacity="0.06" />
            );
          })()}
        </svg>

        {/* Arrow */}
        <div style={{
          position: "absolute", top: "50%", left: "50%",
          transform: `translate(-50%, -50%) rotate(${arrowAngle}deg)`,
          transition: "transform 0.5s cubic-bezier(0.4, 0, 0.2, 1)",
          animation: "arrowGlow 3s ease-in-out infinite",
        }}>
          <svg width="120" height="120" viewBox="0 0 120 120">
            <defs>
              <linearGradient id="arrowGrad" x1="0.5" y1="1" x2="0.5" y2="0">
                <stop offset="0%" stopColor={device.color} stopOpacity="0.1" />
                <stop offset="60%" stopColor={device.color} stopOpacity="0.6" />
                <stop offset="100%" stopColor={device.color} stopOpacity="1" />
              </linearGradient>
            </defs>
            {/* Arrow body */}
            <path d="M60 10 L80 55 L65 48 L65 105 L55 105 L55 48 L40 55 Z"
              fill="url(#arrowGrad)" />
            {/* Arrow tip glow */}
            <circle cx="60" cy="14" r="4" fill={device.color} opacity="0.8" />
          </svg>
        </div>

        {/* Center you-dot */}
        <div style={{
          position: "absolute", top: "50%", left: "50%", transform: "translate(-50%, -50%)",
          width: 14, height: 14, borderRadius: "50%", background: T.cyan,
          boxShadow: `0 0 16px ${T.cyan}66`,
        }} />
      </div>

      {/* Distance display */}
      <div style={{ textAlign: "center", marginBottom: 20 }}>
        <div style={{ fontSize: 56, fontWeight: 900, color: device.color, lineHeight: 1, letterSpacing: -2 }}>
          {distance.toFixed(1)}
          <span style={{ fontSize: 20, fontWeight: 500, opacity: 0.6, marginLeft: 4 }}>米</span>
        </div>
        <div style={{ fontSize: 12, color: T.textSecondary, marginTop: 6 }}>
          约 {Math.ceil(distance * 1.2)} 步 · 步行 {Math.ceil(distance / 1.4)} 秒
        </div>
      </div>

      {/* Confidence indicator */}
      <div style={{
        display: "flex", alignItems: "center", gap: 8, padding: "8px 16px",
        background: `${confColors[confLevel]}0D`, borderRadius: 20,
        border: `1px solid ${confColors[confLevel]}22`,
      }}>
        <div style={{ width: 6, height: 6, borderRadius: "50%", background: confColors[confLevel], animation: "pulse 1.5s ease-in-out infinite" }} />
        <span style={{ fontSize: 12, fontWeight: 600, color: confColors[confLevel] }}>
          {confLevel === "high" ? "信号精确 — 箭头方向可信" : confLevel === "mid" ? "较准确 — 建议走动以提高精度" : "粗略定位 — 走动几步可改善"}
        </span>
      </div>
    </div>
  );
};

// ─── Screen: Radar Overview ───
const RadarScreen = ({ devices, onSelectDevice }) => {
  const [tick, setTick] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setTick(t => t + 1), 1000);
    return () => clearInterval(id);
  }, []);

  const cx = 160, cy = 180, maxR = 140;

  return (
    <div style={{ animation: "fadeInUp 0.4s ease-out", display: "flex", flexDirection: "column", alignItems: "center", padding: "0 16px" }}>
      <div style={{ fontSize: 15, fontWeight: 600, color: T.textPrimary, marginBottom: 4 }}>全景雷达</div>
      <div style={{ fontSize: 12, color: T.textSecondary, marginBottom: 20 }}>实时追踪周围 {devices.length} 台设备</div>

      <div style={{ position: "relative", width: 320, height: 360 }}>
        <svg width="320" height="360" viewBox="0 0 320 360">
          {/* Grid rings */}
          {[0.25, 0.5, 0.75, 1.0].map((s, i) => (
            <g key={i}>
              <circle cx={cx} cy={cy} r={maxR * s} fill="none" stroke={T.border} strokeWidth="0.8" strokeDasharray="3 5" />
              <text x={cx + maxR * s + 4} y={cy + 4} fill={T.textDim} fontSize="8">{Math.round(50 * s)}m</text>
            </g>
          ))}
          {/* Cross */}
          <line x1={cx} y1={cy - maxR} x2={cx} y2={cy + maxR} stroke={T.border} strokeWidth="0.5" opacity="0.2" />
          <line x1={cx - maxR} y1={cy} x2={cx + maxR} y2={cy} stroke={T.border} strokeWidth="0.5" opacity="0.2" />
          {/* Diagonals */}
          <line x1={cx - maxR * 0.7} y1={cy - maxR * 0.7} x2={cx + maxR * 0.7} y2={cy + maxR * 0.7} stroke={T.border} strokeWidth="0.3" opacity="0.15" />
          <line x1={cx + maxR * 0.7} y1={cy - maxR * 0.7} x2={cx - maxR * 0.7} y2={cy + maxR * 0.7} stroke={T.border} strokeWidth="0.3" opacity="0.15" />

          {/* User center */}
          <circle cx={cx} cy={cy} r="8" fill={T.bg} stroke={T.cyan} strokeWidth="2" />
          <circle cx={cx} cy={cy} r="3" fill={T.cyan} />
          <text x={cx} y={cy + 22} textAnchor="middle" fill={T.cyan} fontSize="10" fontWeight="600">你</text>

          {/* Device markers */}
          {devices.map(d => {
            const normDist = Math.min(d.distance / 50, 1);
            const jitter = Math.sin(tick * 0.5 + d.id) * 2;
            const dx = polarX(d.angle, maxR * normDist + jitter, cx);
            const dy = polarY(d.angle, maxR * normDist + jitter, cy);
            const arrowLen = 18;
            const ax = polarX(d.angle, arrowLen, dx) - dx;
            const ay = polarY(d.angle, arrowLen, dy) - dy;
            return (
              <g key={d.id} onClick={() => onSelectDevice(d)} style={{ cursor: "pointer" }}>
                {/* Direction line */}
                <line x1={dx} y1={dy} x2={dx - ax * 0.6} y2={dy - ay * 0.6}
                  stroke={d.color} strokeWidth="2" strokeLinecap="round" opacity="0.4" />
                {/* Halo */}
                <circle cx={dx} cy={dy} r="18" fill={d.color} opacity="0.05" />
                {/* Dot */}
                <circle cx={dx} cy={dy} r="6" fill={d.color} opacity="0.85" />
                <circle cx={dx} cy={dy} r="9" fill="none" stroke={d.color} strokeWidth="1" opacity="0.3" />
                {/* Label */}
                <rect x={dx + 12} y={dy - 10} width={d.name.length * 13 + 12} height="20" rx="6"
                  fill={T.bg} stroke={d.color} strokeWidth="0.8" opacity="0.9" />
                <text x={dx + 18} y={dy + 3} fill={d.color} fontSize="10" fontWeight="600">
                  {d.name}
                </text>
                <text x={dx + 18 + d.name.length * 11} y={dy + 3} fill={T.textSecondary} fontSize="9">
                  {d.distance}m
                </text>
              </g>
            );
          })}
        </svg>
      </div>

      {/* Legend */}
      <div style={{
        display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center", marginTop: 8
      }}>
        {devices.map(d => (
          <div key={d.id} onClick={() => onSelectDevice(d)}
            style={{
              display: "flex", alignItems: "center", gap: 6, padding: "6px 12px",
              background: T.card, borderRadius: 10, border: `1px solid ${T.border}`, cursor: "pointer",
              fontSize: 12, transition: "all 0.2s",
            }}
            onMouseEnter={e => e.currentTarget.style.borderColor = d.color + "66"}
            onMouseLeave={e => e.currentTarget.style.borderColor = T.border}
          >
            <div style={{ width: 8, height: 8, borderRadius: "50%", background: d.color }} />
            <span style={{ color: T.textPrimary, fontWeight: 600 }}>{d.name}</span>
            <span style={{ color: T.textSecondary }}>{d.distance}m</span>
          </div>
        ))}
      </div>
    </div>
  );
};

// ─── Bottom Nav ───
const BottomNav = ({ active, onChange }) => {
  const items = [
    { id: "scan", icon: (
      <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
        <circle cx="11" cy="11" r="3" stroke="currentColor" strokeWidth="1.5" />
        <path d="M11 2V5M11 17V20M2 11H5M17 11H20" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        <circle cx="11" cy="11" r="8" stroke="currentColor" strokeWidth="1.5" strokeDasharray="4 3" />
      </svg>
    ), label: "扫描" },
    { id: "focus", icon: (
      <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
        <path d="M11 3L15 10L11 8L7 10Z" fill="currentColor" opacity="0.8" />
        <path d="M11 8V18" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        <circle cx="11" cy="11" r="9" stroke="currentColor" strokeWidth="1.5" />
      </svg>
    ), label: "追踪" },
    { id: "radar", icon: (
      <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
        <circle cx="11" cy="11" r="8" stroke="currentColor" strokeWidth="1.5" />
        <circle cx="11" cy="11" r="4" stroke="currentColor" strokeWidth="1" strokeDasharray="2 2" />
        <path d="M11 3V11" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        <circle cx="11" cy="11" r="1.5" fill="currentColor" />
      </svg>
    ), label: "雷达" },
  ];

  return (
    <div style={{
      position: "fixed", bottom: 16, left: "50%", transform: "translateX(-50%)",
      display: "flex", gap: 4, padding: "6px 8px",
      background: T.surface + "EE", borderRadius: 20,
      border: `1px solid ${T.border}`,
      backdropFilter: "blur(20px)", boxShadow: "0 8px 32px rgba(0,0,0,0.4)",
    }}>
      {items.map(item => (
        <div key={item.id} onClick={() => onChange(item.id)}
          style={{
            display: "flex", flexDirection: "column", alignItems: "center", gap: 2,
            padding: "8px 20px", borderRadius: 14, cursor: "pointer",
            background: active === item.id ? T.cyan + "15" : "transparent",
            color: active === item.id ? T.cyan : T.textSecondary,
            transition: "all 0.2s",
          }}>
          {item.icon}
          <span style={{ fontSize: 10, fontWeight: 600 }}>{item.label}</span>
        </div>
      ))}
    </div>
  );
};

// ─── Main App ───
export default function App() {
  const [screen, setScreen] = useState("scan");
  const [selectedDevice, setSelectedDevice] = useState(null);
  const styleRef = useRef(null);

  useEffect(() => {
    if (!styleRef.current) {
      const s = document.createElement("style");
      s.textContent = GLOBAL_CSS;
      document.head.appendChild(s);
      styleRef.current = s;
    }
  }, []);

  const handleSelectDevice = (d) => {
    setSelectedDevice(d);
    setScreen("focus");
  };

  return (
    <div style={{
      fontFamily: "'Inter', system-ui, sans-serif",
      background: T.bg, color: T.textPrimary,
      minHeight: "100vh", overflow: "hidden",
      maxWidth: 420, margin: "0 auto", position: "relative",
    }}>
      <StatusBar />

      <div style={{ paddingBottom: 90, paddingTop: 8, minHeight: "calc(100vh - 40px)", overflowY: "auto" }}>
        {screen === "scan" && (
          <ScanScreen devices={MOCK_DEVICES} onSelectDevice={handleSelectDevice} />
        )}
        {screen === "focus" && selectedDevice && (
          <FocusScreen device={selectedDevice} onBack={() => setScreen("scan")} />
        )}
        {screen === "radar" && (
          <RadarScreen devices={MOCK_DEVICES} onSelectDevice={handleSelectDevice} />
        )}
      </div>

      <BottomNav active={screen} onChange={(s) => {
        if (s === "focus" && !selectedDevice) {
          setSelectedDevice(MOCK_DEVICES[0]);
        }
        setScreen(s);
      }} />
    </div>
  );
}
