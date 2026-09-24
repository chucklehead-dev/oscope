//! `oscope`: one binary that owns an embedded chDB store, accepts OTLP/HTTP
//! JSON, and serves a HyperDX-style web UI plus the JSON API it runs on.
//!
//!   oscope [--db PATH] [--listen ADDR] [--seed-minutes M] [--seed-rpm R]
//!          [--live-rps N] [--seed SEED]

mod api;
mod query;
mod seed;

use axum::{
    body::Bytes,
    extract::{Path, Query as Q, State},
    http::{header, HeaderMap, StatusCode},
    response::{IntoResponse, Response},
    routing::{get, post},
    Json, Router,
};
use oscope_core::chdb::Conn;
use oscope_core::encode::Resource;
use oscope_core::pipeline::{self, Config, InsertMode, Pipeline};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::{Duration, SystemTime, UNIX_EPOCH};

pub struct App {
    pipe: Pipeline,
    ingest: Mutex<Conn>,
    pub logs: &'static str,
    pub traces: &'static str,
}

impl App {
    /// Run a read query; returns the `data` array of chDB's JSON output.
    ///
    /// optimize_functions_to_subcolumns is off because the engine rewrites
    /// `Attributes['k']` into a map-key subcolumn read, which the Buffer
    /// tables in front of MergeTree cannot serve (THERE_IS_NO_COLUMN).
    pub fn rows(&self, sql: &str) -> Result<Vec<Value>, String> {
        let out = self.pipe.query(&format!("{sql} SETTINGS optimize_functions_to_subcolumns = 0"), "JSON")?;
        let v: Value = serde_json::from_str(&out).map_err(|e| format!("bad JSON from engine: {e}"))?;
        Ok(v.get("data").and_then(|d| d.as_array()).cloned().unwrap_or_default())
    }

    /// Decode an OTLP/HTTP JSON body and insert it. Returns rows inserted.
    pub fn ingest(&self, logs: bool, body: &[u8]) -> Result<usize, String> {
        let mut out = Vec::with_capacity(body.len());
        let mut scratch = Vec::new();
        let rows = if logs {
            oscope_core::otlp_json::decode_logs(body, &mut out, &mut scratch)?
        } else {
            oscope_core::otlp_json::decode_traces(body, &mut out, &mut scratch)?
        };
        if rows > 0 {
            let conn = self.ingest.lock().unwrap();
            pipeline::insert(&conn, InsertMode::StreamRowBinary, if logs { 2 } else { 1 }, &out)?;
        }
        Ok(rows)
    }
}

pub fn now_ms() -> i64 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis() as i64
}

type Shared = Arc<App>;
type Params = Q<HashMap<String, String>>;

fn api_result(r: Result<Value, String>) -> Response {
    match r {
        Ok(v) => Json(v).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, Json(json!({ "error": e }))).into_response(),
    }
}

async fn blocking<F: FnOnce() -> Result<Value, String> + Send + 'static>(f: F) -> Response {
    api_result(tokio::task::spawn_blocking(f).await.unwrap_or_else(|e| Err(e.to_string())))
}

async fn search(State(app): State<Shared>, Q(p): Params) -> Response {
    blocking(move || api::search(&app, &p)).await
}
async fn histogram(State(app): State<Shared>, Q(p): Params) -> Response {
    blocking(move || api::histogram(&app, &p)).await
}
async fn facets(State(app): State<Shared>, Q(p): Params) -> Response {
    blocking(move || api::facets(&app, &p)).await
}
async fn trace(State(app): State<Shared>, Path(id): Path<String>) -> Response {
    blocking(move || api::trace(&app, &id)).await
}
async fn services(State(app): State<Shared>, Q(p): Params) -> Response {
    blocking(move || api::services(&app, &p)).await
}

async fn otlp(app: Shared, logs: bool, headers: HeaderMap, body: Bytes) -> Response {
    let ct = headers.get(header::CONTENT_TYPE).and_then(|v| v.to_str().ok()).unwrap_or("");
    if !ct.starts_with("application/json") {
        return (StatusCode::UNSUPPORTED_MEDIA_TYPE, "only application/json is supported so far").into_response();
    }
    match tokio::task::spawn_blocking(move || app.ingest(logs, &body)).await.unwrap_or_else(|e| Err(e.to_string())) {
        Ok(_) => Json(json!({})).into_response(),
        Err(e) => (StatusCode::BAD_REQUEST, e).into_response(),
    }
}

const INDEX: &str = include_str!("../ui/index.html");
const APP_JS: &str = include_str!("../ui/app.js");
const APP_CSS: &str = include_str!("../ui/app.css");

fn asset(ct: &'static str, body: &'static str) -> Response {
    ([(header::CONTENT_TYPE, ct), (header::CACHE_CONTROL, "no-cache")], body).into_response()
}

fn arg<'a>(args: &'a [String], flag: &str) -> Option<&'a str> {
    args.iter().position(|a| a == flag).and_then(|i| args.get(i + 1)).map(|s| s.as_str())
}

#[tokio::main]
async fn main() {
    let args: Vec<String> = std::env::args().collect();
    let db = arg(&args, "--db").unwrap_or("./oscope-data").to_string();
    let listen = arg(&args, "--listen").unwrap_or("127.0.0.1:8080").to_string();
    let seed_minutes: u64 = arg(&args, "--seed-minutes").unwrap_or("0").parse().unwrap();
    let seed_rpm: u64 = arg(&args, "--seed-rpm").unwrap_or("300").parse().unwrap();
    let live_rps: u64 = arg(&args, "--live-rps").unwrap_or("0").parse().unwrap();
    let seed_value: u64 = arg(&args, "--seed").unwrap_or("7").parse().unwrap();

    let pipe = Pipeline::start(Config {
        path: Some(db.clone()),
        wal: None,
        batch_rows: 8192,
        flush_interval: Duration::from_millis(250),
        mode: InsertMode::StreamRowBinary,
        resource: Resource { service_name: "oscope".into(), scope_name: "oscope".into(), scope_version: "0.0.1".into(), attrs: vec![] },
    })
    .expect("start store");
    let ingest = Conn::open(Some(&db)).expect("open ingest connection");
    let buffered = pipe.query("EXISTS TABLE otel_logs_buf", "TSV").map(|s| s.trim() == "1").unwrap_or(false);
    let app = Arc::new(App {
        pipe,
        ingest: Mutex::new(ingest),
        logs: if buffered { "otel_logs_buf" } else { "otel_logs" },
        traces: if buffered { "otel_traces_buf" } else { "otel_traces" },
    });

    let mut rng = seed::Rng::new(seed_value);
    if seed_minutes > 0 {
        let to = now_ms() as u64 * 1_000_000;
        let from = to - seed_minutes * 60 * 1_000_000_000;
        let total = (seed_minutes * seed_rpm) as usize;
        let mut done = 0;
        // chunks keep each OTLP body a realistic size
        while done < total {
            let n = (total - done).min(2000);
            let span = (to - from) / total as u64;
            let b = seed::generate(&mut rng, n, from + span * done as u64, from + span * (done + n) as u64);
            let (t, l) = b.bodies();
            app.ingest(false, &t).expect("seed traces");
            app.ingest(true, &l).expect("seed logs");
            done += n;
        }
        eprintln!("seeded {total} requests over the last {seed_minutes} min");
    }
    if live_rps > 0 {
        let app2 = app.clone();
        std::thread::spawn(move || {
            let mut rng = seed::Rng::new(seed_value ^ 0xABCD);
            loop {
                std::thread::sleep(Duration::from_secs(1));
                let to = now_ms() as u64 * 1_000_000;
                let b = seed::generate(&mut rng, live_rps as usize, to - 1_000_000_000, to);
                let (t, l) = b.bodies();
                let _ = app2.ingest(false, &t);
                let _ = app2.ingest(true, &l);
            }
        });
    }

    let a1 = app.clone();
    let a2 = app.clone();
    let router = Router::new()
        .route("/", get(|| async { asset("text/html; charset=utf-8", INDEX) }))
        .route("/app.js", get(|| async { asset("text/javascript; charset=utf-8", APP_JS) }))
        .route("/app.css", get(|| async { asset("text/css; charset=utf-8", APP_CSS) }))
        .route("/healthz", get(|| async { "ok" }))
        .route("/api/search", get(search))
        .route("/api/histogram", get(histogram))
        .route("/api/facets", get(facets))
        .route("/api/trace/{id}", get(trace))
        .route("/api/services", get(services))
        .route("/v1/traces", post(move |h: HeaderMap, b: Bytes| otlp(a1.clone(), false, h, b)))
        .route("/v1/logs", post(move |h: HeaderMap, b: Bytes| otlp(a2.clone(), true, h, b)))
        .with_state(app);

    let listener = tokio::net::TcpListener::bind(&listen).await.expect("bind");
    eprintln!("oscope listening on http://{listen} (store: {db})");
    axum::serve(listener, router)
        .with_graceful_shutdown(async {
            let _ = tokio::signal::ctrl_c().await;
        })
        .await
        .unwrap();
}
