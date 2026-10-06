import React, { useState, useEffect, useRef, useCallback } from "react";

/* ═══════════════════════════════════════
   Design Tokens — 暗色科技感
   ═══════════════════════════════════════ */
const T = {
  bg: "#0A0E17", surface: "#111827", card: "#151D2E", cardHover: "#1B2640",
  border: "#1E2A42", cyan: "#00E5FF", cyanDim: "#00E5FF33", purple: "#7C6AFF",
  pink: "#FF3D7F", green: "#00E676", orange: "#FF9F43",
  textP: "#E8ECF4", textS: "#5A6A8A", textDim: "#3A4A6A",
};

/* ═══════════════════════════════════════
   CSS Animations
   ═══════════════════════════════════════ */
const CSS = `
@import url('https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800;900&display=swap');
*{box-sizing:border-box;margin:0;padding:0}

@keyframes arrowLunge {
  0%,100% { transform: translateY(-4px) translateX(4px); }
  50%     { transform: translateY(-10px) translateX(10px); }
}
@keyframes arrowGlow {
  0%,100% { filter: drop-shadow(0 0 6px var(--c)); }
  50%     { filter: drop-shadow(0 0 18px var(--c)); }
}
@keyframes circleBreath {
  0%,100% { transform: translate(var(--ox),var(--oy)) scale(1); }
  50%     { transform: translate(var(--ox),var(--oy)) scale(1.1); }
}
@keyframes ringPulse {
  0%   { transform: scale(1); opacity:0.35; }
  100% { transform: scale(1.7); opacity:0; }
}
@keyframes centerPulse {
  0%,100% { opacity:0.5 }
  50%     { opacity:1 }
}
@keyframes fadeInUp {
  from { opacity:0; transform:translateY(16px) }
  to   { opacity:1; transform:translateY(0) }
}
@keyframes vibShake {
  0%,100% { transform: translateX(0) }
  20%     { transform: translateX(-2px) }
  40%     { transform: translateX(2px) }
  60%     { transform: translateX(-1px) }
  80%     { transform: translateX(1px) }
}
@keyframes nearRing {
  0%   { r: 36; opacity: 0.5; stroke-width: 2; }
  100% { r: 52; opacity: 0; stroke-width: 0.5; }
}
`;

/* ═══════════════════════════════════════
   Mock Devices
   ═══════════════════════════════════════ */
const DEVICES = [
  { id: 1, name: "小明", av: "M", color: "#00E5FF", dist: 12,  angle: 40  },
  { id: 2, name: "Luna", av: "L", color: "#7C6AFF", dist: 28,  angle: 135 },
  { id: 3, name: "阿杰", av: "A", color: "#FF9F43", dist: 5,   angle: -25 },
  { id: 4, name: "Sophia",av: "S", color: "#FF3D7F", dist: 42,  angle: 205 },
  { id: 5, name: "大伟", av: "D", color: "#00E676", dist: 18,  angle: -75 },
];
const MAX_DIST = 50;

/* ═══════════════════════════════════════
   Utilities
   ═══════════════════════════════════════ */
const deg2rad = d => d * Math.PI / 180;
const pX = (a, r, cx) => cx + r * Math.sin(deg2rad(a));
const pY = (a, r, cy) => cy - r * Math.cos(deg2rad(a));
const lerp = (v, lo, hi) => Math.max(0, Math.min(1, 1 - (v - lo) / (hi - lo)));
const hexToRgba = (hex, a) => {
  const r = parseInt(hex.slice(1,3),16);
  const g = parseInt(hex.slice(3,5),16);
  const b = parseInt(hex.slice(5,7),16);
  return `rgba(${r},${g},${b},${a})`;
};

/* ═══════════════════════════════════════
   ArrowCircle — 单个设备箭头圆
   ═══════════════════════════════════════ */
function ArrowCircle({ device, selected, onTap }) {
  const { color, dist, angle, name, av } = device;
  const isNear = dist <= 10;
  const intensity = lerp(dist, 0, MAX_DIST);     // 1=很近, 0=很远
  const radius = 32 + intensity * 6;              // 32–38px
  const offset = 4 + intensity * 16;              // 偏移量 4–20px

  // 圆在隐藏圆上的位置
  const ringCx = 170, ringCy = 190, ringR = 115;
  const cx = pX(angle, ringR, ringCx);
  const cy = pY(angle, ringR, ringCy);

  // 偏移方向 = 箭头指向（目标方向）
  const oX = offset * Math.sin(deg2rad(angle));
  const oY = -offset * Math.cos(deg2rad(angle));

  // 填充不透明度：距离越近越深
  const fillA = 0.08 + intensity * 0.52;
  const strokeA = 0.25 + intensity * 0.75;

  // 近距离：箭头前冲动画
  const arrowAnim = isNear ? "arrowLunge 0.7s ease-in-out infinite" : "none";
  const arrowGlow = isNear ? "arrowGlow 1.2s ease-in-out infinite" : "none";
  // 近距离：圆呼吸动画（在偏移方向上微微跳动）
  const circleAnim = isNear ? "circleBreath 1.4s ease-in-out infinite" : "none";

  return (
    <div
      onClick={() => onTap(device)}
      style={{
        position: "absolute",
        left: cx - radius, top: cy - radius,
        width: radius * 2, height: radius * 2,
        cursor: "pointer",
        // CSS 变量供动画使用
        "--ox": `${oX}px`, "--oy": `${oY}px`, "--c": color,
        transform: `translate(${oX}px, ${oY}px)`,
        animation: circleAnim,
        transition: "transform 0.4s cubic-bezier(.4,0,.2,1)",
        zIndex: isNear ? 20 : selected ? 15 : 10,
      }}
    >
      {/* 近距离脉冲环 */}
      {isNear && (
        <svg width={radius*2+24} height={radius*2+24}
          style={{ position:"absolute", top:-12, left:-12, pointerEvents:"none" }}>
          <circle cx={radius+12} cy={radius+12} r={radius} fill="none"
            stroke={color} strokeWidth="2" opacity="0"
            style={{ animation: "nearRing 1.5s ease-out infinite" }} />
          <circle cx={radius+12} cy={radius+12} r={radius} fill="none"
            stroke={color} strokeWidth="1.5" opacity="0"
            style={{ animation: "nearRing 1.5s ease-out 0.5s infinite" }} />
        </svg>
      )}

      {/* 主圆形 */}
      <div style={{
        width: "100%", height: "100%", borderRadius: "50%",
        background: `radial-gradient(circle at 40% 35%, ${hexToRgba(color, fillA * 0.6)}, ${hexToRgba(color, fillA)})`,
        border: `${selected ? 2.5 : 1.5}px solid ${hexToRgba(color, strokeA)}`,
        boxShadow: isNear
          ? `0 0 28px ${hexToRgba(color, 0.5)}, inset 0 0 20px ${hexToRgba(color, 0.15)}`
          : selected
            ? `0 0 16px ${hexToRgba(color, 0.25)}`
            : `0 0 8px ${hexToRgba(color, 0.1)}`,
        display: "flex", alignItems: "center", justifyContent: "center",
        transition: "border-color 0.3s, box-shadow 0.3s",
      }}>
        {/* 箭头 SVG */}
        <svg width="30" height="30" viewBox="0 0 30 30"
          style={{
            transform: `rotate(${angle}deg)`,
            animation: arrowAnim,
            filter: isNear ? undefined : "none",
            // arrowGlow 动画用 CSS 变量 --c
          }}>
          <defs>
            <linearGradient id={`ag${device.id}`} x1="0.5" y1="1" x2="0.5" y2="0">
              <stop offset="0%" stopColor={color} stopOpacity="0.15" />
              <stop offset="100%" stopColor={color} stopOpacity={isNear ? 1 : 0.7} />
            </linearGradient>
          </defs>
          <path d="M15 3 L22 16 L16.5 12 L16.5 27 L13.5 27 L13.5 12 L8 16 Z"
            fill={`url(#ag${device.id})`}
            style={isNear ? { animation: arrowGlow } : {}} />
        </svg>
      </div>

      {/* 名字 + 距离标签 */}
      <div style={{
        position: "absolute", top: radius * 2 + 6, left: "50%",
        transform: "translateX(-50%)", whiteSpace: "nowrap", textAlign: "center",
      }}>
        <div style={{
          fontSize: 11, fontWeight: 700, color, letterSpacing: 0.3,
          textShadow: isNear ? `0 0 8px ${hexToRgba(color, 0.5)}` : "none",
        }}>{name}</div>
        <div style={{
          fontSize: 10, fontWeight: 800, color: T.textP, marginTop: 1,
          opacity: isNear ? 1 : 0.6,
        }}>{dist.toFixed(1)}m</div>
      </div>
    </div>
  );
}

/* ═══════════════════════════════════════
   VibrationBar — 震动强度指示
   ═══════════════════════════════════════ */
function VibrationBar({ distance }) {
  const near = distance <= 10;
  const intensity = near ? (1 - distance / 10) : 0;
  const bars = Math.ceil(intensity * 5);

  return (
    <div style={{
      display: "flex", alignItems: "center", gap: 10, padding: "8px 16px",
      background: near ? `${T.pink}0A` : `${T.card}`,
      borderRadius: 14, border: `1px solid ${near ? T.pink + "33" : T.border}`,
    }}>
      {/* 震动图标 */}
      <div style={{
        fontSize: 16,
        animation: near ? "vibShake 0.3s ease-in-out infinite" : "none",
        opacity: near ? 1 : 0.3,
      }}>📳</div>

      {/* 条形图 */}
      <div style={{ display: "flex", gap: 3, alignItems: "flex-end", flex: 1 }}>
        {[1,2,3,4,5].map(i => (
          <div key={i} style={{
            width: 5,
            height: 4 + i * 4,
            borderRadius: 3,
            background: i <= bars
              ? `linear-gradient(to top, ${T.orange}, ${T.pink})`
              : T.border,
            transition: "all 0.3s",
            boxShadow: i <= bars ? `0 0 6px ${T.pink}44` : "none",
          }} />
        ))}
      </div>

      <span style={{
        fontSize: 11, fontWeight: 600,
        color: near ? T.pink : T.textDim,
      }}>
        {near
          ? `震动反馈 ${Math.round(intensity * 100)}%`
          : "未触发"}
      </span>
    </div>
  );
}

/* ═══════════════════════════════════════
   DeviceDetail — 底部设备详情卡
   ═══════════════════════════════════════ */
function DeviceDetail({ d }) {
  const isNear = d.dist <= 10;
  const intensity = lerp(d.dist, 0, MAX_DIST);

  return (
    <div style={{
      background: `linear-gradient(135deg, ${hexToRgba(d.color, 0.05)}, ${hexToRgba(d.color, 0.12)})`,
      borderRadius: 16, padding: "14px 18px",
      border: `1px solid ${hexToRgba(d.color, 0.2)}`,
      animation: "fadeInUp 0.25s ease-out",
    }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
        {/* 头像 */}
        <div style={{
          width: 38, height: 38, borderRadius: "50%",
          background: hexToRgba(d.color, 0.08 + intensity * 0.35),
          border: `2px solid ${d.color}`,
          display: "flex", alignItems: "center", justifyContent: "center",
          fontSize: 15, fontWeight: 800, color: d.color,
          boxShadow: isNear ? `0 0 14px ${hexToRgba(d.color, 0.4)}` : "none",
        }}>{d.av}</div>

        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 15, fontWeight: 700, color: T.textP }}>{d.name}</div>
          <div style={{
            fontSize: 11, fontWeight: 500, marginTop: 2,
            color: isNear ? T.green : T.textS,
          }}>{isNear ? "⚡ 已接近！" : "连接稳定"}</div>
        </div>

        {/* 距离 */}
        <div style={{ textAlign: "right" }}>
          <div style={{ fontSize: 28, fontWeight: 900, color: d.color, lineHeight: 1 }}>
            {d.dist.toFixed(1)}
          </div>
          <div style={{ fontSize: 10, color: T.textS, marginTop: 2 }}>米</div>
        </div>
      </div>

      {/* 10m 以内时显示震动指示器 */}
      {isNear && (
        <div style={{ marginTop: 12 }}>
          <VibrationBar distance={d.dist} />
        </div>
      )}
    </div>
  );
}

/* ═══════════════════════════════════════
   Main App
   ═══════════════════════════════════════ */
export default function App() {
  const [devices, setDevices] = useState(DEVICES);
  const [selId, setSelId] = useState(3);
  const cssRef = useRef(null);

  // 注入全局 CSS
  useEffect(() => {
    if (!cssRef.current) {
      const s = document.createElement("style");
      s.textContent = CSS;
      document.head.appendChild(s);
      cssRef.current = s;
    }
  }, []);

  // 模拟实时距离变化
  useEffect(() => {
    const id = setInterval(() => {
      setDevices(prev => prev.map(d => ({
        ...d,
        dist: Math.max(1, d.dist + (Math.random() - 0.5) * 1.2),
        angle: d.angle + (Math.random() - 0.5) * 1.5,
      })));
    }, 1200);
    return () => clearInterval(id);
  }, []);

  // 模拟手机震动（Web Vibration API）
  useEffect(() => {
    const hasNear = devices.some(d => d.dist <= 10);
    if (hasNear && navigator.vibrate) {
      navigator.vibrate([50, 100, 50]);
    }
  }, [Math.round(devices.filter(d => d.dist <= 10).length > 0 ? Date.now() / 2000 : 0)]);

  const selected = devices.find(d => d.id === selId);
  const nearestDist = Math.min(...devices.map(d => d.dist));
  const hasNear = devices.some(d => d.dist <= 10);

  const cx = 170, cy = 190, ringR = 115;

  return (
    <div style={{
      fontFamily: "'Inter', system-ui, sans-serif",
      background: T.bg, color: T.textP,
      minHeight: "100vh", maxWidth: 420, margin: "0 auto",
      position: "relative", overflow: "hidden",
    }}>
      {/* ── 状态栏 ── */}
      <div style={{
        display: "flex", justifyContent: "space-between", padding: "12px 20px 0",
        fontSize: 12, color: T.textS,
      }}>
        <span style={{ fontWeight: 600 }}>9:41</span>
        <div style={{ display: "flex", gap: 8, alignItems: "center" }}>
          <span style={{ color: hasNear ? T.pink : T.green, fontWeight: 600, fontSize: 11 }}>
            {hasNear ? "● 近距离" : "BLE"}
          </span>
          <span>86%</span>
        </div>
      </div>

      {/* ── 标题 ── */}
      <div style={{
        textAlign: "center", padding: "16px 0 4px",
      }}>
        <div style={{ fontSize: 16, fontWeight: 700, color: T.textP, letterSpacing: 0.5 }}>
          方向指示
        </div>
        <div style={{ fontSize: 11, color: T.textS, marginTop: 4 }}>
          {devices.length} 台设备在线 · 最近 {nearestDist.toFixed(1)}m
        </div>
      </div>

      {/* ── 主视觉区域：隐藏圆 + 箭头圆 ── */}
      <div style={{
        position: "relative", width: 340, height: 380,
        margin: "8px auto 0",
        // 近距离时整个区域微微震动
        animation: hasNear ? "vibShake 0.4s ease-in-out infinite" : "none",
      }}>
        {/* 隐藏圆 — 虚线描边，极淡 */}
        <svg width="340" height="380" viewBox="0 0 340 380"
          style={{ position: "absolute", top: 0, left: 0, pointerEvents: "none" }}>

          {/* 隐藏圆（参考圆） */}
          <circle cx={cx} cy={cy} r={ringR}
            fill="none" stroke={T.border} strokeWidth="1"
            strokeDasharray="6 8" opacity="0.4" />

          {/* 十字辅助线 */}
          <line x1={cx} y1={cy - ringR - 10} x2={cx} y2={cy + ringR + 10}
            stroke={T.border} strokeWidth="0.5" opacity="0.15" />
          <line x1={cx - ringR - 10} y1={cy} x2={cx + ringR + 10} y2={cy}
            stroke={T.border} strokeWidth="0.5" opacity="0.15" />

          {/* 圆心 = 你 */}
          <circle cx={cx} cy={cy} r="7" fill={T.bg}
            stroke={T.cyan} strokeWidth="2" />
          <circle cx={cx} cy={cy} r="3" fill={T.cyan}
            style={{ animation: "centerPulse 2s ease-in-out infinite" }} />
          <text x={cx} y={cy + 20} textAnchor="middle"
            fill={T.cyan} fontSize="10" fontWeight="700">你</text>

          {/* 圆心到每个设备的方向虚线 */}
          {devices.map(d => (
            <line key={`line-${d.id}`}
              x1={cx} y1={cy}
              x2={pX(d.angle, ringR + 8, cx)}
              y2={pY(d.angle, ringR + 8, cy)}
              stroke={d.color} strokeWidth="0.5" strokeDasharray="3 5" opacity="0.15"
            />
          ))}
        </svg>

        {/* 箭头圆形们 */}
        {devices.map(d => (
          <ArrowCircle
            key={d.id}
            device={d}
            selected={d.id === selId}
            onTap={dev => setSelId(dev.id)}
          />
        ))}
      </div>

      {/* ── 底部信息区 ── */}
      <div style={{
        padding: "0 20px 100px",
        marginTop: -8,
      }}>
        {/* 设备标签组 */}
        <div style={{
          display: "flex", gap: 6, overflowX: "auto", paddingBottom: 12,
          scrollbarWidth: "none",
        }}>
          {devices.map(d => (
            <div key={d.id}
              onClick={() => setSelId(d.id)}
              style={{
                display: "flex", alignItems: "center", gap: 5,
                padding: "6px 12px", borderRadius: 12, flexShrink: 0,
                background: d.id === selId ? hexToRgba(d.color, 0.12) : T.card,
                border: `1.5px solid ${d.id === selId ? hexToRgba(d.color, 0.5) : T.border}`,
                cursor: "pointer", transition: "all 0.2s",
              }}>
              <div style={{
                width: 7, height: 7, borderRadius: "50%", background: d.color,
                boxShadow: d.dist <= 10 ? `0 0 8px ${hexToRgba(d.color, 0.6)}` : "none",
              }} />
              <span style={{ fontSize: 12, fontWeight: 600, color: d.id === selId ? d.color : T.textP }}>
                {d.name}
              </span>
            </div>
          ))}
        </div>

        {/* 选中设备详情 */}
        {selected && <DeviceDetail d={selected} />}
      </div>

      {/* ── 底部导航栏 ── */}
      <div style={{
        position: "fixed", bottom: 16, left: "50%", transform: "translateX(-50%)",
        display: "flex", gap: 4, padding: "6px 10px",
        background: T.surface + "EE", borderRadius: 20,
        border: `1px solid ${T.border}`,
        backdropFilter: "blur(20px)", boxShadow: "0 8px 32px rgba(0,0,0,0.4)",
      }}>
        {[
          { icon: (
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <circle cx="10" cy="10" r="7" stroke="currentColor" strokeWidth="1.5" strokeDasharray="3 2" />
              <circle cx="10" cy="10" r="2.5" fill="currentColor" />
            </svg>
          ), label: "指示", active: true },
          { icon: (
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <circle cx="10" cy="10" r="7" stroke="currentColor" strokeWidth="1.5" />
              <path d="M10 5V10L13 13" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          ), label: "历史" },
          { icon: (
            <svg width="20" height="20" viewBox="0 0 20 20" fill="none">
              <circle cx="10" cy="6" r="3.5" stroke="currentColor" strokeWidth="1.5" />
              <path d="M4 17C4 13.7 6.7 11 10 11S16 13.7 16 17" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          ), label: "我的" },
        ].map((item, i) => (
          <div key={i} style={{
            display: "flex", flexDirection: "column", alignItems: "center", gap: 2,
            padding: "8px 18px", borderRadius: 14, cursor: "pointer",
            background: item.active ? T.cyan + "15" : "transparent",
            color: item.active ? T.cyan : T.textS,
            transition: "all 0.2s",
          }}>
            {item.icon}
            <span style={{ fontSize: 10, fontWeight: 600 }}>{item.label}</span>
          </div>
        ))}
      </div>
    </div>
  );
}
