#!/usr/bin/env python3
import re
import sys
from pathlib import Path


def read_text(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="ignore") if path.exists() else ""


def find_client_struct_and_ctor(client_rs: Path, fallback_struct: str):
    src = read_text(client_rs)
    m_struct = re.search(r"pub\s+struct\s+(\w+Client)\b", src)
    struct_name = m_struct.group(1) if m_struct else fallback_struct
    m_new = re.search(r"pub\s+fn\s+new\s*\(([^)]*)\)\s*(->\s*[^{]+)?\{", src)
    if m_new and m_new.group(1).strip() == "":
        ret = (m_new.group(2) or "").strip()
        if "Result" in ret:
            return struct_name, f'{struct_name}::new().expect("init {struct_name}")'
        return struct_name, f"{struct_name}::new()"
    if f"impl Default for {struct_name}" in src or ("#[derive(" in src and "Default" in src):
        return struct_name, f"{struct_name}::default()"
    return struct_name, f"{struct_name}::new()"


def find_resolve_method(providers_mod: Path, fourk_mod: Path, fourk_client: Path):
    for p in (providers_mod, fourk_mod, fourk_client):
        src = read_text(p)
        m = re.search(r"async\s+fn\s+(resolve_\w+)\s*\(\s*&self\s*,\s*\w+\s*:\s*&str\s*\)", src)
        if m:
            return m.group(1)
    return None


def main():
    vendor_dir = Path(sys.argv[1])
    out_dir = Path(sys.argv[2])
    (out_dir / "src").mkdir(parents=True, exist_ok=True)

    mb_client_rs = vendor_dir / "src/providers/moviebox/client.rs"
    fk_client_rs = vendor_dir / "src/providers/fourkhdhub/client.rs"
    dr_client_rs = vendor_dir / "src/providers/dramachi/client.rs"
    prov_mod_rs = vendor_dir / "src/providers/mod.rs"
    fk_mod_rs = vendor_dir / "src/providers/fourkhdhub/mod.rs"
    dr_mod_rs = vendor_dir / "src/providers/dramachi/mod.rs"

    if fk_mod_rs.exists():
        fk_mod_rs.write_text(re.sub(r"(?m)^mod client;", "pub mod client;", read_text(fk_mod_rs)), encoding="utf-8")

    mb_struct, mb_ctor = find_client_struct_and_ctor(mb_client_rs, "MovieBoxClient")
    fk_struct, fk_ctor = find_client_struct_and_ctor(fk_client_rs, "FourkHdHubClient")
    dr_struct, dr_ctor = find_client_struct_and_ctor(dr_client_rs, "DramachiClient")
    resolve_fn = find_resolve_method(prov_mod_rs, fk_mod_rs, fk_client_rs)

    fk_mod_src = read_text(fk_mod_rs)
    dr_mod_src = read_text(dr_mod_rs)
    has_fourk = fk_client_rs.exists() and "impl Provider for" in fk_mod_src
    has_fourk_rel = has_fourk and "ReleaseProvider for" in fk_mod_src
    has_dramachi = dr_client_rs.exists() and "impl Provider for" in dr_mod_src
    has_dramachi_rel = has_dramachi and "ReleaseProvider for" in dr_mod_src

    (out_dir / "Cargo.toml").write_text("""[package]
name = "moviebox-ffi"
version = "0.1.0"
edition = "2024"

[lib]
name = "moviebox_ffi"
crate-type = ["cdylib"]

[dependencies]
moviebox-tui = { path = "../vendor/MovieBox-Tui" }
jni = "0.21"
once_cell = "1.20"
rustls = { version = "0.23", default-features = false, features = ["ring", "std"] }
serde_json = "1.0"
tokio = { version = "1", features = ["rt-multi-thread"] }
""", encoding="utf-8")

    fourk_import = f"use moviebox_tui::providers::fourkhdhub::client::{fk_struct};" if has_fourk else ""
    dramachi_import = f"use moviebox_tui::providers::dramachi::client::{dr_struct};" if has_dramachi else ""
    fourk_static = f"static FOURK: Lazy<{fk_struct}> = Lazy::new(|| {fk_ctor});" if has_fourk else ""
    dramachi_static = f"static DRAMACHI: Lazy<{dr_struct}> = Lazy::new(|| {dr_ctor});" if has_dramachi else ""

    fourk_search = '        "4khdhub" => Provider::search(&*FOURK, &query, page).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_fourk else ""
    dramachi_search = '        "dramachi" => Provider::search(&*DRAMACHI, &query, page).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_dramachi else ""
    fourk_details = '        "4khdhub" => Provider::details(&*FOURK, &id).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_fourk else ""
    dramachi_details = '        "dramachi" => Provider::details(&*DRAMACHI, &id).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_dramachi else ""
    fourk_streams = '        "4khdhub" => ReleaseProvider::episode_streams(&*FOURK, &id, season, episode).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_fourk_rel else ""
    dramachi_streams = '        "dramachi" => ReleaseProvider::episode_streams(&*DRAMACHI, &id, season, episode).await.map(|v| serde_json::to_value(v).unwrap_or_default()),' if has_dramachi_rel else ""
    resolve_arm = f'        "4khdhub" => FOURK.{resolve_fn}(&url).await.map(|v| serde_json::to_value(v).unwrap_or_default()).map_err(|e| e.to_string()),' if (has_fourk and resolve_fn) else ""

    lib_rs = f"""use jni::JNIEnv;
use jni::objects::{{JClass, JString}};
use jni::sys::{{jint, jstring}};
use moviebox_tui::providers::moviebox::client::{mb_struct};
{fourk_import}
{dramachi_import}
use moviebox_tui::providers::{{Provider, ReleaseProvider}};
use once_cell::sync::Lazy;
use serde_json::json;
use tokio::runtime::Runtime;

fn ensure_tls() {{
    let _ = rustls::crypto::ring::default_provider().install_default();
}}

static RUNTIME: Lazy<Runtime> = Lazy::new(|| {{
    ensure_tls();
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to create Tokio runtime")
}});

static MOVIEBOX: Lazy<{mb_struct}> = Lazy::new(|| {{ ensure_tls(); {mb_ctor} }});
{fourk_static}
{dramachi_static}

fn get_str(env: &mut JNIEnv, s: JString) -> String {{
    env.get_string(&s).map(|v| v.into()).unwrap_or_default()
}}

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {{
    env.new_string(s).expect("JNI string").into_raw()
}}

fn ok_json(val: serde_json::Value) -> String {{
    json!({{ "ok": true, "data": val }}).to_string()
}}

fn err_json(msg: impl ToString) -> String {{
    json!({{ "ok": false, "error": msg.to_string() }}).to_string()
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeInit<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, data_dir: JString<'local>,
) {{
    ensure_tls();
    let dir = get_str(&mut env, data_dir);
    if !dir.is_empty() {{
        unsafe {{
            std::env::set_var("HOME", &dir);
            std::env::set_var("XDG_CONFIG_HOME", &dir);
            std::env::set_var("XDG_DATA_HOME", &dir);
            std::env::set_var("XDG_CACHE_HOME", &dir);
        }}
    }}
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeHomepage<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, page: jint,
) -> jstring {{
    ensure_tls();
    let page = (page.max(1)) as usize;
    let out = RUNTIME.block_on(async move {{
        match MOVIEBOX.get_homepage("0", page).await {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(format!("{{e:?}}")),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeSearch<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, provider: JString<'local>, query: JString<'local>, page: jint,
) -> jstring {{
    ensure_tls();
    let provider = get_str(&mut env, provider).to_lowercase();
    let query = get_str(&mut env, query);
    let page = (page.max(1)) as usize;
    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_search}
{dramachi_search}
            _ => Provider::search(&*MOVIEBOX, &query, page).await.map(|v| serde_json::to_value(v).unwrap_or_default()),
        }};
        match res {{ Ok(v) => ok_json(v), Err(e) => err_json(format!("{{e:?}}")) }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeDetails<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, provider: JString<'local>, id: JString<'local>,
) -> jstring {{
    ensure_tls();
    let provider = get_str(&mut env, provider).to_lowercase();
    let id = get_str(&mut env, id);
    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_details}
{dramachi_details}
            _ => Provider::details(&*MOVIEBOX, &id).await.map(|v| serde_json::to_value(v).unwrap_or_default()),
        }};
        match res {{ Ok(v) => ok_json(v), Err(e) => err_json(format!("{{e:?}}")) }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeStreams<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, provider: JString<'local>, id: JString<'local>, season: jint, episode: jint,
) -> jstring {{
    ensure_tls();
    let provider = get_str(&mut env, provider).to_lowercase();
    let id = get_str(&mut env, id);
    let season = season.max(0) as usize;
    let episode = episode.max(0) as usize;
    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_streams}
{dramachi_streams}
            _ => ReleaseProvider::episode_streams(&*MOVIEBOX, &id, season, episode).await.map(|v| serde_json::to_value(v).unwrap_or_default()),
        }};
        match res {{ Ok(v) => ok_json(v), Err(e) => err_json(format!("{{e:?}}")) }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeResolveUrl<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, provider: JString<'local>, url: JString<'local>,
) -> jstring {{
    ensure_tls();
    let provider = get_str(&mut env, provider).to_lowercase();
    let url = get_str(&mut env, url);
    let out = RUNTIME.block_on(async move {{
        let res: Result<serde_json::Value, String> = match provider.as_str() {{
{resolve_arm}
            _ => Ok(json!({{ "url": url }})),
        }};
        match res {{ Ok(v) => ok_json(v), Err(e) => err_json(e) }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeCaptions<'local>(
    mut env: JNIEnv<'local>, _class: JClass<'local>, subject_id: JString<'local>, resource_id: JString<'local>,
) -> jstring {{
    ensure_tls();
    let subject_id = get_str(&mut env, subject_id);
    let resource_id = get_str(&mut env, resource_id);
    let out = RUNTIME.block_on(async move {{
        match MOVIEBOX.get_ext_captions(&subject_id, &resource_id).await {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(format!("{{e:?}}")),
        }}
    }});
    to_jstring(&mut env, out)
}}
"""
    (out_dir / "src/lib.rs").write_text(lib_rs, encoding="utf-8")


if __name__ == "__main__":
    main()
