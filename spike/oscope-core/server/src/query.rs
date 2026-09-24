//! Search-bar language → SQL WHERE fragment.
//!
//! Terms are ANDed. Each term is one of
//!   word | "quoted words"          full-text (log Body / span name + status message)
//!   field:value | field:"a b"      equality (case-insensitive for level/status/kind)
//!   field:val*                     prefix
//!   field:>=500  (>, >=, <, <=)    numeric; `duration` is in milliseconds
//!   -term                          negation
//! Known fields map to columns; anything else is an attribute key
//! (LogAttributes / SpanAttributes). User text only ever reaches SQL as a
//! quoted, escaped string literal or a validated number; field names are
//! allowlisted or checked against [A-Za-z0-9_.-]+.

#[derive(Clone, Copy, PartialEq, Debug)]
pub enum Source {
    Logs,
    Traces,
}

impl Source {
    pub fn parse(s: &str) -> Source {
        if s == "traces" { Source::Traces } else { Source::Logs }
    }
}

pub fn sql_str(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 2);
    o.push('\'');
    for c in s.chars() {
        match c {
            '\'' => o.push_str("\\'"),
            '\\' => o.push_str("\\\\"),
            c => o.push(c),
        }
    }
    o.push('\'');
    o
}

fn valid_key(k: &str) -> bool {
    !k.is_empty() && k.len() <= 128 && k.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '_' | '.' | '-'))
}

fn tokenize(q: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut cur = String::new();
    let mut in_q = false;
    for c in q.chars() {
        match c {
            '"' => {
                in_q = !in_q;
                cur.push(c);
            }
            c if c.is_whitespace() && !in_q => {
                if !cur.is_empty() {
                    out.push(std::mem::take(&mut cur));
                }
            }
            c => cur.push(c),
        }
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out
}

fn unquote(v: &str) -> (String, bool) {
    if v.len() >= 2 && v.starts_with('"') && v.ends_with('"') {
        (v[1..v.len() - 1].to_string(), true)
    } else {
        (v.trim_matches('"').to_string(), false)
    }
}

enum Col {
    /// plain column, compared as text
    Text(&'static str),
    /// column compared case-insensitively
    Folded(&'static str),
    /// numeric column; the multiplier converts the user's unit to storage
    Num(&'static str, f64),
    /// attribute map lookup
    Attr(String),
}

fn column(src: Source, field: &str) -> Result<Col, String> {
    let f = field.to_ascii_lowercase();
    Ok(match (src, f.as_str()) {
        (_, "service" | "service.name") => Col::Text("ServiceName"),
        (_, "trace" | "trace_id" | "traceid") => Col::Text("TraceId"),
        (_, "span_id" | "spanid") => Col::Text("SpanId"),
        (Source::Logs, "level" | "severity") => Col::Folded("SeverityText"),
        (Source::Logs, "body" | "message") => Col::Text("Body"),
        (Source::Traces, "name" | "span" | "span.name") => Col::Text("SpanName"),
        (Source::Traces, "status") => Col::Folded("StatusCode"),
        (Source::Traces, "kind") => Col::Folded("SpanKind"),
        (Source::Traces, "duration") => Col::Num("Duration", 1e6),
        (Source::Traces, "parent" | "parent_span_id") => Col::Text("ParentSpanId"),
        _ => {
            if !valid_key(field) {
                return Err(format!("invalid field name: {field}"));
            }
            let map = if src == Source::Logs { "LogAttributes" } else { "SpanAttributes" };
            Col::Attr(format!("{map}[{}]", sql_str(field)))
        }
    })
}

fn term_sql(src: Source, tok: &str) -> Result<String, String> {
    // field:value, but not a bare quoted phrase that happens to contain ':'
    if let (Some(i), false) = (tok.find(':'), tok.starts_with('"')) {
        let (field, raw) = (&tok[..i], &tok[i + 1..]);
        if raw.is_empty() {
            return Err(format!("missing value for {field}"));
        }
        let col = column(src, field)?;
        // numeric comparison
        for op in [">=", "<=", ">", "<"] {
            if let Some(num) = raw.strip_prefix(op) {
                let n: f64 = num.parse().map_err(|_| format!("not a number: {num}"))?;
                return Ok(match col {
                    Col::Num(c, mul) => format!("{c} {op} {}", n * mul),
                    Col::Attr(a) => format!("toFloat64OrNull({a}) {op} {n}"),
                    Col::Text(c) | Col::Folded(c) => format!("toFloat64OrNull({c}) {op} {n}"),
                });
            }
        }
        let (val, quoted) = unquote(raw);
        let prefix = !quoted && val.ends_with('*') && val.len() > 1;
        let val = if prefix { &val[..val.len() - 1] } else { &val[..] };
        return Ok(match col {
            Col::Num(c, mul) => {
                let n: f64 = val.parse().map_err(|_| format!("not a number: {val}"))?;
                format!("{c} = {}", n * mul)
            }
            Col::Folded(c) if prefix => format!("startsWith(lower({c}), lower({}))", sql_str(val)),
            Col::Folded(c) => format!("lower({c}) = lower({})", sql_str(val)),
            Col::Text(c) if prefix => format!("startsWith({c}, {})", sql_str(val)),
            Col::Text(c) => format!("{c} = {}", sql_str(val)),
            Col::Attr(a) if prefix => format!("startsWith({a}, {})", sql_str(val)),
            Col::Attr(a) => format!("{a} = {}", sql_str(val)),
        });
    }
    let (word, _) = unquote(tok);
    if word.is_empty() {
        return Ok("1".into());
    }
    let pat = sql_str(&format!("%{}%", word.replace('\\', "\\\\").replace('%', "\\%").replace('_', "\\_")));
    Ok(match src {
        Source::Logs => format!("Body ILIKE {pat}"),
        Source::Traces => format!("(SpanName ILIKE {pat} OR StatusMessage ILIKE {pat})"),
    })
}

/// Compile a search string to a WHERE fragment ("1" when empty).
pub fn compile(q: &str, src: Source) -> Result<String, String> {
    let mut parts = Vec::new();
    for tok in tokenize(q) {
        let (neg, t) = match tok.strip_prefix('-') {
            Some(rest) if !rest.is_empty() => (true, rest),
            _ => (false, tok.as_str()),
        };
        let s = term_sql(src, t)?;
        parts.push(if neg { format!("NOT ({s})") } else { format!("({s})") });
    }
    Ok(if parts.is_empty() { "1".into() } else { parts.join(" AND ") })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn compiles_terms() {
        assert_eq!(compile("", Source::Logs).unwrap(), "1");
        assert_eq!(compile("service:checkout", Source::Logs).unwrap(), "(ServiceName = 'checkout')");
        assert_eq!(compile("level:ERROR", Source::Logs).unwrap(), "(lower(SeverityText) = lower('ERROR'))");
        assert_eq!(compile("duration:>250", Source::Traces).unwrap(), "(Duration > 250000000)");
        assert_eq!(
            compile("http.status_code:>=500", Source::Traces).unwrap(),
            "(toFloat64OrNull(SpanAttributes['http.status_code']) >= 500)"
        );
        assert_eq!(compile("-name:db.query", Source::Traces).unwrap(), "NOT (SpanName = 'db.query')");
        assert_eq!(compile("route:/api/*", Source::Traces).unwrap(), "(startsWith(SpanAttributes['route'], '/api/'))");
        assert_eq!(compile("\"gateway timeout\"", Source::Logs).unwrap(), "(Body ILIKE '%gateway timeout%')");
        assert_eq!(
            compile("service:\"a b\" timeout", Source::Logs).unwrap(),
            "(ServiceName = 'a b') AND (Body ILIKE '%timeout%')"
        );
    }

    #[test]
    fn user_text_cannot_escape_the_literal() {
        let s = compile("body:x'); DROP TABLE otel_logs; --", Source::Logs).unwrap();
        assert_eq!(s, "(Body = 'x\\');') AND (Body ILIKE '%DROP%') AND (Body ILIKE '%TABLE%') AND (Body ILIKE '%otel\\\\_logs;%') AND NOT (Body ILIKE '%-%')");
        assert!(compile("bad key!:1", Source::Logs).is_err());
        assert!(compile("x'y:1", Source::Logs).is_err());
        assert!(compile("duration:>abc", Source::Traces).is_err());
    }
}
