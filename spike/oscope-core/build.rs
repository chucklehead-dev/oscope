fn main() {
    let dir = std::env::var("CHDB_DIR").expect("set CHDB_DIR to the libchdb directory");
    println!("cargo:rustc-link-search=native={dir}");
    println!("cargo:rustc-link-lib=dylib=chdb");
    println!("cargo:rustc-link-arg=-Wl,-rpath,{dir}");
    println!("cargo:rerun-if-env-changed=CHDB_DIR");
}
