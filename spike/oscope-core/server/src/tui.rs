//! oscope-tui: a terminal client of the oscope HTTP API.
//!
//!   oscope-tui [--url http://127.0.0.1:8080]
//!   oscope-tui --url URL --snapshot out.html [--size 140x42] [--keys "k1 k2 ..."]
//!
//! Keys: / edit query (Enter runs, Esc cancels) · Tab logs/traces · 1-5 range ·
//! ↑/↓ or j/k select · Enter details · t open trace · s services · l search ·
//! L live refresh · Esc back · q quit.
//!
//! --snapshot replays a key script against a headless backend and writes the
//! final frame as HTML, so docs and tests get a real rendering without a
//! terminal. Key tokens: <enter> <esc> <tab> <down> <up> <space>, or literal
//! text (typed character by character).

use crossterm::event::{self, Event, KeyCode, KeyEvent, KeyEventKind, KeyModifiers};
use ratatui::{
    backend::{Backend, CrosstermBackend, TestBackend},
    buffer::Buffer,
    layout::{Constraint, Direction, Layout, Rect},
    style::{Color, Modifier, Style},
    text::{Line, Span},
    widgets::{Block, Borders, Cell, Clear, Paragraph, Row, Sparkline, Table, TableState, Wrap},
    Frame, Terminal,
};
use serde_json::Value;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

const RANGES: [(&str, i64); 5] = [("5m", 5), ("15m", 15), ("1h", 60), ("6h", 360), ("24h", 1440)];
const BG: Color = Color::Rgb(14, 17, 22);
const PANEL: Color = Color::Rgb(21, 26, 33);
const BORDER: Color = Color::Rgb(38, 46, 57);
const TEXT: Color = Color::Rgb(217, 223, 231);
const MUTED: Color = Color::Rgb(139, 150, 165);
const ACCENT: Color = Color::Rgb(79, 179, 166);
const ERROR: Color = Color::Rgb(229, 72, 77);
const WARN: Color = Color::Rgb(240, 160, 50);
const INFO: Color = Color::Rgb(62, 143, 214);
const DEBUG: Color = Color::Rgb(107, 119, 133);
const OK: Color = Color::Rgb(48, 164, 108);
const SERVICES: [Color; 6] = [
    Color::Rgb(57, 135, 229),
    Color::Rgb(217, 89, 38),
    Color::Rgb(25, 158, 112),
    Color::Rgb(201, 133, 0),
    Color::Rgb(213, 81, 129),
    Color::Rgb(144, 133, 233),
];

#[derive(Clone, Copy, PartialEq)]
enum Screen {
    Search,
    Trace,
    Services,
}

struct App {
    url: String,
    screen: Screen,
    traces: bool,
    query: String,
    editing: bool,
    range: usize,
    rows: Vec<Value>,
    total: u64,
    hist: Vec<u64>,
    hist_err: Vec<u64>,
    table: TableState,
    detail: bool,
    trace: Option<Value>,
    trace_sel: usize,
    services: Option<Value>,
    live: bool,
    message: String,
}

fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}
fn s(v: &Value, k: &str) -> String {
    match v.get(k) {
        Some(Value::String(x)) => x.clone(),
        Some(Value::Null) | None => String::new(),
        Some(x) => x.to_string(),
    }
}
fn n(v: &Value, k: &str) -> f64 {
    match v.get(k) {
        Some(Value::String(x)) => x.parse().unwrap_or(0.0),
        Some(x) => x.as_f64().unwrap_or(0.0),
        None => 0.0,
    }
}
fn fmt_time(ms: i64) -> String {
    // local time is not needed in a terminal client; UTC keeps snapshots stable across hosts
    let secs = ms.div_euclid(1000);
    let (h, m, sec) = ((secs / 3600) % 24, (secs / 60) % 60, secs % 60);
    format!("{h:02}:{m:02}:{sec:02}.{:03}", ms.rem_euclid(1000))
}
fn fmt_dur(ms: f64) -> String {
    if ms < 1.0 {
        format!("{:.0} µs", ms * 1000.0)
    } else if ms < 1000.0 {
        if ms < 10.0 { format!("{ms:.2} ms") } else if ms < 100.0 { format!("{ms:.1} ms") } else { format!("{ms:.0} ms") }
    } else {
        format!("{:.2} s", ms / 1000.0)
    }
}
fn level_color(l: &str) -> Color {
    match l.to_ascii_uppercase().as_str() {
        "ERROR" | "FATAL" => ERROR,
        "WARN" => WARN,
        "INFO" => INFO,
        _ => DEBUG,
    }
}

impl App {
    fn new(url: String) -> App {
        App {
            url,
            screen: Screen::Search,
            traces: false,
            query: String::new(),
            editing: false,
            range: 2,
            rows: vec![],
            total: 0,
            hist: vec![],
            hist_err: vec![],
            table: TableState::default().with_selected(Some(0)),
            detail: false,
            trace: None,
            trace_sel: 0,
            services: None,
            live: false,
            message: String::new(),
        }
    }

    /// A service keeps one color everywhere: FNV-1a of the name picks the
    /// slot, the same function the web UI uses.
    fn service_color(&mut self, name: &str) -> Color {
        let mut h: u32 = 0x811c9dc5;
        for b in name.bytes() {
            h = (h ^ b as u32).wrapping_mul(0x01000193);
        }
        SERVICES[(h % SERVICES.len() as u32) as usize]
    }

    fn window(&self) -> (i64, i64) {
        let to = now_ms();
        (to - RANGES[self.range].1 * 60_000, to)
    }

    fn get(&self, path: &str, params: &[(&str, String)]) -> Result<Value, String> {
        let mut req = ureq::get(&format!("{}{}", self.url, path));
        for (k, v) in params {
            req = req.query(k, v);
        }
        match req.call() {
            Ok(r) => r.into_json::<Value>().map_err(|e| e.to_string()),
            Err(ureq::Error::Status(_, r)) => {
                let v: Value = r.into_json().unwrap_or(Value::Null);
                Err(v.get("error").and_then(|e| e.as_str()).unwrap_or("request failed").to_string())
            }
            Err(e) => Err(e.to_string()),
        }
    }

    fn refresh(&mut self) {
        self.message.clear();
        let r = match self.screen {
            Screen::Services => self.load_services(),
            Screen::Trace => Ok(()),
            Screen::Search => self.load_search(),
        };
        if let Err(e) = r {
            self.message = e;
        }
    }

    fn load_services(&mut self) -> Result<(), String> {
        let (from, to) = self.window();
        let d = self.get("/api/services", &[("from", from.to_string()), ("to", to.to_string())])?;
        self.services = Some(d);
        Ok(())
    }

    fn load_search(&mut self) -> Result<(), String> {
        let (from, to) = self.window();
        let src = if self.traces { "traces" } else { "logs" };
        let p = [("source", src.to_string()), ("q", self.query.clone()), ("from", from.to_string()), ("to", to.to_string())];
        let res = self.get("/api/search", &[&p[..], &[("limit", "300".to_string())]].concat())?;
        let hist = self.get("/api/histogram", &p)?;
        self.rows = res["rows"].as_array().cloned().unwrap_or_default();
        self.total = n(&res, "total") as u64;
        let step = n(&hist, "step_ms") as i64;
        let start = (from / step.max(1)) * step.max(1);
        let buckets = (((to - start) as f64) / step.max(1) as f64).ceil() as usize;
        self.hist = vec![0; buckets.max(1)];
        self.hist_err = vec![0; buckets.max(1)];
        for b in hist["buckets"].as_array().into_iter().flatten() {
            let i = ((n(b, "t") as i64 - start) / step.max(1)) as usize;
            if i < self.hist.len() {
                let c = n(b, "c") as u64;
                self.hist[i] += c;
                if matches!(s(b, "g").as_str(), "ERROR" | "FATAL" | "Error") {
                    self.hist_err[i] += c;
                }
            }
        }
        let sel = self.table.selected().unwrap_or(0).min(self.rows.len().saturating_sub(1));
        self.table.select(Some(sel));
        Ok(())
    }

    fn open_trace(&mut self) {
        let Some(row) = self.table.selected().and_then(|i| self.rows.get(i)) else { return };
        let id = s(row, "trace_id");
        if id.is_empty() {
            self.message = "this event has no trace".into();
            return;
        }
        match self.get(&format!("/api/trace/{id}"), &[]) {
            Ok(t) => {
                self.trace = Some(t);
                self.trace_sel = 0;
                self.screen = Screen::Trace;
                self.detail = false;
            }
            Err(e) => self.message = e,
        }
    }

    /// Returns false when the app should quit.
    fn key(&mut self, k: KeyEvent) -> bool {
        if k.kind != KeyEventKind::Press {
            return true;
        }
        if self.editing {
            match k.code {
                KeyCode::Enter => {
                    self.editing = false;
                    self.table.select(Some(0));
                    self.refresh();
                }
                KeyCode::Esc => self.editing = false,
                KeyCode::Backspace => {
                    self.query.pop();
                }
                KeyCode::Char(c) => self.query.push(c),
                _ => {}
            }
            return true;
        }
        match k.code {
            KeyCode::Char('q') => return false,
            KeyCode::Char('c') if k.modifiers.contains(KeyModifiers::CONTROL) => return false,
            KeyCode::Char('/') => {
                self.screen = Screen::Search;
                self.editing = true;
            }
            KeyCode::Char('s') => {
                self.screen = Screen::Services;
                self.detail = false;
                self.refresh();
            }
            KeyCode::Char('l') => {
                self.screen = Screen::Search;
                self.refresh();
            }
            KeyCode::Char('L') => self.live = !self.live,
            KeyCode::Tab if self.screen == Screen::Search => {
                self.traces = !self.traces;
                self.query.clear();
                self.table.select(Some(0));
                self.refresh();
            }
            KeyCode::Char(c @ '1'..='5') => {
                self.range = (c as usize) - ('1' as usize);
                self.refresh();
            }
            KeyCode::Down | KeyCode::Char('j') => self.step(1),
            KeyCode::Up | KeyCode::Char('k') => self.step(-1),
            KeyCode::Enter => self.detail = !self.detail,
            KeyCode::Char('t') if self.screen == Screen::Search => self.open_trace(),
            KeyCode::Esc => {
                if self.detail {
                    self.detail = false;
                } else if self.screen == Screen::Trace {
                    self.screen = Screen::Search;
                }
            }
            _ => {}
        }
        true
    }

    fn step(&mut self, d: i64) {
        if self.screen == Screen::Trace {
            let len = self.trace.as_ref().and_then(|t| t["spans"].as_array()).map(|a| a.len()).unwrap_or(0);
            self.trace_sel = (self.trace_sel as i64 + d).clamp(0, len.saturating_sub(1) as i64) as usize;
        } else {
            let len = self.rows.len();
            let i = (self.table.selected().unwrap_or(0) as i64 + d).clamp(0, len.saturating_sub(1) as i64) as usize;
            self.table.select(Some(i));
        }
    }
}

// ------------------------------------------------------------------ rendering

fn block(title: impl AsRef<str>) -> Block<'static> {
    let title = title.as_ref();
    Block::default()
        .borders(Borders::ALL)
        .border_style(Style::new().fg(BORDER))
        .title(Span::styled(format!(" {title} "), Style::new().fg(MUTED)))
        .style(Style::new().bg(PANEL).fg(TEXT))
}

fn draw(f: &mut Frame, app: &mut App) {
    let area = f.area();
    f.render_widget(Block::default().style(Style::new().bg(BG).fg(TEXT)), area);
    let rows = Layout::default().direction(Direction::Vertical).constraints([Constraint::Length(1), Constraint::Min(0), Constraint::Length(1)]).split(area);
    header(f, app, rows[0]);
    match app.screen {
        Screen::Search => search(f, app, rows[1]),
        Screen::Trace => trace(f, app, rows[1]),
        Screen::Services => services(f, app, rows[1]),
    }
    footer(f, app, rows[2]);
}

fn header(f: &mut Frame, app: &App, r: Rect) {
    let tab = |name: &str, on: bool| {
        if on { Span::styled(format!(" {name} "), Style::new().fg(BG).bg(ACCENT).add_modifier(Modifier::BOLD)) } else { Span::styled(format!(" {name} "), Style::new().fg(MUTED)) }
    };
    let mut spans = vec![
        Span::styled(" ● oscope ", Style::new().fg(ACCENT).add_modifier(Modifier::BOLD)),
        tab("Search", app.screen != Screen::Services),
        Span::raw(" "),
        tab("Services", app.screen == Screen::Services),
        Span::raw("   "),
    ];
    for (i, (name, _)) in RANGES.iter().enumerate() {
        spans.push(if i == app.range { Span::styled(format!("[{name}]"), Style::new().fg(TEXT).add_modifier(Modifier::BOLD)) } else { Span::styled(format!(" {name} "), Style::new().fg(MUTED)) });
    }
    spans.push(Span::raw("   "));
    spans.push(if app.live { Span::styled("● live", Style::new().fg(OK)) } else { Span::styled("○ live", Style::new().fg(MUTED)) });
    f.render_widget(Paragraph::new(Line::from(spans)).style(Style::new().bg(PANEL)), r);
}

fn footer(f: &mut Frame, app: &App, r: Rect) {
    let help = match (app.editing, app.screen) {
        (true, _) => "type a query · Enter run · Esc cancel",
        (_, Screen::Search) => "/ query · Tab logs/traces · 1-5 range · ↑↓ select · Enter details · t trace · s services · L live · q quit",
        (_, Screen::Trace) => "↑↓ select span · Enter details · Esc back · q quit",
        (_, Screen::Services) => "l search · 1-5 range · L live · q quit",
    };
    let line = if app.message.is_empty() {
        Line::from(Span::styled(format!(" {help}"), Style::new().fg(MUTED)))
    } else {
        Line::from(Span::styled(format!(" {}", app.message), Style::new().fg(ERROR)))
    };
    f.render_widget(Paragraph::new(line).style(Style::new().bg(PANEL)), r);
}

fn search(f: &mut Frame, app: &mut App, r: Rect) {
    let parts = Layout::default().direction(Direction::Vertical).constraints([Constraint::Length(3), Constraint::Length(6), Constraint::Min(0)]).split(r);
    let src = if app.traces { "traces" } else { "logs" };
    let cursor = if app.editing { "▏" } else { "" };
    let q = if app.query.is_empty() && !app.editing {
        Span::styled(if app.traces { "service:payments status:error duration:>500" } else { "service:checkout level:error \"timeout\"" }, Style::new().fg(DEBUG))
    } else {
        Span::styled(format!("{}{cursor}", app.query), Style::new().fg(TEXT))
    };
    let qb = block(&format!("{src} ›  /")).border_style(Style::new().fg(if app.editing { ACCENT } else { BORDER }));
    f.render_widget(Paragraph::new(Line::from(vec![Span::styled("> ", Style::new().fg(DEBUG)), q])).block(qb), parts[0]);

    // volume: errors drawn on top of the total so they read at a glance
    let hb = block(&format!("{} {} · errors in red", app.total, if app.traces { "spans" } else { "events" }));
    let inner = hb.inner(parts[1]);
    f.render_widget(hb, parts[1]);
    let w = inner.width as usize;
    let squeeze = |v: &Vec<u64>| -> Vec<u64> {
        if v.is_empty() || w == 0 {
            return vec![];
        }
        (0..w).map(|i| v[i * v.len() / w]).collect()
    };
    let (tot, err) = (squeeze(&app.hist), squeeze(&app.hist_err));
    let max = tot.iter().copied().max().unwrap_or(1).max(1);
    let halves = Layout::default().direction(Direction::Vertical).constraints([Constraint::Length(inner.height.saturating_sub(1)), Constraint::Length(1)]).split(inner);
    f.render_widget(Sparkline::default().data(&tot).max(max).style(Style::new().fg(INFO).bg(PANEL)), halves[0]);
    f.render_widget(Sparkline::default().data(&err).max(err.iter().copied().max().unwrap_or(1).max(1)).style(Style::new().fg(ERROR).bg(PANEL)), halves[1]);

    let body = if app.detail {
        Layout::default().direction(Direction::Horizontal).constraints([Constraint::Percentage(55), Constraint::Percentage(45)]).split(parts[2])
    } else {
        Layout::default().direction(Direction::Horizontal).constraints([Constraint::Percentage(100)]).split(parts[2])
    };
    let rows_src = app.rows.clone();
    let rows: Vec<Row> = rows_src
        .iter()
        .map(|r| {
            let svc = s(r, "service");
            let color = app.service_color(&svc);
            let t = fmt_time(n(r, "ts") as i64);
            if app.traces {
                let st = s(r, "status");
                Row::new(vec![
                    Cell::from(Span::styled(t, Style::new().fg(MUTED))),
                    Cell::from(Line::from(vec![Span::styled("■ ", Style::new().fg(color)), Span::raw(svc)])),
                    Cell::from(s(r, "name")),
                    Cell::from(Span::styled(st.clone(), Style::new().fg(if st == "Error" { ERROR } else { MUTED }))),
                    Cell::from(Line::from(fmt_dur(n(r, "duration_ms"))).right_aligned()),
                ])
            } else {
                let lvl = s(r, "level");
                Row::new(vec![
                    Cell::from(Span::styled(t, Style::new().fg(MUTED))),
                    Cell::from(Line::from(vec![Span::styled("■ ", Style::new().fg(color)), Span::raw(svc)])),
                    Cell::from(Span::styled(lvl.clone(), Style::new().fg(level_color(&lvl)).add_modifier(Modifier::BOLD))),
                    Cell::from(s(r, "body")),
                ])
            }
        })
        .collect();
    let widths: Vec<Constraint> = if app.traces {
        vec![Constraint::Length(12), Constraint::Length(12), Constraint::Min(20), Constraint::Length(6), Constraint::Length(10)]
    } else {
        vec![Constraint::Length(12), Constraint::Length(12), Constraint::Length(6), Constraint::Min(20)]
    };
    let header = if app.traces { vec!["time", "service", "span", "status", "duration"] } else { vec!["time", "service", "level", "message"] };
    let title = format!("{} of {}", rows.len(), app.total);
    let table = Table::new(rows, widths)
        .header(Row::new(header).style(Style::new().fg(MUTED).add_modifier(Modifier::BOLD)))
        .block(block(&title))
        .row_highlight_style(Style::new().bg(Color::Rgb(27, 45, 47)))
        .highlight_symbol("▌");
    f.render_stateful_widget(table, body[0], &mut app.table);

    if app.detail {
        if let Some(r) = app.table.selected().and_then(|i| app.rows.get(i)).cloned() {
            detail(f, &r, body[1], app.traces);
        }
    }
    if app.rows.is_empty() && app.message.is_empty() {
        let a = parts[2];
        let msg = Rect { x: a.x + 2, y: a.y + 2, width: a.width.saturating_sub(4), height: 1 };
        f.render_widget(Paragraph::new("No results in this time range.").style(Style::new().fg(MUTED).bg(PANEL)), msg);
    }
}

fn detail(f: &mut Frame, r: &Value, area: Rect, traces: bool) {
    let mut lines: Vec<Line> = vec![];
    let field = |k: &str, v: String| Line::from(vec![Span::styled(format!("{k:<18}"), Style::new().fg(MUTED)), Span::raw(v)]);
    if traces {
        lines.push(Line::from(Span::styled(s(r, "name"), Style::new().add_modifier(Modifier::BOLD))));
        lines.push(field("status", s(r, "status")));
        lines.push(field("duration", fmt_dur(n(r, "duration_ms"))));
        if !s(r, "status_message").is_empty() {
            lines.push(field("status message", s(r, "status_message")));
        }
    } else {
        lines.push(Line::from(Span::styled(s(r, "body"), Style::new().add_modifier(Modifier::BOLD))));
        lines.push(field("level", s(r, "level")));
    }
    lines.push(field("service", s(r, "service")));
    lines.push(field("trace_id", s(r, "trace_id")));
    lines.push(Line::from(""));
    lines.push(Line::from(Span::styled("attributes", Style::new().fg(ACCENT))));
    if let Some(m) = r["attributes"].as_object() {
        let mut kv: Vec<_> = m.iter().collect();
        kv.sort_by(|a, b| a.0.cmp(b.0));
        for (k, v) in kv {
            lines.push(field(k, v.as_str().map(String::from).unwrap_or_else(|| v.to_string())));
        }
    }
    if !s(r, "trace_id").is_empty() {
        lines.push(Line::from(""));
        lines.push(Line::from(Span::styled("t: open trace", Style::new().fg(ACCENT))));
    }
    f.render_widget(Clear, area);
    f.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }).block(block("details")), area);
}

fn trace(f: &mut Frame, app: &mut App, r: Rect) {
    let Some(t) = app.trace.clone() else { return };
    let spans = t["spans"].as_array().cloned().unwrap_or_default();
    let logs = t["logs"].as_array().cloned().unwrap_or_default();
    // tree order with depth
    let ids: Vec<String> = spans.iter().map(|s0| s(s0, "span_id")).collect();
    let mut order: Vec<(usize, usize)> = vec![];
    fn walk(i: usize, d: usize, spans: &[Value], ids: &[String], out: &mut Vec<(usize, usize)>) {
        out.push((i, d));
        let mut kids: Vec<usize> = (0..spans.len()).filter(|&j| s(&spans[j], "parent_span_id") == ids[i]).collect();
        kids.sort_by(|a, b| n(&spans[*a], "start_ns").partial_cmp(&n(&spans[*b], "start_ns")).unwrap());
        for k in kids {
            walk(k, d + 1, spans, ids, out);
        }
    }
    let mut roots: Vec<usize> = (0..spans.len()).filter(|&i| !ids.contains(&s(&spans[i], "parent_span_id"))).collect();
    roots.sort_by(|a, b| n(&spans[*a], "start_ns").partial_cmp(&n(&spans[*b], "start_ns")).unwrap());
    for i in roots {
        walk(i, 0, &spans, &ids, &mut order);
    }
    let t0 = spans.iter().map(|x| n(x, "start_ns")).fold(f64::MAX, f64::min);
    let t1 = spans.iter().map(|x| n(x, "start_ns") + n(x, "dur_ns")).fold(0.0, f64::max);
    let total = (t1 - t0).max(1.0);
    let errors = spans.iter().filter(|x| s(x, "status") == "Error").count();

    let parts = Layout::default().direction(Direction::Vertical).constraints([Constraint::Length(3), Constraint::Min(6), Constraint::Length((logs.len() as u16 + 3).min(10))]).split(r);
    let root = order.first().map(|(i, _)| s(&spans[*i], "name")).unwrap_or_default();
    let head = Line::from(vec![
        Span::styled(root, Style::new().add_modifier(Modifier::BOLD)),
        Span::styled(format!("   {} · {} spans · ", fmt_dur(total / 1e6), spans.len()), Style::new().fg(MUTED)),
        Span::styled(format!("{errors} errors"), Style::new().fg(if errors > 0 { ERROR } else { MUTED })),
        Span::styled(format!(" · {} logs", logs.len()), Style::new().fg(MUTED)),
    ]);
    f.render_widget(Paragraph::new(head).block(block(&format!("trace {}", s(&t, "trace_id")))), parts[0]);

    let wf = block("waterfall");
    let inner = wf.inner(parts[1]);
    f.render_widget(wf, parts[1]);
    let name_w = (inner.width as usize * 2 / 5).max(24);
    let bar_w = (inner.width as usize).saturating_sub(name_w + 11).max(10);
    let mut lines = vec![];
    for (row, (i, d)) in order.iter().enumerate() {
        let sp = &spans[*i];
        let svc = s(sp, "service");
        let color = app.service_color(&svc);
        let err = s(sp, "status") == "Error";
        let label = format!("{}{}{}", "  ".repeat(*d), if err { "! " } else { "" }, s(sp, "name"));
        let label: String = label.chars().take(name_w.saturating_sub(1)).collect();
        let start = ((n(sp, "start_ns") - t0) / total * bar_w as f64) as usize;
        let len = ((n(sp, "dur_ns") / total * bar_w as f64).round() as usize).max(1).min(bar_w - start.min(bar_w - 1));
        let sel = row == app.trace_sel;
        let base = if sel { Style::new().bg(Color::Rgb(27, 45, 47)) } else { Style::new() };
        lines.push(Line::from(vec![
            Span::styled(format!("{label:<name_w$}"), base.fg(if err { ERROR } else { TEXT })),
            Span::styled(" ".repeat(start), base),
            Span::styled("█".repeat(len), base.fg(color)),
            Span::styled(" ".repeat(bar_w.saturating_sub(start + len)), base),
            Span::styled(format!(" {:>9}", fmt_dur(n(sp, "dur_ns") / 1e6)), base.fg(MUTED)),
        ]));
    }
    f.render_widget(Paragraph::new(lines), inner);

    let log_lines: Vec<Line> = logs
        .iter()
        .map(|l| {
            let lvl = s(l, "level");
            Line::from(vec![
                Span::styled(format!("+{:<9} ", fmt_dur((n(l, "ts_ns") - t0) / 1e6)), Style::new().fg(MUTED)),
                Span::styled(format!("{:<6}", lvl), Style::new().fg(level_color(&lvl)).add_modifier(Modifier::BOLD)),
                Span::styled(format!("{:<11}", s(l, "service")), Style::new().fg(MUTED)),
                Span::raw(s(l, "body")),
            ])
        })
        .collect();
    f.render_widget(Paragraph::new(log_lines).block(block("logs in this trace")), parts[2]);

    if app.detail {
        if let Some((i, _)) = order.get(app.trace_sel) {
            let sp = spans[*i].clone();
            let area = Rect { x: r.x + r.width / 2, y: r.y + 3, width: r.width / 2, height: r.height.saturating_sub(3) };
            let mut lines = vec![Line::from(Span::styled(s(&sp, "name"), Style::new().add_modifier(Modifier::BOLD)))];
            for k in ["service", "kind", "status", "status_message"] {
                lines.push(Line::from(vec![Span::styled(format!("{k:<18}"), Style::new().fg(MUTED)), Span::raw(s(&sp, k))]));
            }
            lines.push(Line::from(vec![Span::styled(format!("{:<18}", "duration"), Style::new().fg(MUTED)), Span::raw(fmt_dur(n(&sp, "dur_ns") / 1e6))]));
            lines.push(Line::from(""));
            if let Some(m) = sp["attributes"].as_object() {
                let mut kv: Vec<_> = m.iter().collect();
                kv.sort_by(|a, b| a.0.cmp(b.0));
                for (k, v) in kv {
                    lines.push(Line::from(vec![Span::styled(format!("{k:<18}"), Style::new().fg(MUTED)), Span::raw(v.as_str().unwrap_or("").to_string())]));
                }
            }
            f.render_widget(Clear, area);
            f.render_widget(Paragraph::new(lines).wrap(Wrap { trim: false }).block(block("span")), area);
        }
    }
}

fn spark(points: &[f64], width: usize) -> String {
    const BARS: [char; 8] = ['▁', '▂', '▃', '▄', '▅', '▆', '▇', '█'];
    if points.is_empty() {
        return String::new();
    }
    let max = points.iter().cloned().fold(0.0, f64::max).max(1e-9);
    (0..width).map(|i| BARS[((points[i * points.len() / width] / max) * 7.0).round() as usize]).collect()
}

fn services(f: &mut Frame, app: &mut App, r: Rect) {
    let Some(d) = app.services.clone() else { return };
    let (from, to) = app.window();
    let minutes = (to - from) as f64 / 60000.0;
    let series = d["series"].as_array().cloned().unwrap_or_default();
    let rows: Vec<Row> = d["services"]
        .as_array()
        .into_iter()
        .flatten()
        .map(|sv| {
            let name = s(sv, "service");
            let color = app.service_color(&name);
            let req = n(sv, "requests");
            let rate = if req > 0.0 { n(sv, "errors") / req } else { 0.0 };
            let pts: Vec<&Value> = series.iter().filter(|p| s(p, "service") == name).collect();
            let reqs: Vec<f64> = pts.iter().map(|p| n(p, "req")).collect();
            let p95: Vec<f64> = pts.iter().map(|p| n(p, "p95_ms")).collect();
            Row::new(vec![
                Cell::from(Line::from(vec![Span::styled("■ ", Style::new().fg(color)), Span::raw(name)])),
                Cell::from(Line::from(format!("{req:.0}")).right_aligned()),
                Cell::from(Line::from(format!("{:.1}", req / minutes)).right_aligned()),
                Cell::from(Line::from(Span::styled(format!("{:.2}%", rate * 100.0), Style::new().fg(if rate >= 0.05 { ERROR } else if rate >= 0.01 { WARN } else { OK }))).right_aligned()),
                Cell::from(Line::from(fmt_dur(n(sv, "p50_ms"))).right_aligned()),
                Cell::from(Line::from(fmt_dur(n(sv, "p95_ms"))).right_aligned()),
                Cell::from(Line::from(fmt_dur(n(sv, "p99_ms"))).right_aligned()),
                Cell::from(Span::styled(spark(&reqs, 24), Style::new().fg(color))),
                Cell::from(Span::styled(spark(&p95, 24), Style::new().fg(MUTED))),
            ])
        })
        .collect();
    let widths = [
        Constraint::Length(14), Constraint::Length(9), Constraint::Length(8), Constraint::Length(8),
        Constraint::Length(9), Constraint::Length(9), Constraint::Length(9), Constraint::Length(25), Constraint::Length(25),
    ];
    let header = Row::new(vec![
        Cell::from("service"), Cell::from(Line::from("requests").right_aligned()), Cell::from(Line::from("req/min").right_aligned()),
        Cell::from(Line::from("errors").right_aligned()), Cell::from(Line::from("p50").right_aligned()), Cell::from(Line::from("p95").right_aligned()),
        Cell::from(Line::from("p99").right_aligned()), Cell::from("request rate"), Cell::from("p95 latency"),
    ])
    .style(Style::new().fg(MUTED).add_modifier(Modifier::BOLD));
    f.render_widget(Table::new(rows, widths).header(header).block(block("services · server spans")), r);
}

// ------------------------------------------------------------------ snapshot → HTML

fn css(c: Color, default: &str) -> String {
    match c {
        Color::Rgb(r, g, b) => format!("#{r:02x}{g:02x}{b:02x}"),
        Color::Reset => default.to_string(),
        Color::Black => "#000".into(),
        Color::Red => ERROR_CSS.into(),
        Color::Green => "#30a46c".into(),
        Color::Yellow => "#f0a032".into(),
        Color::Blue => "#3e8fd6".into(),
        Color::Magenta => "#d55181".into(),
        Color::Cyan => "#4fb3a6".into(),
        Color::Gray | Color::White => "#d9dfe7".into(),
        Color::DarkGray => "#5d6877".into(),
        _ => default.to_string(),
    }
}
const ERROR_CSS: &str = "#e5484d";

fn to_html(buf: &Buffer) -> String {
    let esc = |s: &str| s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;");
    let mut out = String::from(
        "<!doctype html><meta charset=\"utf-8\"><title>oscope-tui</title><style>\
         body{margin:0;background:#0b0d11;display:flex;justify-content:center;padding:18px}\
         .term{border:1px solid #262e39;border-radius:10px;overflow:hidden;box-shadow:0 12px 40px rgba(0,0,0,.5)}\
         .bar{background:#1b212a;height:26px;display:flex;align-items:center;gap:7px;padding:0 12px}\
         .bar i{width:11px;height:11px;border-radius:50%;background:#3a4452;display:inline-block}\
         pre{margin:0;font:13px/1.3 'JetBrains Mono','SF Mono',Menlo,Consolas,monospace;background:#0e1116;color:#d9dfe7;padding:6px 8px}\
         pre span,pre b{display:inline-block;height:1.3em;vertical-align:top}b{font-weight:700}</style><div class=\"term\"><div class=\"bar\"><i></i><i></i><i></i></div><pre data-testid=\"tui\">",
    );
    let area = buf.area;
    for y in 0..area.height {
        let mut run = String::new();
        let mut cur: Option<(String, String, bool)> = None;
        let flush = |out: &mut String, run: &mut String, cur: &Option<(String, String, bool)>| {
            if let Some((fg, bg, bold)) = cur {
                let t = esc(run);
                if *bold {
                    out.push_str(&format!("<b style=\"color:{fg};background:{bg}\">{t}</b>"));
                } else {
                    out.push_str(&format!("<span style=\"color:{fg};background:{bg}\">{t}</span>"));
                }
            }
            run.clear();
        };
        for x in 0..area.width {
            let c = &buf[(x, y)];
            let key = (css(c.fg, "#d9dfe7"), css(c.bg, "#0e1116"), c.modifier.contains(Modifier::BOLD));
            if cur.as_ref() != Some(&key) {
                flush(&mut out, &mut run, &cur);
                cur = Some(key);
            }
            run.push_str(c.symbol());
        }
        flush(&mut out, &mut run, &cur);
        out.push('\n');
    }
    out.push_str("</pre></div>");
    out
}

fn key_script(script: &str) -> Vec<KeyEvent> {
    let mut keys = vec![];
    for tok in script.split(' ').filter(|t| !t.is_empty()) {
        let k = |c| KeyEvent::new(c, KeyModifiers::NONE);
        match tok {
            "<enter>" => keys.push(k(KeyCode::Enter)),
            "<esc>" => keys.push(k(KeyCode::Esc)),
            "<tab>" => keys.push(k(KeyCode::Tab)),
            "<down>" => keys.push(k(KeyCode::Down)),
            "<up>" => keys.push(k(KeyCode::Up)),
            "<space>" => keys.push(k(KeyCode::Char(' '))),
            t => t.chars().for_each(|c| keys.push(k(KeyCode::Char(c)))),
        }
    }
    keys
}

fn arg<'a>(args: &'a [String], flag: &str) -> Option<&'a str> {
    args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1)).map(|s| s.as_str())
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let url = arg(&args, "--url").unwrap_or("http://127.0.0.1:8080").trim_end_matches('/').to_string();
    let mut app = App::new(url);
    if let Some(r) = arg(&args, "--range").and_then(|r| RANGES.iter().position(|(n, _)| *n == r)) {
        app.range = r;
    }

    if let Some(out) = arg(&args, "--snapshot") {
        let (w, h) = arg(&args, "--size").and_then(|s| s.split_once('x')).map(|(w, h)| (w.parse().unwrap(), h.parse().unwrap())).unwrap_or((140u16, 42u16));
        app.refresh();
        for k in key_script(arg(&args, "--keys").unwrap_or("")) {
            app.key(k);
        }
        let mut term = Terminal::new(TestBackend::new(w, h)).unwrap();
        term.draw(|f| draw(f, &mut app)).unwrap();
        std::fs::write(out, to_html(term.backend().buffer())).expect("write snapshot");
        return;
    }

    crossterm::terminal::enable_raw_mode().expect("raw mode");
    crossterm::execute!(std::io::stdout(), crossterm::terminal::EnterAlternateScreen).unwrap();
    let mut term = Terminal::new(CrosstermBackend::new(std::io::stdout())).unwrap();
    app.refresh();
    let mut last = Instant::now();
    loop {
        term.draw(|f| draw(f, &mut app)).unwrap();
        if event::poll(Duration::from_millis(250)).unwrap_or(false) {
            if let Ok(Event::Key(k)) = event::read() {
                if !app.key(k) {
                    break;
                }
            }
        }
        if app.live && !app.editing && last.elapsed() >= Duration::from_secs(3) {
            app.refresh();
            last = Instant::now();
        }
    }
    crossterm::terminal::disable_raw_mode().ok();
    crossterm::execute!(term.backend_mut(), crossterm::terminal::LeaveAlternateScreen).ok();
    let _ = term.show_cursor();
    let _ = Backend::size(term.backend());
}

#[cfg(test)]
mod tests {
    use super::*;

    fn app_with(rows: Value) -> App {
        let mut a = App::new("http://unused".into());
        a.rows = rows.as_array().unwrap().clone();
        a.total = a.rows.len() as u64;
        a.hist = vec![3, 5, 2, 8];
        a.hist_err = vec![0, 1, 0, 2];
        a
    }

    fn text(buf: &Buffer) -> String {
        let mut s = String::new();
        for y in 0..buf.area.height {
            for x in 0..buf.area.width {
                s.push_str(buf[(x, y)].symbol());
            }
            s.push('\n');
        }
        s
    }

    #[test]
    fn renders_log_rows_with_levels_and_details() {
        let mut a = app_with(serde_json::json!([
            {"ts": "1790221296648", "service": "payments", "level": "ERROR", "body": "payment gateway timeout for ord-1", "trace_id": "ab", "attributes": {"order.id": "ord-1"}},
            {"ts": "1790221296600", "service": "frontend", "level": "INFO", "body": "GET /api/cart -> 200", "trace_id": "", "attributes": {}}
        ]));
        let mut t = Terminal::new(TestBackend::new(120, 30)).unwrap();
        t.draw(|f| draw(f, &mut a)).unwrap();
        let s = text(t.backend().buffer());
        assert!(s.contains("payment gateway timeout for ord-1"));
        assert!(s.contains("ERROR"));
        assert!(s.contains("2 of 2"));
        // Enter opens the detail pane with attributes
        a.key(KeyEvent::new(KeyCode::Enter, KeyModifiers::NONE));
        t.draw(|f| draw(f, &mut a)).unwrap();
        let s = text(t.backend().buffer());
        assert!(s.contains("details"));
        assert!(s.contains("order.id"));
        assert!(s.contains("t: open trace"));
    }

    #[test]
    fn query_editing_and_key_script() {
        let mut a = app_with(serde_json::json!([]));
        a.key(KeyEvent::new(KeyCode::Char('/'), KeyModifiers::NONE));
        assert!(a.editing);
        for k in key_script("level:error") {
            a.key(k);
        }
        assert_eq!(a.query, "level:error");
        a.key(KeyEvent::new(KeyCode::Esc, KeyModifiers::NONE));
        assert!(!a.editing);
        assert!(!a.key(KeyEvent::new(KeyCode::Char('q'), KeyModifiers::NONE)));
    }

    #[test]
    fn html_snapshot_escapes_text() {
        let mut a = app_with(serde_json::json!([{"ts": "0", "service": "s", "level": "INFO", "body": "<script>x</script> & more", "attributes": {}}]));
        let mut t = Terminal::new(TestBackend::new(100, 20)).unwrap();
        t.draw(|f| draw(f, &mut a)).unwrap();
        let html = to_html(t.backend().buffer());
        assert!(html.contains("&lt;script&gt;x&lt;/script&gt; &amp; more"));
        assert!(!html.contains("<script>x"));
    }
}
