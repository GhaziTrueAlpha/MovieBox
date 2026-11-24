//! JNI bridge exposing the MovieBox-Tui provider stack to the Android app.
//! Every native method returns a JSON string:
//!   success: {"ok":true,"data":...}
//!   failure: {"ok":false,"error":"..."}

use jni::objects::{JClass, JObject, JString};
use jni::sys::{jint, jstring};
use jni::JNIEnv;
use moviebox_tui::providers::moviebox::adapt;
use moviebox_tui::providers::moviebox::client::MovieBoxClient;
use moviebox_tui::providers::dramachi::DramachiClient;
use moviebox_tui::providers::fourkhdhub::FourKHdHubClient;
use moviebox_tui::providers::models::{
    CatalogItem, MediaDetails, MediaType, PlaybackSource, ProviderKind, Release, ResolutionIntent,
    SubtitleOption,
};
use moviebox_tui::providers::{Provider, ReleaseProvider};
use once_cell::sync::{Lazy, OnceCell};
use serde_json::{json, Value};
use std::sync::Once;
use tokio::runtime::Runtime;

static NDK_INIT: Once = Once::new();
static TLS_INIT: Once = Once::new();

fn ensure_tls() {
    TLS_INIT.call_once(|| {
        let _ = rustls::crypto::ring::default_provider().install_default();
    });
}

static RUNTIME: Lazy<Runtime> = Lazy::new(|| {
    ensure_tls();
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()
        .expect("failed to build tokio runtime")
});

static MOVIEBOX: Lazy<MovieBoxClient> = Lazy::new(MovieBoxClient::default);
static DRAMACHI: Lazy<DramachiClient> = Lazy::new(DramachiClient::new);
static FOURK: OnceCell<FourKHdHubClient> = OnceCell::new();

fn fourk() -> Option<FourKHdHubClient> {
    FOURK
        .get_or_try_init(|| FourKHdHubClient::new())
        .ok()
        .cloned()
}

fn jstr(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(|v| v.into()).unwrap_or_default()
}

fn to_jstring(env: &mut JNIEnv, s: &str) -> jstring {
    env.new_string(s).expect("jstring").into_raw()
}

fn ok_json(data: Value) -> String {
    json!({ "ok": true, "data": data }).to_string()
}

fn err_json(msg: &str) -> String {
    json!({ "ok": false, "error": msg }).to_string()
}

fn catalog_items_json(items: &[CatalogItem]) -> Value {
    let list: Vec<Value> = items
        .iter()
        .map(|item| {
            json!({
                "id": item.id.value,
                "title": item.title,
                "year": item.year.clone().unwrap_or_default(),
                "type": if item.media_type == MediaType::Series { "series" } else { "movie" },
                "poster": item.poster_url.clone().unwrap_or_default(),
                "overview": "",
                "rating": "",
            })
        })
        .collect();
    json!({ "items": list })
}

fn releases_json(releases: &[Release]) -> Value {
    let list: Vec<Value> = releases.iter().filter_map(|rel| serde_json::to_value(rel).ok()).collect();
    json!(list)
}

fn mirror_headers_json(headers: &[(String, String)]) -> Value {
    let pairs: Vec<Value> = headers.iter().map(|(k, v)| json!({ "k": k, "v": v })).collect();
    json!(pairs)
}

fn pick_direct(release: &Release) -> Option<Value> {
    for mirror in &release.mirrors {
        let lower = mirror.resolver_url.to_lowercase();
        let playable = mirror.direct_file
            || lower.contains(".mp4")
            || lower.contains(".mkv")
            || lower.contains(".m3u8")
            || lower.contains(".mpd")
            || lower.contains("r2.dev")
            || lower.contains("workers.dev")
            || lower.contains("cloudfront")
            || lower.contains("googlevideo");
        if playable {
            return Some(json!({
                "url": mirror.resolver_url,
                "label": mirror.label,
                "headers": mirror_headers_json(&mirror.headers),
            }));
        }
    }
    None
}

fn details_json(details: &MediaDetails, releases: &[Release]) -> Value {
    let is_series = details.media_type == MediaType::Series || !details.seasons.is_empty();
    let episodes: Vec<Value> = details
        .seasons
        .iter()
        .flat_map(|season| {
            season.episodes.iter().map(|ep| {
                json!({
                    "season": ep.season,
                    "number": ep.number,
                    "title": ep.title.clone().unwrap_or_else(|| format!("Episode {}", ep.number)),
                })
            })
        })
        .collect();
    json!({
        "media_type": if is_series { "series" } else { "movie" },
        "episodes": episodes,
        "releases": releases_json(releases),
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeInit<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    context: JObject<'local>,
    data_dir: JString<'local>,
) {
    ensure_tls();
    NDK_INIT.call_once(|| {
        if let Ok(vm) = env.get_java_vm() {
            if let Ok(global_ctx) = env.new_global_ref(context) {
                unsafe {
                    let ctx_ptr = global_ctx.as_raw() as *mut std::ffi::c_void;
                    let vm_ptr = vm.get_java_vm_pointer() as *mut std::ffi::c_void;
                    std::mem::forget(global_ctx);
                    ndk_context::initialize_android_context(vm_ptr, ctx_ptr);
                }
            }
        }
    });
    let dir = jstr(&mut env, &data_dir);
    if !dir.is_empty() {
        let fs_cfg = std::path::PathBuf::from(&dir).join("config");
        let fs_data = std::path::PathBuf::from(&dir).join("data");
        let fs_cache = std::path::PathBuf::from(&dir).join("cache");
        let _ = std::fs::create_dir_all(&fs_cfg);
        let _ = std::fs::create_dir_all(&fs_data);
        let _ = std::fs::create_dir_all(&fs_cache);
        unsafe {
            std::env::set_var("HOME", &dir);
            std::env::set_var("TMPDIR", &dir);
            std::env::set_var("XDG_CONFIG_HOME", &fs_cfg);
            std::env::set_var("XDG_DATA_HOME", &fs_data);
            std::env::set_var("XDG_CACHE_HOME", &fs_cache);
            std::env::set_var("MOVIEBOX_CONFIG_DIR", &fs_cfg);
            std::env::set_var("MOVIEBOX_DATA_DIR", &fs_data);
            std::env::set_var("MOVIEBOX_CACHE_DIR", &fs_cache);
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeHomepage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    page: jint,
) -> jstring {
    let page = (page.max(1)) as usize;
    let out = RUNTIME.block_on(async move {
        match MOVIEBOX.get_homepage("2", page).await {
            Ok(payload) => {
                let (items, _metrics) = adapt::moviebox_homepage_json_to_catalog(&payload);
                Ok(catalog_items_json(&items))
            }
            Err(e) => Err(e.to_string()),
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeSearch<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    query: JString<'local>,
    page: jint,
) -> jstring {
    let provider = jstr(&mut env, &provider).to_lowercase();
    let query = jstr(&mut env, &query);
    let page = (page.max(1)) as usize;
    let out = RUNTIME.block_on(async move {
        let res: Result<Vec<CatalogItem>, String> = match provider.as_str() {
            "4khdhub" | "fourkhdhub" => {
                match fourk() {
                    Some(client) => Provider::search(&client, &query, page)
                        .await
                        .map_err(|e| e.user_message(ProviderKind::FourKHdHub)),
                    None => Err("4KHDHub client unavailable".to_string()),
                }
            }
            "dramachi" => Provider::search(&*DRAMACHI, &query, page)
                .await
                .map_err(|e| e.user_message(ProviderKind::Dramachi)),
            _ => Provider::search(&*MOVIEBOX, &query, page)
                .await
                .map_err(|e| e.user_message(ProviderKind::MovieBox)),
        };
        match res {
            Ok(items) => Ok(catalog_items_json(&items)),
            Err(e) => Err(e),
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeDetails<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    id: JString<'local>,
) -> jstring {
    let provider = jstr(&mut env, &provider).to_lowercase();
    let id = jstr(&mut env, &id);
    let out = RUNTIME.block_on(async move {
        let res: Result<MediaDetails, String> = match provider.as_str() {
            "4khdhub" | "fourkhdhub" => {
                match fourk() {
                    Some(client) => Provider::details(&client, &id)
                        .await
                        .map_err(|e| e.user_message(ProviderKind::FourKHdHub)),
                    None => Err("4KHDHub client unavailable".to_string()),
                }
            }
            "dramachi" => Provider::details(&*DRAMACHI, &id)
                .await
                .map_err(|e| e.user_message(ProviderKind::Dramachi)),
            _ => Provider::details(&*MOVIEBOX, &id)
                .await
                .map_err(|e| e.user_message(ProviderKind::MovieBox)),
        };
        match res {
            Ok(details) => {
                let is_series = details.media_type == MediaType::Series || !details.seasons.is_empty();
                let releases: Vec<Release> = if is_series {
                    Vec::new()
                } else {
                    let res: Result<Vec<Release>, String> = match provider.as_str() {
                        "4khdhub" | "fourkhdhub" => {
                            match fourk() {
                                Some(client) => ReleaseProvider::episode_streams(&client, &id, 0, 0)
                                    .await
                                    .map_err(|e| e.user_message(ProviderKind::FourKHdHub)),
                                None => Err("4KHDHub client unavailable".to_string()),
                            }
                        }
                        "dramachi" => ReleaseProvider::episode_streams(&*DRAMACHI, &id, 0, 0)
                            .await
                            .map_err(|e| e.user_message(ProviderKind::Dramachi)),
                        _ => ReleaseProvider::episode_streams(&*MOVIEBOX, &id, 0, 0)
                            .await
                            .map_err(|e| e.user_message(ProviderKind::MovieBox)),
                    };
                    res.unwrap_or_default()
                };
                Ok(details_json(&details, &releases))
            }
            Err(e) => Err(e),
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeStreams<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    id: JString<'local>,
    season: jint,
    episode: jint,
) -> jstring {
    let provider = jstr(&mut env, &provider).to_lowercase();
    let id = jstr(&mut env, &id);
    let season = season.max(0) as usize;
    let episode = episode.max(0) as usize;
    let out = RUNTIME.block_on(async move {
        let res: Result<Vec<Release>, String> = match provider.as_str() {
            "4khdhub" | "fourkhdhub" => {
                match fourk() {
                    Some(client) => ReleaseProvider::episode_streams(&client, &id, season, episode)
                        .await
                        .map_err(|e| e.user_message(ProviderKind::FourKHdHub)),
                    None => Err("4KHDHub client unavailable".to_string()),
                }
            }
            "dramachi" => ReleaseProvider::episode_streams(&*DRAMACHI, &id, season, episode)
                .await
                .map_err(|e| e.user_message(ProviderKind::Dramachi)),
            _ => ReleaseProvider::episode_streams(&*MOVIEBOX, &id, season, episode)
                .await
                .map_err(|e| e.user_message(ProviderKind::MovieBox)),
        };
        match res {
            Ok(releases) => Ok(json!({ "releases": releases_json(&releases) })),
            Err(e) => Err(e),
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeResolve<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    provider: JString<'local>,
    subject_id: JString<'local>,
    release_json: JString<'local>,
    failed_url: JString<'local>,
) -> jstring {
    let provider = jstr(&mut env, &provider).to_lowercase();
    let subject_id = jstr(&mut env, &subject_id);
    let release_json = jstr(&mut env, &release_json);
    let failed_url = jstr(&mut env, &failed_url);
    let out = RUNTIME.block_on(async move {
        let Ok(release) = serde_json::from_str::<Release>(&release_json) else {
            return Err("could not parse release".to_string());
        };
        match provider.as_str() {
            "4khdhub" | "fourkhdhub" => {
                let client = match fourk() {
                    Some(c) => c,
                    None => return Err("4KHDHub client unavailable".to_string()),
                };
                let source: PlaybackSource = client
                    .resolve_release(&release, ResolutionIntent::Playback)
                    .await
                    .map_err(|e| e.user_message())
                    .map_err(|m| m.to_string())?;
                Ok(json!({
                    "url": source.url,
                    "label": source.source_label,
                    "headers": mirror_headers_json(&source.headers),
                }))
            }
            "moviebox" | "" => {
                if let Some(direct) = pick_direct(&release) {
                    return Ok(direct);
                }
                let rid = release.resource_id.clone().unwrap_or_default();
                if rid.is_empty() {
                    return Err("No recoverable stream".to_string());
                }
                let payload = MOVIEBOX
                    .get_resource_position(&subject_id, &rid, &failed_url)
                    .await
                    .map_err(|e| e.to_string())?;
                let recovered = adapt::moviebox_resource_position_json_to_release(&payload, &release, MOVIEBOX.user_agent())
                    .ok_or_else(|| "No recoverable stream".to_string())?;
                match pick_direct(&recovered) {
                    Some(direct) => Ok(direct),
                    None => Err("No recoverable stream".to_string()),
                }
            }
            _ => {
                match pick_direct(&release) {
                    Some(direct) => Ok(direct),
                    None => Err("No recoverable stream".to_string()),
                }
            }
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_moviebox_android_data_NativeMovieBox_nativeCaptions<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    subject_id: JString<'local>,
    resource_id: JString<'local>,
) -> jstring {
    let subject_id = jstr(&mut env, &subject_id);
    let resource_id = jstr(&mut env, &resource_id);
    let out = RUNTIME.block_on(async move {
        match MOVIEBOX.get_ext_captions(&subject_id, &resource_id).await {
            Ok(payload) => {
                let options: Vec<SubtitleOption> = adapt::captions_json_to_options(&payload);
                let list: Vec<Value> = options.iter().map(|sub| {
                    json!({ "lan": sub.name, "lanName": sub.name, "url": sub.url })
                }).collect();
                Ok(json!({ "captions": list }))
            }
            Err(e) => Err(e.to_string()),
        }
    });
    to_jstring(&mut env, &match out {
        Ok(data) => ok_json(data),
        Err(e) => err_json(&e),
    })
}
