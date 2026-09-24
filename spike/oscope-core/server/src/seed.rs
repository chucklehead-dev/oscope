//! Synthetic multi-service traffic, generated as OTLP/HTTP JSON and fed
//! through the same decode path as an external OTLP sender.

use serde_json::{json, Value};
use std::collections::BTreeMap;

pub struct Rng(u64);
impl Rng {
    pub fn new(seed: u64) -> Rng {
        Rng(seed | 1)
    }
    pub fn u64(&mut self) -> u64 {
        // xorshift64*
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    pub fn f(&mut self) -> f64 {
        (self.u64() >> 11) as f64 / (1u64 << 53) as f64
    }
    pub fn range(&mut self, lo: u64, hi: u64) -> u64 {
        lo + self.u64() % (hi - lo).max(1)
    }
    pub fn pick<'a, T>(&mut self, v: &'a [T]) -> &'a T {
        &v[(self.u64() % v.len() as u64) as usize]
    }
    /// Log-normal-ish latency: median `med` microseconds, long right tail.
    pub fn latency_us(&mut self, med: f64) -> u64 {
        let u = self.f().max(1e-9);
        let n = (-2.0 * u.ln()).sqrt() * (2.0 * std::f64::consts::PI * self.f()).cos();
        (med * (0.45 * n).exp()).max(20.0) as u64
    }
}

fn kv(k: &str, v: Value) -> Value {
    let val = match v {
        Value::String(s) => json!({ "stringValue": s }),
        Value::Number(n) if n.is_i64() || n.is_u64() => json!({ "intValue": n.to_string() }),
        Value::Number(n) => json!({ "doubleValue": n }),
        Value::Bool(b) => json!({ "boolValue": b }),
        other => json!({ "stringValue": other.to_string() }),
    };
    json!({ "key": k, "value": val })
}

#[derive(Default)]
pub struct Batch {
    spans: BTreeMap<&'static str, Vec<Value>>,
    logs: BTreeMap<&'static str, Vec<Value>>,
    pub span_count: usize,
    pub log_count: usize,
}

struct Ctx {
    trace: String,
}

impl Batch {
    #[allow(clippy::too_many_arguments)]
    fn span(&mut self, svc: &'static str, c: &Ctx, id: u64, parent: u64, name: &str, kind: u8, start: u64, dur_us: u64, error: Option<&str>, attrs: Vec<Value>) {
        let mut s = json!({
            "traceId": c.trace, "spanId": format!("{id:016x}"),
            "parentSpanId": if parent == 0 { String::new() } else { format!("{parent:016x}") },
            "name": name, "kind": kind,
            "startTimeUnixNano": start.to_string(), "endTimeUnixNano": (start + dur_us * 1000).to_string(),
            "attributes": attrs,
            "status": { "code": if error.is_some() { 2 } else { 0 } }
        });
        if let Some(e) = error {
            s["status"]["message"] = json!(e);
            s["attributes"].as_array_mut().unwrap().push(kv("exception.message", json!(e)));
        }
        self.spans.entry(svc).or_default().push(s);
        self.span_count += 1;
    }

    #[allow(clippy::too_many_arguments)]
    fn log(&mut self, svc: &'static str, trace: &str, span: u64, ts: u64, sev: u8, text: &str, body: String, attrs: Vec<Value>) {
        self.logs.entry(svc).or_default().push(json!({
            "timeUnixNano": ts.to_string(), "severityNumber": sev, "severityText": text,
            "body": { "stringValue": body }, "attributes": attrs,
            "traceId": trace, "spanId": if span == 0 { String::new() } else { format!("{span:016x}") }
        }));
        self.log_count += 1;
    }

    fn resource(svc: &str) -> Value {
        json!({ "attributes": [
            kv("service.name", json!(svc)), kv("service.version", json!("1.4.2")),
            kv("deployment.environment", json!("local")), kv("host.name", json!(format!("{svc}-7f9c"))),
        ]})
    }

    /// OTLP/HTTP JSON bodies: one traces request and one logs request.
    pub fn bodies(&self) -> (Vec<u8>, Vec<u8>) {
        let rs: Vec<Value> = self
            .spans
            .iter()
            .map(|(svc, spans)| json!({ "resource": Self::resource(svc), "scopeSpans": [{ "scope": { "name": "seed", "version": "0.1" }, "spans": spans }] }))
            .collect();
        let rl: Vec<Value> = self
            .logs
            .iter()
            .map(|(svc, logs)| json!({ "resource": Self::resource(svc), "scopeLogs": [{ "scope": { "name": "seed" }, "logRecords": logs }] }))
            .collect();
        (serde_json::to_vec(&json!({ "resourceSpans": rs })).unwrap(), serde_json::to_vec(&json!({ "resourceLogs": rl })).unwrap())
    }
}

const ROUTES: &[(&str, &str)] = &[
    ("GET", "/api/products/{id}"),
    ("GET", "/api/cart"),
    ("POST", "/api/checkout"),
    ("GET", "/api/recommendations"),
];

/// Generate `n` requests with start times spread over [from_ns, to_ns).
pub fn generate(rng: &mut Rng, n: usize, from_ns: u64, to_ns: u64) -> Batch {
    let mut b = Batch::default();
    for _ in 0..n {
        let start = from_ns + (rng.f() * (to_ns - from_ns) as f64) as u64;
        request(rng, &mut b, start);
    }
    // background noise: logs with no trace
    for _ in 0..(n / 8).max(1) {
        let ts = from_ns + (rng.f() * (to_ns - from_ns) as f64) as u64;
        match rng.range(0, 10) {
            0..=5 => b.log("inventory", "", 0, ts, 9, "INFO", "stock sync completed".into(), vec![kv("job", json!("stock-sync")), kv("items", json!(rng.range(40, 400)))]),
            6..=7 => b.log("frontend", "", 0, ts, 5, "DEBUG", "cache warmed".into(), vec![kv("cache.entries", json!(rng.range(500, 5000)))]),
            8 => b.log("payments", "", 0, ts, 13, "WARN", "gateway latency above SLO".into(), vec![kv("gateway", json!("stripe")), kv("p95_ms", json!(rng.range(600, 1500)))]),
            _ => b.log("checkout", "", 0, ts, 9, "INFO", "feature flags refreshed".into(), vec![kv("flags", json!(12))]),
        }
    }
    b
}

fn request(rng: &mut Rng, b: &mut Batch, start: u64) {
    let c = Ctx { trace: format!("{:016x}{:016x}", rng.u64(), rng.u64()) };
    let (method, route) = *rng.pick(ROUTES);
    let root = rng.u64() | 1;
    let user = format!("user-{}", rng.range(1, 400));
    let mut t = start + 150_000; // time cursor inside the request
    let mut status = 200;
    let mut root_err: Option<String> = None;

    // frontend -> cache
    let cache = rng.u64() | 1;
    let d = rng.latency_us(300.0);
    b.span("frontend", &c, cache, root, "redis GET", 3, t, d, None, vec![kv("db.system", json!("redis")), kv("db.operation.name", json!("GET"))]);
    t += d * 1000;

    match route {
        "/api/checkout" => {
            // frontend client -> checkout server
            let cli = rng.u64() | 1;
            let srv = rng.u64() | 1;
            let cstart = t;
            let mut ct = cstart + 200_000;
            let order = format!("ord-{}", rng.range(10_000, 99_999));
            // inventory reserve
            let inv = rng.u64() | 1;
            let inv_d = rng.latency_us(4_000.0);
            let out_of_stock = rng.f() < 0.03;
            b.span("inventory", &c, inv, srv, "POST /reserve", 2, ct, inv_d, if out_of_stock { Some("insufficient stock") } else { None },
                vec![kv("http.request.method", json!("POST")), kv("http.route", json!("/reserve")), kv("http.response.status_code", json!(if out_of_stock { 409 } else { 200 })), kv("order.id", json!(order))]);
            let q = rng.u64() | 1;
            b.span("inventory", &c, q, inv, "UPDATE stock", 3, ct + 300_000, inv_d * 6 / 10, None,
                vec![kv("db.system", json!("postgresql")), kv("db.operation.name", json!("UPDATE")), kv("db.query.text", json!("UPDATE stock SET qty = qty - $1 WHERE sku = $2"))]);
            ct += inv_d * 1000;
            if out_of_stock {
                status = 409;
                b.log("inventory", &c.trace, inv, ct, 13, "WARN", format!("reservation rejected for {order}: insufficient stock"), vec![kv("order.id", json!(order)), kv("sku", json!(format!("sku-{}", rng.range(1, 60))))]);
            } else {
                // payments
                let pay = rng.u64() | 1;
                let slow = rng.f() < 0.05;
                let pay_d = if slow { rng.range(2_000_000, 3_000_000) } else { rng.latency_us(45_000.0) };
                let timeout = slow && rng.f() < 0.8;
                b.span("payments", &c, pay, srv, "POST /charge", 2, ct, pay_d, if timeout { Some("gateway timeout after 2000ms") } else { None },
                    vec![kv("http.request.method", json!("POST")), kv("http.route", json!("/charge")), kv("http.response.status_code", json!(if timeout { 504 } else { 200 })), kv("payment.provider", json!("stripe")), kv("payment.amount", json!(rng.range(5, 400) as f64 + 0.99))]);
                let gw = rng.u64() | 1;
                b.span("payments", &c, gw, pay, "stripe.charges.create", 3, ct + 500_000, pay_d.saturating_sub(800), if timeout { Some("context deadline exceeded") } else { None },
                    vec![kv("peer.service", json!("stripe")), kv("rpc.system", json!("http"))]);
                ct += pay_d * 1000;
                if timeout {
                    status = 502;
                    root_err = Some("upstream payments failed".into());
                    b.log("payments", &c.trace, pay, ct, 17, "ERROR", format!("payment gateway timeout for {order}"), vec![kv("order.id", json!(order)), kv("payment.provider", json!("stripe")), kv("timeout_ms", json!(2000))]);
                } else {
                    b.log("payments", &c.trace, pay, ct, 9, "INFO", format!("charged {order}"), vec![kv("order.id", json!(order))]);
                }
            }
            let cdur = (ct - cstart) / 1000 + 300;
            b.span("checkout", &c, srv, cli, "POST /orders", 2, cstart + 100_000, cdur, root_err.as_deref(),
                vec![kv("http.request.method", json!("POST")), kv("http.route", json!("/orders")), kv("http.response.status_code", json!(status)), kv("order.id", json!(order)), kv("user.id", json!(user))]);
            b.span("frontend", &c, cli, root, "POST checkout:/orders", 3, cstart, cdur + 200, root_err.as_deref(),
                vec![kv("http.request.method", json!("POST")), kv("server.address", json!("checkout")), kv("http.response.status_code", json!(status))]);
            b.log("checkout", &c.trace, srv, ct, if status == 200 { 9 } else { 17 }, if status == 200 { "INFO" } else { "ERROR" },
                if status == 200 { format!("order {order} placed") } else { format!("order {order} failed with {status}") }, vec![kv("order.id", json!(order)), kv("user.id", json!(user))]);
            t = ct + 400_000;
        }
        "/api/products/{id}" | "/api/cart" => {
            let q = rng.u64() | 1;
            let slow = rng.f() < 0.02;
            let d = if slow { rng.range(400_000, 900_000) } else { rng.latency_us(2_500.0) };
            b.span("frontend", &c, q, root, "SELECT products", 3, t, d, None,
                vec![kv("db.system", json!("postgresql")), kv("db.operation.name", json!("SELECT")), kv("db.query.text", json!("SELECT id, name, price FROM products WHERE id = $1"))]);
            if slow {
                b.log("frontend", &c.trace, q, t + d * 1000, 13, "WARN", format!("slow query: {} ms", d / 1000), vec![kv("db.system", json!("postgresql")), kv("duration_ms", json!(d / 1000))]);
            }
            t += d * 1000;
        }
        _ => {
            let r = rng.u64() | 1;
            let d = rng.latency_us(12_000.0);
            b.span("frontend", &c, r, root, "recommend", 1, t, d, None, vec![kv("model", json!("als-v3")), kv("candidates", json!(rng.range(20, 200)))]);
            t += d * 1000;
        }
    }
    let dur = (t - start) / 1000 + 200;
    b.span("frontend", &c, root, 0, &format!("{method} {route}"), 2, start, dur, root_err.as_deref(),
        vec![kv("http.request.method", json!(method)), kv("http.route", json!(route)), kv("http.response.status_code", json!(status)), kv("user.id", json!(user))]);
    b.log("frontend", &c.trace, root, t, if status >= 500 { 17 } else { 9 }, if status >= 500 { "ERROR" } else { "INFO" },
        format!("{method} {route} -> {status} in {} ms", dur / 1000), vec![kv("http.response.status_code", json!(status)), kv("user.id", json!(user))]);
}
