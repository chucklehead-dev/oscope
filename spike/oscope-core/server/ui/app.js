// oscope web UI: HyperDX-style search, histogram, facets, trace waterfall
// and service overview over the JSON API. No framework, no build step.
"use strict";

const $ = (sel, el = document) => el.querySelector(sel);
const h = (tag, attrs = {}, ...kids) => {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue;
    if (k === "class") e.className = v;
    else if (k === "style") e.style.cssText = v;
    else if (k.startsWith("on")) e.addEventListener(k.slice(2), v);
    else e.setAttribute(k, v === true ? "" : v);
  }
  for (const kid of kids.flat(Infinity)) if (kid != null && kid !== false) e.append(kid instanceof Node ? kid : String(kid));
  return e;
};
const svg = (tag, attrs = {}) => {
  const e = document.createElementNS("http://www.w3.org/2000/svg", tag);
  for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
  return e;
};

// ------------------------------------------------------------------ state

const RANGES = { "5m": 5, "15m": 15, "1h": 60, "6h": 360, "24h": 1440 };
const LEVEL_ORDER = ["FATAL", "ERROR", "WARN", "INFO", "DEBUG", "TRACE"];
const LEVEL_COLOR = { FATAL: "var(--error)", ERROR: "var(--error)", WARN: "var(--warn)", INFO: "var(--info)", DEBUG: "var(--debug)", TRACE: "var(--debug)" };
const STATUS_ORDER = ["Error", "Ok", "Unset"];
const STATUS_COLOR = { Error: "var(--error)", Ok: "var(--ok)", Unset: "var(--unset)" };
const SERVICE_SLOTS = ["var(--s1)", "var(--s2)", "var(--s3)", "var(--s4)", "var(--s5)", "var(--s6)"];
// A service keeps one color everywhere (web UI, TUI, every page): the slot is
// FNV-1a of the name, the same function the TUI uses.
function serviceColor(name) {
  let h = 0x811c9dc5;
  for (const b of new TextEncoder().encode(String(name))) h = Math.imul(h ^ b, 0x01000193) >>> 0;
  return SERVICE_SLOTS[h % SERVICE_SLOTS.length];
}

function route() {
  const raw = location.hash.replace(/^#/, "") || "/search";
  const [path, qs] = raw.split("?");
  return { path, params: new URLSearchParams(qs || "") };
}
function go(path, params) {
  const qs = params ? "?" + new URLSearchParams(params).toString() : "";
  location.hash = "#" + path + qs;
}
function currentRange(params) {
  const r = params.get("range") || localRange();
  return RANGES[r] ? r : "15m";
}
function localRange() {
  try { return localStorage.getItem("oscope.range") || "15m"; } catch { return "15m"; }
}
function window_(params) {
  if (params.get("from") && params.get("to")) return { from: +params.get("from"), to: +params.get("to"), zoomed: true };
  const to = Date.now();
  return { from: to - RANGES[currentRange(params)] * 60000, to, zoomed: false };
}

async function api(path, params = {}) {
  const url = path + "?" + new URLSearchParams(params).toString();
  const r = await fetch(url);
  const body = await r.json().catch(() => ({ error: `HTTP ${r.status}` }));
  if (!r.ok || body.error) throw new Error(body.error || `HTTP ${r.status}`);
  return body;
}

// ------------------------------------------------------------------ formatting

const pad = (n, w = 2) => String(n).padStart(w, "0");
function fmtTime(ms, withMs = true) {
  const d = new Date(Number(ms));
  const t = `${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`;
  return withMs ? `${t}.${pad(d.getMilliseconds(), 3)}` : t;
}
function fmtClock(ms) {
  const d = new Date(Number(ms));
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
function fmtDur(ms) {
  ms = Number(ms);
  if (ms < 1) return `${Math.round(ms * 1000)} µs`;
  if (ms < 1000) return `${ms < 10 ? ms.toFixed(2) : ms < 100 ? ms.toFixed(1) : Math.round(ms)} ms`;
  return `${(ms / 1000).toFixed(ms < 10000 ? 2 : 1)} s`;
}
const fmtInt = (n) => Number(n).toLocaleString("en-US");
function niceMax(v) {
  if (v <= 5) return 5;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}
function quoteValue(v) {
  return /^[\w.\-/{}@:*]+$/.test(v) && !v.includes(":") ? v : `"${String(v).replace(/"/g, "")}"`;
}

// ------------------------------------------------------------------ header

function syncHeader() {
  const { path, params } = route();
  const tab = path.startsWith("/services") ? "services" : "search";
  document.querySelectorAll("nav.tabs a").forEach((a) => a.setAttribute("aria-current", a.dataset.tab === tab ? "page" : "false"));
  const r = currentRange(params);
  const zoomed = params.get("from") && params.get("to");
  document.querySelectorAll(".range button").forEach((b) => b.setAttribute("aria-pressed", String(!zoomed && b.dataset.range === r)));
}
document.querySelectorAll(".range button").forEach((b) =>
  b.addEventListener("click", () => {
    const { path, params } = route();
    params.set("range", b.dataset.range);
    params.delete("from");
    params.delete("to");
    try { localStorage.setItem("oscope.range", b.dataset.range); } catch {}
    go(path.startsWith("/trace") ? "/search" : path, params);
  })
);
let liveTimer = null;
$(".live").addEventListener("click", (e) => {
  const on = e.currentTarget.getAttribute("aria-pressed") !== "true";
  e.currentTarget.setAttribute("aria-pressed", String(on));
  clearInterval(liveTimer);
  if (on) liveTimer = setInterval(() => { if (!route().path.startsWith("/trace")) render({ quiet: true }); }, 3000);
});
document.addEventListener("keydown", (e) => {
  if (e.key === "/" && document.activeElement?.tagName !== "INPUT") {
    e.preventDefault();
    $("#q")?.focus();
  }
  if (e.key === "Escape") closeDrawer();
});

// ------------------------------------------------------------------ drawer

function closeDrawer() {
  $("#drawer").hidden = true;
  document.querySelectorAll('[aria-selected="true"]').forEach((r) => r.setAttribute("aria-selected", "false"));
}
function openDrawer(title, body) {
  const d = $("#drawer");
  d.replaceChildren(
    h("div", { class: "dr-head" }, h("h3", {}, title), h("button", { onclick: closeDrawer, "aria-label": "Close details" }, "Esc")),
    h("div", { class: "dr-body" }, body)
  );
  d.hidden = false;
}
function kvList(obj, source, filterable = true) {
  const entries = Object.entries(obj || {}).sort(([a], [b]) => a.localeCompare(b));
  if (!entries.length) return h("div", { class: "muted" }, "none");
  return h("dl", { class: "kv" }, entries.map(([k, v]) => [
    h("dt", {}, k),
    h("dd", {}, String(v)),
    filterable
      ? h("span", { class: "acts" },
          h("button", { title: `Filter ${k}`, "aria-label": `Filter ${k}`, onclick: () => addTerm(`${k}:${quoteValue(String(v))}`, source) }, "+"),
          h("button", { title: `Exclude ${k}`, "aria-label": `Exclude ${k}`, onclick: () => addTerm(`-${k}:${quoteValue(String(v))}`, source) }, "−"))
      : h("span"),
  ]));
}
function addTerm(term, source) {
  const { params } = route();
  const q = (params.get("q") || "").trim();
  const terms = q ? q.split(/\s+(?=(?:[^"]*"[^"]*")*[^"]*$)/) : [];
  const bare = term.replace(/^-/, "");
  const next = terms.filter((t) => t !== term && t !== bare && t !== "-" + bare);
  const had = terms.includes(term);
  if (!had) next.push(term);
  params.set("q", next.join(" "));
  if (source) params.set("source", source);
  closeDrawer();
  go("/search", params);
}

// ------------------------------------------------------------------ search page

function histogramSvg(data, source, onZoom) {
  const groups = source === "logs" ? LEVEL_ORDER : STATUS_ORDER;
  const colors = source === "logs" ? LEVEL_COLOR : STATUS_COLOR;
  const step = data.step_ms;
  const start = Math.floor(data.from / step) * step;
  const n = Math.max(1, Math.ceil((data.to - start) / step));
  const cols = Array.from({ length: n }, (_, i) => ({ t: start + i * step, g: {} }));
  for (const b of data.buckets) {
    const i = Math.floor((Number(b.t) - start) / step);
    if (i >= 0 && i < n) cols[i].g[b.g] = (cols[i].g[b.g] || 0) + Number(b.c);
  }
  const totals = cols.map((c) => Object.values(c.g).reduce((a, b) => a + b, 0));
  const max = niceMax(Math.max(1, ...totals));
  const W = 1000, H = 120, L = 34, B = 18, T = 6;
  const bw = (W - L) / n;
  const s = svg("svg", { viewBox: `0 0 ${W} ${H}`, preserveAspectRatio: "none", role: "img", "aria-label": "Event volume over time" });
  for (const f of [0, 0.5, 1]) {
    const y = T + (H - B - T) * (1 - f);
    s.append(svg("line", { x1: L, x2: W, y1: y, y2: y, class: "grid" }));
    const lbl = svg("text", { x: L - 6, y: y + 3, "text-anchor": "end" });
    lbl.textContent = fmtInt(Math.round(max * f));
    s.append(lbl);
  }
  const labelEvery = Math.ceil(n / 6);
  cols.forEach((c, i) => {
    const x = L + i * bw;
    let y = H - B;
    const g = svg("g", { class: "bucket", "data-t": c.t });
    for (const grp of groups) {
      const v = c.g[grp] || 0;
      if (!v) continue;
      const hgt = ((H - B - T) * v) / max;
      g.append(svg("rect", { x: x + 1, y: y - hgt, width: Math.max(1, bw - 2), height: Math.max(1, hgt), fill: colors[grp], rx: 1 }));
      y -= hgt;
    }
    g.append(svg("rect", { x, y: T, width: bw, height: H - B - T, fill: "transparent" }));
    g.addEventListener("mousemove", (e) => showTip(e, c, groups, colors, step));
    g.addEventListener("mouseleave", hideTip);
    g.addEventListener("click", () => onZoom(c.t, c.t + step));
    s.append(g);
    if (i % labelEvery === 0) {
      const t = svg("text", { x: x + bw / 2, y: H - 4, "text-anchor": "middle" });
      t.textContent = step >= 60000 ? fmtClock(c.t) : fmtTime(c.t, false);
      s.append(t);
    }
  });
  return s;
}
function showTip(e, col, groups, colors, step) {
  const box = e.currentTarget.closest(".chart");
  let tip = $(".tip", box);
  if (!tip) box.append((tip = h("div", { class: "tip" })));
  const rows = groups.filter((g) => col.g[g]).map((g) => h("div", {}, h("i", { style: `background:${colors[g]}` }), `${g} ${fmtInt(col.g[g])}`));
  tip.replaceChildren(h("div", {}, h("b", {}, `${fmtTime(col.t, false)} – ${fmtTime(col.t + step, false)}`)), ...(rows.length ? rows : [h("div", {}, "no events")]));
  const r = box.getBoundingClientRect();
  tip.style.left = Math.min(e.clientX - r.left + 12, r.width - 180) + "px";
  tip.style.top = e.clientY - r.top + 8 + "px";
}
function hideTip() { document.querySelectorAll(".tip").forEach((t) => t.remove()); }

function levelVar(row, source) {
  if (source === "logs") return LEVEL_COLOR[String(row.level).toUpperCase()] || "transparent";
  return row.status === "Error" ? "var(--error)" : "transparent";
}

function renderRows(rows, source) {
  if (!rows.length) return h("div", { class: "empty", "data-testid": "no-results" }, "No results in this time range. Widen the range or loosen the query.");
  const head = source === "logs"
    ? h("tr", {}, h("th", { style: "width:112px" }, "Time"), h("th", { style: "width:120px" }, "Service"), h("th", { style: "width:70px" }, "Level"), h("th", {}, "Message"))
    : h("tr", {}, h("th", { style: "width:112px" }, "Time"), h("th", { style: "width:120px" }, "Service"), h("th", {}, "Span"), h("th", { style: "width:74px" }, "Kind"), h("th", { style: "width:70px" }, "Status"), h("th", { class: "num", style: "width:90px" }, "Duration"));
  const body = h("tbody", {}, rows.map((r) => {
    const cells = source === "logs"
      ? [h("td", { class: "t-time" }, fmtTime(r.ts)), h("td", {}, svcLabel(r.service)), h("td", {}, h("span", { class: `pill lv-${String(r.level).toUpperCase()}` }, r.level)), h("td", { title: r.body }, r.body)]
      : [h("td", { class: "t-time" }, fmtTime(r.ts)), h("td", {}, svcLabel(r.service)), h("td", { title: r.name }, r.name), h("td", {}, r.kind), h("td", {}, h("span", { class: `pill st-${r.status}` }, r.status)), h("td", { class: "num" }, fmtDur(r.duration_ms))];
    const tr = h("tr", { style: `--lvl:${levelVar(r, source)}`, "aria-selected": "false", "data-testid": "result-row" }, cells);
    tr.addEventListener("click", () => {
      document.querySelectorAll('tr[aria-selected="true"]').forEach((x) => x.setAttribute("aria-selected", "false"));
      tr.setAttribute("aria-selected", "true");
      showDetail(r, source);
    });
    return tr;
  }));
  return h("table", { class: "grid", "data-testid": "results" }, h("thead", {}, head), body);
}
function svcLabel(name) {
  return h("span", { class: "svc" }, h("i", { style: `background:${serviceColor(name)}` }), name);
}
function showDetail(r, source) {
  const fields = source === "logs"
    ? { time: new Date(Number(r.ts)).toISOString(), service: r.service, level: r.level, trace_id: r.trace_id || "—", span_id: r.span_id || "—" }
    : { time: new Date(Number(r.ts)).toISOString(), service: r.service, name: r.name, kind: r.kind, status: r.status, duration: fmtDur(r.duration_ms), trace_id: r.trace_id, span_id: r.span_id, parent_span_id: r.parent_span_id || "—" };
  const body = [];
  if (source === "logs") body.push(h("div", {}, h("h5", {}, "Message"), h("div", { class: "body-text" }, r.body)));
  if (r.status_message) body.push(h("div", {}, h("h5", {}, "Status message"), h("div", { class: "body-text" }, r.status_message)));
  if (r.trace_id) body.push(h("div", {}, h("button", { class: "btn", style: "padding:6px 14px", "data-testid": "open-trace", onclick: () => { closeDrawer(); go("/trace/" + r.trace_id); } }, "View trace →")));
  body.push(h("div", {}, h("h5", {}, "Fields"), kvList(fields, source, false)));
  body.push(h("div", {}, h("h5", {}, "Attributes"), kvList(r.attributes, source)));
  body.push(h("div", {}, h("h5", {}, "Resource"), kvList(r.resource, source)));
  openDrawer(source === "logs" ? r.body : r.name, body);
}

function renderFacets(facets, q, source) {
  const terms = new Set((q || "").split(/\s+/));
  return h("aside", { class: "facets", "data-testid": "facets" }, facets.filter((f) => f.values.length).map((f) => {
    const max = Math.max(...f.values.map((v) => Number(v.c)));
    return h("section", { class: "facet" }, h("h4", {}, f.field), f.values.map((v) => {
      const term = `${f.field}:${quoteValue(String(v.v))}`;
      return h("div", {
        class: "fv" + (terms.has(term) ? " on" : ""), role: "button", tabindex: "0",
        title: `${term}  (shift-click to exclude)`, "data-term": term,
        onclick: (e) => addTerm(e.shiftKey ? "-" + term : term, source),
        onkeydown: (e) => { if (e.key === "Enter") addTerm(term, source); },
      }, h("div", { class: "bar", style: `width:${(100 * Number(v.c)) / max}%` }), h("span", {}, v.v === "" ? "(empty)" : v.v), h("em", {}, fmtInt(v.c)));
    }));
  }));
}

let searchSeq = 0;
async function renderSearch(params, opts) {
  const source = params.get("source") === "traces" ? "traces" : "logs";
  const q = params.get("q") || "";
  const w = window_(params);
  const seq = ++searchSeq;
  const view = $("#view");
  const input = h("input", { id: "q", value: q, spellcheck: "false", autocomplete: "off", "aria-label": "Search query", "data-testid": "query",
    placeholder: source === "logs" ? 'service:checkout level:error "timeout"' : "service:payments status:error duration:>500" });
  const submit = (e) => { e?.preventDefault(); params.set("q", input.value.trim()); go("/search", params); };
  const bar = h("form", { class: "searchbar", onsubmit: submit },
    h("div", { class: "seg", role: "group", "aria-label": "Source" },
      ...["logs", "traces"].map((s) => h("button", { type: "button", "aria-pressed": String(s === source), "data-testid": `source-${s}`,
        onclick: () => { params.set("source", s); params.set("q", ""); go("/search", params); } }, s === "logs" ? "Logs" : "Traces"))),
    h("label", { class: "q" }, h("span", {}, ">"), input, h("kbd", {}, "/")),
    h("button", { class: "btn", type: "submit", "data-testid": "run" }, "Search"));
  const shell = opts.quiet ? null : h("div", { class: "page" }, bar, h("div", { class: "chart", "data-testid": "histogram" }, h("div", { class: "empty" }, "Loading…")), h("div", { class: "work" }, h("aside", { class: "facets" }), h("section", { class: "results" }, h("div", { class: "empty" }, "Loading…"))));
  if (shell) view.replaceChildren(shell);
  const base = { source, q, from: w.from, to: w.to };
  let res, hist, fac;
  try {
    [res, hist, fac] = await Promise.all([api("/api/search", { ...base, limit: 200 }), api("/api/histogram", base), api("/api/facets", base)]);
  } catch (err) {
    if (seq !== searchSeq) return;
    view.replaceChildren(h("div", { class: "page" }, bar, h("div", { class: "error-banner", "data-testid": "query-error" }, err.message)));
    return;
  }
  if (seq !== searchSeq) return;
  const zoom = (from, to) => { params.set("from", from); params.set("to", to); go("/search", params); };
  const legendGroups = (source === "logs" ? LEVEL_ORDER : STATUS_ORDER).filter((g) => hist.buckets.some((b) => b.g === g));
  const chart = h("div", { class: "chart", "data-testid": "histogram" },
    h("div", { class: "chart-head" },
      h("b", { "data-testid": "total" }, fmtInt(res.total)), h("span", { class: "muted" }, `${source === "logs" ? "log events" : "spans"} · ${fmtTime(w.from, false)} – ${fmtTime(w.to, false)}`),
      w.zoomed ? h("a", { href: "#", onclick: (e) => { e.preventDefault(); params.delete("from"); params.delete("to"); go("/search", params); } }, "reset zoom") : null,
      h("span", { class: "legend" }, legendGroups.map((g) => h("span", {}, h("i", { style: `background:${(source === "logs" ? LEVEL_COLOR : STATUS_COLOR)[g]}` }), g)))),
    histogramSvg(hist, source, zoom));
  const results = h("section", { class: "results" }, renderRows(res.rows, source),
    res.total > res.rows.length ? h("div", { class: "more" }, `Showing the newest ${fmtInt(res.rows.length)} of ${fmtInt(res.total)}. Narrow the query or zoom the chart to see the rest.`) : null);
  const page = h("div", { class: "page" }, bar, chart, h("div", { class: "work" }, renderFacets(fac.facets, q, source), results));
  const scroll = view.scrollTop;
  const focused = document.activeElement?.id === "q";
  view.replaceChildren(page);
  if (opts.quiet) view.scrollTop = scroll;
  if (focused) $("#q").focus();
}

// ------------------------------------------------------------------ trace page

async function renderTrace(id) {
  const view = $("#view");
  view.replaceChildren(h("div", { class: "page" }, h("div", { class: "empty" }, "Loading trace…")));
  let t;
  try { t = await api("/api/trace/" + encodeURIComponent(id)); }
  catch (err) { view.replaceChildren(h("div", { class: "page" }, h("div", { class: "error-banner" }, err.message))); return; }
  if (!t.spans.length) { view.replaceChildren(h("div", { class: "page" }, h("div", { class: "empty" }, "No spans for this trace in the store."))); return; }
  const spans = t.spans.map((s) => ({ ...s, start: Number(s.start_ns), dur: Number(s.dur_ns), kids: [] }));
  const byId = new Map(spans.map((s) => [s.span_id, s]));
  const roots = [];
  for (const s of spans) (byId.has(s.parent_span_id) ? byId.get(s.parent_span_id).kids : roots).push(s);
  const flat = [];
  const walk = (s, d) => { flat.push({ s, d }); s.kids.sort((a, b) => a.start - b.start).forEach((k) => walk(k, d + 1)); };
  roots.sort((a, b) => a.start - b.start).forEach((r) => walk(r, 0));
  const t0 = Math.min(...spans.map((s) => s.start));
  const t1 = Math.max(...spans.map((s) => s.start + s.dur));
  const total = Math.max(1, t1 - t0);
  const logsBySpan = new Map();
  for (const l of t.logs) (logsBySpan.get(l.span_id) || logsBySpan.set(l.span_id, []).get(l.span_id)).push(l);
  const services = [...new Set(spans.map((s) => s.service))].sort();
  const errors = spans.filter((s) => s.status === "Error").length;
  const pct = (ns) => (100 * (ns - t0)) / total;

  const head = h("div", { class: "trace-head", "data-testid": "trace-head" },
    h("h2", {}, flat[0].s.name),
    h("div", { class: "stat" }, h("b", {}, fmtDur(total / 1e6)), h("small", {}, "duration")),
    h("div", { class: "stat" }, h("b", {}, spans.length), h("small", {}, "spans")),
    h("div", { class: "stat" }, h("b", { style: errors ? "color:var(--error)" : "" }, errors), h("small", {}, "errors")),
    h("div", { class: "stat" }, h("b", {}, t.logs.length), h("small", {}, "logs")),
    h("div", { class: "chips" }, services.map((s) => h("span", { class: "chip" }, h("i", { style: `background:${serviceColor(s)}` }), s))),
    h("span", { class: "spacer", style: "flex:1" }),
    h("code", { style: "color:var(--muted);font-size:11px" }, id));

  const ticks = [0, 0.25, 0.5, 0.75, 1];
  const header = h("div", { class: "wf-row head" }, h("div", { class: "wf-name" }, "Span"),
    h("div", { class: "wf-tl" }, ticks.map((f) => h("div", { class: "tick", style: `left:${f * 100}%` }, h("span", { style: f === 1 ? "left:auto;right:4px" : "" }, f === 0 ? "0" : fmtDur((total * f) / 1e6))))));
  const rows = flat.map(({ s, d }) => {
    const left = pct(s.start), width = Math.max(0.15, (100 * s.dur) / total);
    const durRight = left + width > 82;
    const row = h("div", { class: "wf-row", "aria-selected": "false", "data-testid": "span-row", "data-span": s.span_id },
      h("div", { class: "wf-name", style: `padding-left:${10 + d * 16}px` },
        h("i", { style: `width:8px;height:8px;border-radius:2px;flex:none;background:${serviceColor(s.service)}` }),
        s.status === "Error" ? h("span", { class: "err", title: s.status_message || "error" }, "!") : null,
        h("span", { class: "nm" }, s.name), h("small", {}, s.service)),
      h("div", { class: "wf-tl" },
        h("div", { class: "wf-bar" + (s.status === "Error" ? " err" : ""), style: `left:${left}%;width:${width}%;background:${serviceColor(s.service)}` }),
        h("span", { class: "wf-dur", style: durRight ? `right:${100 - left + 0.5}%` : `left:${left + width + 0.5}%` }, fmtDur(s.dur / 1e6)),
        (logsBySpan.get(s.span_id) || []).map((l) => h("span", { class: "wf-log", title: `${l.level}: ${l.body}`, style: `left:${pct(Number(l.ts_ns))}%;background:${LEVEL_COLOR[String(l.level).toUpperCase()] || "var(--debug)"}` }))));
    row.addEventListener("click", () => {
      document.querySelectorAll('.wf-row[aria-selected="true"]').forEach((x) => x.setAttribute("aria-selected", "false"));
      row.setAttribute("aria-selected", "true");
      const logs = logsBySpan.get(s.span_id) || [];
      openDrawer(s.name, [
        h("div", {}, h("h5", {}, "Span"), kvList({ service: s.service, kind: s.kind, status: s.status, duration: fmtDur(s.dur / 1e6), offset: fmtDur((s.start - t0) / 1e6), span_id: s.span_id, parent_span_id: s.parent_span_id || "—" }, "traces", false)),
        s.status_message ? h("div", {}, h("h5", {}, "Status message"), h("div", { class: "body-text" }, s.status_message)) : null,
        h("div", {}, h("h5", {}, "Attributes"), kvList(s.attributes, "traces")),
        logs.length ? h("div", {}, h("h5", {}, `Logs (${logs.length})`), logs.map((l) => h("div", { class: "body-text", style: "margin-bottom:6px" }, `${l.level}  ${l.body}`))) : null,
      ]);
    });
    return row;
  });
  const logTable = t.logs.length
    ? h("table", { class: "grid", "data-testid": "trace-logs" },
        h("thead", {}, h("tr", {}, h("th", { style: "width:90px" }, "Offset"), h("th", { style: "width:120px" }, "Service"), h("th", { style: "width:70px" }, "Level"), h("th", {}, "Message"))),
        h("tbody", {}, t.logs.map((l) => h("tr", { style: `--lvl:${LEVEL_COLOR[String(l.level).toUpperCase()] || "transparent"}`,
          onclick: () => document.querySelector(`.wf-row[data-span="${l.span_id}"]`)?.click() },
          h("td", { class: "t-time" }, "+" + fmtDur((Number(l.ts_ns) - t0) / 1e6)), h("td", {}, svcLabel(l.service)),
          h("td", {}, h("span", { class: `pill lv-${String(l.level).toUpperCase()}` }, l.level)), h("td", { title: l.body }, l.body)))))
    : h("div", { class: "empty" }, "No logs were recorded in this trace.");
  view.replaceChildren(h("div", { class: "page" },
    h("div", {}, h("a", { href: "#", onclick: (e) => { e.preventDefault(); history.back(); } }, "← back")),
    head, h("div", { class: "wf", "data-testid": "waterfall" }, header, rows),
    h("div", { class: "logs-panel" }, h("h4", {}, "Logs in this trace"), logTable)));
}

// ------------------------------------------------------------------ services page

function sparkline(points, key, color, max) {
  const W = 140, H = 28;
  const s = svg("svg", { width: W, height: H, viewBox: `0 0 ${W} ${H}`, class: "spark", role: "img", "aria-label": key });
  if (points.length < 2) return s;
  const m = max || Math.max(1e-9, ...points.map((p) => Number(p[key])));
  const xy = points.map((p, i) => [(i / (points.length - 1)) * (W - 4) + 2, H - 3 - ((H - 6) * Number(p[key])) / m]);
  const d = xy.map(([x, y], i) => `${i ? "L" : "M"}${x.toFixed(1)},${y.toFixed(1)}`).join("");
  s.append(svg("path", { d: `${d}L${xy.at(-1)[0]},${H - 2}L${xy[0][0]},${H - 2}Z`, fill: color, opacity: "0.12" }));
  s.append(svg("path", { d, fill: "none", stroke: color, "stroke-width": "1.6", "stroke-linejoin": "round" }));
  const [ex, ey] = xy.at(-1);
  s.append(svg("circle", { cx: ex, cy: ey, r: 2.4, fill: color }));
  return s;
}
function rateClass(r) { return r >= 0.05 ? "rate-bad" : r >= 0.01 ? "rate-warn" : "rate-ok"; }

async function renderServices(params) {
  const view = $("#view");
  const w = window_(params);
  if (!view.querySelector("[data-testid=services]")) view.replaceChildren(h("div", { class: "page" }, h("div", { class: "empty" }, "Loading…")));
  let d;
  try { d = await api("/api/services", { from: w.from, to: w.to }); }
  catch (err) { view.replaceChildren(h("div", { class: "page" }, h("div", { class: "error-banner" }, err.message))); return; }
  const minutes = (w.to - w.from) / 60000;
  const series = new Map();
  for (const p of d.series) (series.get(p.service) || series.set(p.service, []).get(p.service)).push(p);
  const eps = new Map();
  for (const e of d.endpoints) (eps.get(e.service) || eps.set(e.service, []).get(e.service)).push(e);
  const open = new Set((params.get("open") || "").split(",").filter(Boolean));
  const body = h("tbody", {}, d.services.flatMap((s) => {
    const req = Number(s.requests), err = Number(s.errors), rate = req ? err / req : 0;
    const pts = series.get(s.service) || [];
    const row = h("tr", { "data-testid": "service-row", "data-service": s.service, onclick: () => {
      open.has(s.service) ? open.delete(s.service) : open.add(s.service);
      params.set("open", [...open].join(",")); go("/services", params);
    } },
      h("td", {}, svcLabel(s.service)),
      h("td", { class: "num" }, fmtInt(req)),
      h("td", { class: "num" }, (req / minutes).toFixed(1)),
      h("td", { class: "num" }, h("span", { class: "pill " + rateClass(rate) }, (rate * 100).toFixed(2) + "%")),
      h("td", { class: "num" }, fmtDur(s.p50_ms)), h("td", { class: "num" }, fmtDur(s.p95_ms)), h("td", { class: "num" }, fmtDur(s.p99_ms)),
      h("td", {}, sparkline(pts, "req", serviceColor(s.service))),
      h("td", {}, sparkline(pts, "p95_ms", "var(--muted)")),
      h("td", {}, h("a", { href: "#/search?" + new URLSearchParams({ source: "traces", q: `service:${s.service} kind:server`, range: currentRange(params) }), onclick: (e) => e.stopPropagation() }, "spans →")));
    const extra = open.has(s.service)
      ? (eps.get(s.service) || []).map((e) => h("tr", { class: "endpoints" },
          h("td", {}, e.name), h("td", { class: "num" }, fmtInt(e.requests)), h("td", { class: "num" }, (Number(e.requests) / minutes).toFixed(1)),
          h("td", { class: "num" }, h("span", { class: "pill " + rateClass(Number(e.errors) / Math.max(1, Number(e.requests))) }, ((100 * Number(e.errors)) / Math.max(1, Number(e.requests))).toFixed(2) + "%")),
          h("td", {}), h("td", { class: "num" }, fmtDur(e.p95_ms)), h("td", {}), h("td", {}), h("td", {}),
          h("td", {}, h("a", { href: "#/search?" + new URLSearchParams({ source: "traces", q: `service:${s.service} name:"${e.name}"`, range: currentRange(params) }), onclick: (ev) => ev.stopPropagation() }, "spans →"))))
      : [];
    return [row, ...extra];
  }));
  const table = h("section", { class: "results", "data-testid": "services" },
    h("table", { class: "grid svc-table" },
      h("thead", {}, h("tr", {},
        h("th", { style: "width:150px" }, "Service"), h("th", { class: "num", style: "width:90px" }, "Requests"), h("th", { class: "num", style: "width:80px" }, "Req/min"),
        h("th", { class: "num", style: "width:90px" }, "Errors"), h("th", { class: "num", style: "width:80px" }, "p50"), h("th", { class: "num", style: "width:80px" }, "p95"), h("th", { class: "num", style: "width:80px" }, "p99"),
        h("th", { style: "width:156px" }, "Request rate"), h("th", { style: "width:156px" }, "p95 latency"), h("th", { style: "width:80px" }, ""))),
      body));
  view.replaceChildren(h("div", { class: "page" },
    h("div", { class: "section-title" }, h("h2", {}, "Services"), h("span", {}, `server spans · ${fmtTime(w.from, false)} – ${fmtTime(w.to, false)} · click a row for its endpoints`)),
    table));
}

// ------------------------------------------------------------------ router

async function render(opts = {}) {
  syncHeader();
  hideTip();
  const { path, params } = route();
  if (!opts.quiet) closeDrawer();
  if (path.startsWith("/trace/")) return renderTrace(decodeURIComponent(path.slice(7)));
  if (path.startsWith("/services")) return renderServices(params);
  return renderSearch(params, opts);
}
window.addEventListener("hashchange", () => render());
render();
