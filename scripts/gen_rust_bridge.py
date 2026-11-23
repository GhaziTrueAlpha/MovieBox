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

    # Check if new() takes 0 args, or Default is implemented
    m_new = re.search(r"pub\s+fn\s+new\s*\(([^)]*)\)\s*(->\s*[^{]+)?\{", src)
    if m_new:
        args = m_new.group(1).strip()
        ret = (m_new.group(2) or "").strip()
        if args == "":
            if "Result" in ret:
                return struct_name, f"{struct_name}::new().expect(\"init {struct_name}\")"
            return struct_name, f"{struct_name}::new()"
    if f"impl Default for {struct_name}" in src or "#[derive(" in src and "Default" in src:
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

    mb_struct, mb_ctor = find_client_struct_and_ctor(mb_client_rs, "MovieBoxClient")
    fk_struct, fk_ctor = find_client_struct_and_ctor(fk_client_rs, "FourkHdHubClient")
    dr_struct, dr_ctor = find_client_struct_and_ctor(dr_client_rs, "DramachiClient")
    resolve_fn = find_resolve_method(prov_mod_rs, fk_mod_rs, fk_client_rs)

    print(f"Detected MovieBox: {mb_struct} -> {mb_ctor}")
    print(f"Detected 4KHDHub:  {fk_struct} -> {fk_ctor}")
    print(f"Detected Dramachi: {dr_struct} -> {dr_ctor}")
    print(f"Detected resolve method: {resolve_fn}")

    # Check if Dramachi exists and implements Provider + ReleaseProvider
    dr_mod_src = read_text(vendor_dir / "src/providers/dramachi/mod.rs")
    has_dramachi = dr_client_rs.exists() and "impl Provider for" in dr_mod_src
    has_dramachi_rel = has_dramachi and "ReleaseProvider for" in dr_mod_src

    fk_mod_src = read_text(fk_mod_rs)
    has_fourk = fk_client_rs.exists() and "impl Provider for" in fk_mod_src
    has_fourk_rel = has_fourk and "ReleaseProvider for" in fk_mod_src

    cargo_toml = f"""[package]
name = "moviebox-ffi"
version = "0.1.0"
edition = "2024"

[lib]
name = "moviebox_ffi"
crate-type = ["cdylib"]

[dependencies]
moviebox-tui = {{ path = "../vendor/MovieBox-Tui" }}
jni = "0.21"
once_cell = "1.20"
serde_json = "1.0"
tokio = {{ version = "1", features = ["rt-multi-thread"] }}
"""
    (out_dir / "Cargo.toml").write_text(cargo_toml, encoding="utf-8")

    dramachi_import = f"use moviebox_tui::providers::dramachi::client::{dr_struct};" if has_dramachi else ""
    fourk_import = f"use moviebox_tui::providers::fourkhdhub::client::{fk_struct};" if has_fourk else ""

    dramachi_static = f"static DRAMACHI: Lazy<{dr_struct}> = Lazy::new(|| {dr_ctor});" if has_dramachi else ""
    fourk_static = f"static FOURK: Lazy<{fk_struct}> = Lazy::new(|| {fk_ctor});" if has_fourk else ""

    fourk_search_arm = '        "4khdhub" => FOURK.search(&query, page).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_fourk else ""
    dramachi_search_arm = '        "dramachi" => DRAMACHI.search(&query, page).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_dramachi else ""

    fourk_details_arm = '        "4khdhub" => FOURK.details(&id).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_fourk else ""
    dramachi_details_arm = '        "dramachi" => DRAMACHI.details(&id).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_dramachi else ""

    fourk_streams_arm = '        "4khdhub" => FOURK.episode_streams(&id, season, episode).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_fourk_rel else ""
    dramachi_streams_arm = '        "dramachi" => DRAMACHI.episode_streams(&id, season, episode).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),' if has_dramachi_rel else ""

    resolve_body = (
        f'        "4khdhub" => FOURK.{resolve_fn}(&url).await.map(|v| serde_json::to_value(v).unwrap_or_default()).map_err(|e| e.to_string()),'
        if (has_fourk and resolve_fn)
        else ""
    )

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

static RUNTIME: Lazy<Runtime> = Lazy::new(|| {{
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("Failed to create Tokio runtime")
}});

static MOVIEBOX: Lazy<{mb_struct}> = Lazy::new(|| {mb_ctor});
{fourk_static}
{dramachi_static}

fn get_str(env: &mut JNIEnv, s: JString) -> String {{
    env.get_string(&s).map(|v| v.into()).unwrap_or_default()
}}

fn to_jstring(env: &mut JNIEnv, s: String) -> jstring {{
    env.new_string(s)
        .expect("Failed to allocate JNI string")
        .into_raw()
}}

fn ok_json(val: serde_json::Value) -> String {{
    json!({{ "ok": true, "data": val }}).to_string()
}}

fn err_json(msg: impl ToString) -> String {{
    json!({{ "ok": false, "error": msg.to_string() }}).to_string()
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeHomepage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    page: jint,
) -> jstring {{
    let page = (page.max(1)) as usize;
    let out = RUNTIME.block_on(async move {{
        match MOVIEBOX.get_homepage("0", page).await {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeSearch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    query: JString<'local>,
    page: jint,
) -> jstring {{
    let provider = get_str(&mut env, provider).to_lowercase();
    let query = get_str(&mut env, query);
    let page = (page.max(1)) as usize;

    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_search_arm}
{dramachi_search_arm}
            _ => MOVIEBOX.search(&query, page).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),
        }};
        match res {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeDetails<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    id: JString<'local>,
) -> jstring {{
    let provider = get_str(&mut env, provider).to_lowercase();
    let id = get_str(&mut env, id);

    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_details_arm}
{dramachi_details_arm}
            _ => MOVIEBOX.details(&id).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),
        }};
        match res {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeStreams<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    id: JString<'local>,
    season: jint,
    episode: jint,
) -> jstring {{
    let provider = get_str(&mut env, provider).to_lowercase();
    let id = get_str(&mut env, id);
    let season = season.max(0) as usize;
    let episode = episode.max(0) as usize;

    let out = RUNTIME.block_on(async move {{
        let res = match provider.as_str() {{
{fourk_streams_arm}
{dramachi_streams_arm}
            _ => MOVIEBOX.episode_streams(&id, season, episode).await.and_then(|v| Ok(serde_json::to_value(v).unwrap_or_default())),
        }};
        match res {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeResolveUrl<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    url: JString<'local>,
) -> jstring {{
    let provider = get_str(&mut env, provider).to_lowercase();
    let url = get_str(&mut env, url);

    let out = RUNTIME.block_on(async move {{
        let res: Result<serde_json::Value, String> = match provider.as_str() {{
{resolve_body}
            _ => Ok(json!({{ "url": url }})),
        }};
        match res {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeCaptions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subject_id: JString<'local>,
    resource_id: JString<'local>,
) -> jstring {{
    let subject_id = get_str(&mut env, subject_id);
    let resource_id = get_str(&mut env, resource_id);

    let out = RUNTIME.block_on(async move {{
        match MOVIEBOX.get_ext_captions(&subject_id, &resource_id).await {{
            Ok(v) => ok_json(v),
            Err(e) => err_json(e),
        }}
    }});
    to_jstring(&mut env, out)
}}
"""
    (out_dir / "src/lib.rs").write_text(lib_rs, encoding="utf-8")
    print("Generated rust-bridge/Cargo.toml and rust-bridge/src/lib.rs")


if __name__ == "__main__":
    main()
