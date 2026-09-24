fn main() {
    // oscope-core links libchdb; the rpath has to be set on the final binary.
    let dir = std::env::var("CHDB_DIR").expect("set CHDB_DIR to the libchdb directory");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{dir}");
    println!("cargo:rerun-if-env-changed=CHDB_DIR");
    println!("cargo:rerun-if-changed=ui");
}
