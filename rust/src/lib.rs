//! JNI bridge to Brave's adblock-rust engine.
//! The Engine lives on one dedicated worker thread (it is not guaranteed Send/Sync),
//! and the JNI functions talk to it over channels.

use adblock::lists::{FilterSet, ParseOptions};
use adblock::request::Request;
use adblock::Engine;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jstring};
use jni::JNIEnv;
use std::sync::mpsc::{channel, Receiver, Sender};
use std::sync::{Mutex, OnceLock};

enum Msg {
    Load(String, Sender<bool>),
    Check(String, String, String, Sender<bool>),
    Cosmetic(String, Sender<String>),
}

static TX: OnceLock<Mutex<Sender<Msg>>> = OnceLock::new();

fn sender() -> Sender<Msg> {
    TX.get_or_init(|| {
        let (tx, rx) = channel();
        std::thread::Builder::new()
            .name("adblock".into())
            .stack_size(16 * 1024 * 1024)
            .spawn(move || worker(rx))
            .expect("failed to spawn adblock thread");
        Mutex::new(tx)
    })
    .lock()
    .unwrap()
    .clone()
}

fn worker(rx: Receiver<Msg>) {
    let mut engine: Option<Engine> = None;
    while let Ok(msg) = rx.recv() {
        match msg {
            Msg::Load(rules, reply) => {
                let lines: Vec<String> = rules.lines().map(|s| s.to_string()).collect();
                let mut set = FilterSet::new(false);
                set.add_filters(&lines, ParseOptions::default());
                engine = Some(Engine::from_filter_set(set, true));
                let _ = reply.send(true);
            }
            Msg::Check(url, source, rtype, reply) => {
                let blocked = match (&engine, Request::new(&url, &source, &rtype)) {
                    (Some(e), Ok(req)) => e.check_network_request(&req).matched,
                    _ => false,
                };
                let _ = reply.send(blocked);
            }
            Msg::Cosmetic(url, reply) => {
                let css = match &engine {
                    Some(e) => {
                        let res = e.url_cosmetic_resources(&url);
                        // One rule per selector so a single invalid selector can't break the rest.
                        res.hide_selectors
                            .iter()
                            .take(4000)
                            .map(|s| format!("{}{{display:none!important}}\n", s))
                            .collect::<String>()
                    }
                    None => String::new(),
                };
                let _ = reply.send(css);
            }
        }
    }
}

fn get(env: &mut JNIEnv, s: &JString) -> Option<String> {
    env.get_string(s).ok().map(String::from)
}

#[no_mangle]
pub extern "system" fn Java_com_tvbrowser_AdBlock_nativeLoad(
    mut env: JNIEnv,
    _class: JClass,
    rules: JString,
) -> jboolean {
    let Some(rules) = get(&mut env, &rules) else { return 0 };
    let (tx, rx) = channel();
    if sender().send(Msg::Load(rules, tx)).is_err() {
        return 0;
    }
    rx.recv().unwrap_or(false) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_tvbrowser_AdBlock_nativeShouldBlock(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
    source: JString,
    rtype: JString,
) -> jboolean {
    let (Some(u), Some(s), Some(t)) = (get(&mut env, &url), get(&mut env, &source), get(&mut env, &rtype)) else {
        return 0;
    };
    let (tx, rx) = channel();
    if sender().send(Msg::Check(u, s, t, tx)).is_err() {
        return 0;
    }
    rx.recv().unwrap_or(false) as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_tvbrowser_AdBlock_nativeCosmetic(
    mut env: JNIEnv,
    _class: JClass,
    url: JString,
) -> jstring {
    let Some(u) = get(&mut env, &url) else { return std::ptr::null_mut() };
    let (tx, rx) = channel();
    if sender().send(Msg::Cosmetic(u, tx)).is_err() {
        return std::ptr::null_mut();
    }
    let css = rx.recv().unwrap_or_default();
    env.new_string(css).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
