//! Durable mode: append-only binary WAL of already-encoded RowBinary batches.
//! Frame = magic u32 | table u8 | rows u32 | len u32 | crc32 u32 | payload.
//! Replay = read frames, stop at the first torn/corrupt one, re-insert.

use std::fs::{File, OpenOptions};
use std::io::{Read, Write};

const MAGIC: u32 = 0x4f53_574c; // "OSWL"

pub struct Wal {
    f: File,
    pub bytes: u64,
    sync: bool,
}

impl Wal {
    pub fn open(path: &str, sync: bool) -> std::io::Result<Wal> {
        let f = OpenOptions::new().create(true).append(true).open(path)?;
        let bytes = f.metadata()?.len();
        Ok(Wal { f, bytes, sync })
    }

    pub fn append(&mut self, table: u8, rows: u32, payload: &[u8]) -> std::io::Result<()> {
        let mut hdr = [0u8; 17];
        hdr[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        hdr[4] = table;
        hdr[5..9].copy_from_slice(&rows.to_le_bytes());
        hdr[9..13].copy_from_slice(&(payload.len() as u32).to_le_bytes());
        hdr[13..17].copy_from_slice(&crc32fast::hash(payload).to_le_bytes());
        self.f.write_all(&hdr)?;
        self.f.write_all(payload)?;
        if self.sync {
            self.f.sync_data()?;
        }
        self.bytes += 17 + payload.len() as u64;
        Ok(())
    }

    /// Returns (table, rows, payload) for every intact frame.
    pub fn replay(path: &str) -> std::io::Result<Vec<(u8, u32, Vec<u8>)>> {
        let mut data = Vec::new();
        File::open(path)?.read_to_end(&mut data)?;
        let mut out = Vec::new();
        let mut p = 0;
        while p + 17 <= data.len() {
            let magic = u32::from_le_bytes(data[p..p + 4].try_into().unwrap());
            let table = data[p + 4];
            let rows = u32::from_le_bytes(data[p + 5..p + 9].try_into().unwrap());
            let len = u32::from_le_bytes(data[p + 9..p + 13].try_into().unwrap()) as usize;
            let crc = u32::from_le_bytes(data[p + 13..p + 17].try_into().unwrap());
            if magic != MAGIC || p + 17 + len > data.len() {
                break;
            }
            let payload = &data[p + 17..p + 17 + len];
            if crc32fast::hash(payload) != crc {
                break;
            }
            out.push((table, rows, payload.to_vec()));
            p += 17 + len;
        }
        Ok(out)
    }
}
