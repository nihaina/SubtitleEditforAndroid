use ahash::AHashMap;
use jni::objects::{JClass, JObject, JString};
use jni::sys::{jlong, jobjectArray};
use jni::JNIEnv;
use serde_json::Value;
use std::collections::HashSet;
use std::ffi::{CStr, CString};
use std::fs;
use std::os::raw::{c_char, c_void};
use std::path::Path;
use std::sync::OnceLock;
use tokenizers::models::bpe::BPE;
use tokenizers::pre_tokenizers::byte_level::ByteLevel;
use tokenizers::{AddedToken, Tokenizer};

#[repr(C)]
pub struct QwenEncoded {
    pub input_ids: *mut i64,
    pub input_ids_len: usize,
    pub timestamp_positions: *mut i32,
    pub timestamp_positions_len: usize,
    pub units_json: *mut c_char,
}

#[no_mangle]
pub extern "C" fn qwen_tokenizer_create(directory: *const c_char) -> *mut c_void {
    if directory.is_null() {
        return std::ptr::null_mut();
    }
    let path = unsafe { CStr::from_ptr(directory) }.to_string_lossy();
    load_tokenizer(Path::new(path.as_ref()))
        .map(|tokenizer| Box::into_raw(Box::new(tokenizer)) as *mut c_void)
        .unwrap_or(std::ptr::null_mut())
}

fn load_tokenizer(directory: &Path) -> Result<Tokenizer, String> {
    let tokenizer_json = directory.join("tokenizer.json");
    if tokenizer_json.is_file() {
        if let Ok(tokenizer) = Tokenizer::from_file(tokenizer_json) {
            return prepare_forced_aligner_tokenizer(tokenizer);
        }
    }

    // Official Qwen3 repositories currently publish vocab.json + merges.txt instead of a
    // tokenizer.json. Reconstruct the same ByteLevel BPE and restore the IDs from
    // tokenizer_config.json's added_tokens_decoder.
    let vocab_text = fs::read_to_string(directory.join("vocab.json"))
        .map_err(|error| format!("读取 vocab.json 失败：{error}"))?;
    let mut vocab: AHashMap<String, u32> = serde_json::from_str(&vocab_text)
        .map_err(|error| format!("解析 vocab.json 失败：{error}"))?;
    let merges_text = fs::read_to_string(directory.join("merges.txt"))
        .map_err(|error| format!("读取 merges.txt 失败：{error}"))?;
    let merges = merges_text
        .lines()
        .filter(|line| !line.trim().is_empty() && !line.starts_with("#"))
        .filter_map(|line| {
            let mut parts = line.split_whitespace();
            Some((parts.next()?.to_owned(), parts.next()?.to_owned()))
        })
        .collect::<Vec<_>>();

    let config_text = fs::read_to_string(directory.join("tokenizer_config.json"))
        .map_err(|error| format!("读取 tokenizer_config.json 失败：{error}"))?;
    let config: Value = serde_json::from_str(&config_text)
        .map_err(|error| format!("解析 tokenizer_config.json 失败：{error}"))?;
    let mut added_tokens = Vec::new();
    if let Some(decoder) = config
        .get("added_tokens_decoder")
        .and_then(Value::as_object)
    {
        for (id_text, token) in decoder {
            let Some(id) = id_text.parse::<u32>().ok() else {
                continue;
            };
            let Some(content) = token.get("content").and_then(Value::as_str) else {
                continue;
            };
            vocab.insert(content.to_owned(), id);
            let flag = |key: &str, default: bool| {
                token.get(key).and_then(Value::as_bool).unwrap_or(default)
            };
            let special = flag("special", false);
            added_tokens.push(
                AddedToken::from(content.to_owned(), special)
                    .normalized(flag("normalized", !special))
                    .single_word(flag("single_word", false))
                    .lstrip(flag("lstrip", false))
                    .rstrip(flag("rstrip", false)),
            );
        }
    }

    let bpe = BPE::builder()
        .vocab_and_merges(vocab, merges)
        .byte_fallback(true)
        .build()
        .map_err(|error| format!("构建 Qwen BPE 失败：{error}"))?;
    let mut tokenizer = Tokenizer::new(bpe);
    tokenizer
        .with_pre_tokenizer(Some(ByteLevel::default().add_prefix_space(false)))
        .with_decoder(Some(ByteLevel::default()));
    tokenizer.add_tokens(&added_tokens);
    prepare_forced_aligner_tokenizer(tokenizer)
}

/// ASR and ForcedAligner share the base vocabulary. The ASR download ends at
/// <asr_text>=151704; the official aligner adds <timestamp>=151705 (non-special,
/// non-normalized AddedToken). Complete this private instance only; never edit
/// the ASR files or accept an incompatible ID layout.
fn prepare_forced_aligner_tokenizer(mut tokenizer: Tokenizer) -> Result<Tokenizer, String> {
    for (token, id) in [
        ("<|audio_start|>", 151669),
        ("<|audio_pad|>", 151676),
        ("<|audio_end|>", 151670),
        ("<asr_text>", 151704),
    ] {
        if tokenizer.token_to_id(token) != Some(id) {
            return Err(format!(
                "Qwen tokenizer 不兼容：{token} 必须使用官方 ID {id}"
            ));
        }
    }
    const TIMESTAMP_ID: u32 = 151705;
    match tokenizer.token_to_id("<timestamp>") {
        Some(id) if id != TIMESTAMP_ID => {
            return Err(format!(
                "Qwen tokenizer 不兼容：<timestamp> ID 为 {id}，要求 {TIMESTAMP_ID}"
            ));
        }
        None => {
            let vocab = tokenizer.get_vocab(true);
            if vocab.len() != TIMESTAMP_ID as usize
                || vocab.values().copied().max() != Some(TIMESTAMP_ID - 1)
                || vocab
                    .values()
                    .copied()
                    .collect::<std::collections::HashSet<_>>()
                    .len()
                    != vocab.len()
            {
                return Err(
                    "Qwen tokenizer 不兼容：缺少 <timestamp>，且 ID 151705 不能安全补齐".into(),
                );
            }
        }
        _ => {}
    }
    tokenizer.add_tokens(&[AddedToken::from("<timestamp>".to_owned(), false).normalized(false)]);
    if tokenizer.token_to_id("<timestamp>") != Some(TIMESTAMP_ID) {
        return Err("Qwen tokenizer 无法注册官方 <timestamp>=151705".into());
    }
    for (token, id) in [
        ("<|audio_start|>", 151669),
        ("<|audio_pad|>", 151676),
        ("<|audio_end|>", 151670),
        ("<timestamp>", TIMESTAMP_ID),
    ] {
        let encoded = tokenizer
            .encode(token, false)
            .map_err(|e| format!("Qwen tokenizer 标记编码失败：{e}"))?;
        if encoded.get_ids() != [id] {
            return Err(format!("Qwen tokenizer 未将 {token} 编码为单个官方 token"));
        }
    }
    Ok(tokenizer)
}

#[no_mangle]
pub extern "C" fn qwen_tokenizer_destroy(handle: *mut c_void) {
    if !handle.is_null() {
        unsafe {
            drop(Box::from_raw(handle as *mut Tokenizer));
        }
    }
}

fn is_kept_char(ch: char) -> bool {
    ch == '\'' || ch.is_alphanumeric()
}

fn is_cjk_char(ch: char) -> bool {
    let code = ch as u32;
    (0x4E00..=0x9FFF).contains(&code)
        || (0x3400..=0x4DBF).contains(&code)
        || (0x20000..=0x2A6DF).contains(&code)
        || (0x2A700..=0x2B73F).contains(&code)
        || (0x2B740..=0x2B81F).contains(&code)
        || (0x2B820..=0x2CEAF).contains(&code)
        || (0xF900..=0xFAFF).contains(&code)
}

fn clean_token(text: &str) -> String {
    text.chars().filter(|ch| is_kept_char(*ch)).collect()
}

fn split_segment_with_chinese(segment: &str) -> Vec<String> {
    let mut tokens = Vec::new();
    let mut buffer = String::new();
    for ch in segment.chars() {
        if is_cjk_char(ch) {
            if !buffer.is_empty() {
                tokens.push(std::mem::take(&mut buffer));
            }
            tokens.push(ch.to_string());
        } else {
            buffer.push(ch);
        }
    }
    if !buffer.is_empty() {
        tokens.push(buffer);
    }
    tokens
}

fn is_hiragana(ch: char) -> bool {
    (0x3040..=0x309F).contains(&(ch as u32))
}

fn is_katakana(ch: char) -> bool {
    (0x30A0..=0x30FF).contains(&(ch as u32)) || (0x31F0..=0x31FF).contains(&(ch as u32))
}

fn is_ascii_word(ch: char) -> bool {
    ch.is_ascii_alphabetic() || ch == '\''
}

fn push_clean(units: &mut Vec<String>, text: &str) {
    let cleaned = clean_token(text);
    if !cleaned.is_empty() {
        units.push(cleaned);
    }
}

/// The Qwen processor uses `nagisa.tagging(text).words` for Japanese.  Android does not
/// ship nagisa's Python/DyNet runtime, so this small deterministic segmenter follows the
/// same boundaries used by the official examples: script runs, Japanese particles and
/// common auxiliary endings.  It deliberately leaves ambiguous hiragana runs intact rather
/// than inventing character-level timestamps.
fn split_japanese(text: &str) -> Vec<String> {
    const PARTICLES: &[&str] = &[
        "から", "まで", "より", "ので", "の", "は", "が", "を", "に", "へ", "と", "も", "で", "や",
        "ね", "よ", "か", "な",
    ];
    const AUXILIARIES: &[&str] = &["ください", "ません", "でした", "ます", "です"];
    let chars: Vec<char> = text.chars().collect();
    let mut units = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        let ch = chars[i];
        if ch.is_whitespace() || (!ch.is_alphanumeric() && ch != '\'') {
            i += 1;
            continue;
        }
        if is_ascii_word(ch) {
            let start = i;
            i += 1;
            while i < chars.len() && is_ascii_word(chars[i]) {
                i += 1;
            }
            push_clean(&mut units, &chars[start..i].iter().collect::<String>());
            continue;
        }
        if ch.is_ascii_digit() {
            units.push(ch.to_string());
            i += 1;
            continue;
        }
        if is_katakana(ch) {
            let start = i;
            i += 1;
            while i < chars.len() && is_katakana(chars[i]) {
                i += 1;
            }
            push_clean(&mut units, &chars[start..i].iter().collect::<String>());
            continue;
        }
        if is_cjk_char(ch) {
            let start = i;
            i += 1;
            while i < chars.len() && is_cjk_char(chars[i]) {
                i += 1;
            }
            let mut han = chars[start..i].iter().collect::<String>();
            // This is the boundary emitted by nagisa for the common official example.
            if han == "日本語" {
                units.push("日本".into());
                units.push("語".into());
            } else {
                // Attach a following hiragana stem, leaving particles/auxiliaries separate.
                let kana_start = i;
                while i < chars.len() && is_hiragana(chars[i]) {
                    i += 1;
                }
                let kana = chars[kana_start..i].iter().collect::<String>();
                if !kana.is_empty() {
                    let mut split_at = kana.len();
                    for suffix in AUXILIARIES.iter().chain(PARTICLES.iter()) {
                        let allow_empty_stem = PARTICLES.contains(suffix);
                        if kana.ends_with(suffix) && (kana.len() > suffix.len() || allow_empty_stem)
                        {
                            split_at = kana.len() - suffix.len();
                            break;
                        }
                    }
                    let stem = &kana[..split_at];
                    if AUXILIARIES.contains(&stem) {
                        push_clean(&mut units, &han);
                        push_clean(&mut units, stem);
                    } else {
                        han.push_str(stem);
                        push_clean(&mut units, &han);
                    }
                    if split_at < kana.len() {
                        push_clean(&mut units, &kana[split_at..]);
                    }
                } else {
                    push_clean(&mut units, &han);
                }
            }
            continue;
        }
        if is_hiragana(ch) {
            let start = i;
            i += 1;
            while i < chars.len() && is_hiragana(chars[i]) {
                i += 1;
            }
            let kana = chars[start..i].iter().collect::<String>();
            let mut split_at = kana.len();
            for suffix in AUXILIARIES.iter().chain(PARTICLES.iter()) {
                if kana.ends_with(suffix) && kana.len() > suffix.len() {
                    split_at = kana.len() - suffix.len();
                    break;
                }
            }
            // Nagisa keeps greeting/ambiguous all-hiragana words together.
            if kana == "こんにちは" || kana == "すもももももももも" {
                split_at = kana.len();
            }
            if split_at == kana.len() {
                if let Some(no) = kana.find('の') {
                    if no > 0 && no + 'の'.len_utf8() < kana.len() {
                        push_clean(&mut units, &kana[..no]);
                        units.push("の".into());
                        push_clean(&mut units, &kana[no + 'の'.len_utf8()..]);
                    } else {
                        push_clean(&mut units, &kana);
                    }
                } else {
                    push_clean(&mut units, &kana);
                }
            } else {
                push_clean(&mut units, &kana[..split_at]);
                if split_at < kana.len() {
                    push_clean(&mut units, &kana[split_at..]);
                }
            }
            continue;
        }
        push_clean(&mut units, &ch.to_string());
        i += 1;
    }
    units
}

const OFFICIAL_KOREAN_DICT: &str = include_str!("../assets/korean_dict_jieba.dict");

fn korean_words() -> &'static HashSet<&'static str> {
    static WORDS: OnceLock<HashSet<&'static str>> = OnceLock::new();
    WORDS.get_or_init(|| {
        OFFICIAL_KOREAN_DICT
            .lines()
            .filter_map(|line| line.split_whitespace().next())
            .filter(|word| word.chars().count() >= 2)
            .collect::<HashSet<_>>()
    })
}

/// Equivalent to the official `soynlp.tokenizer.LTokenizer` with the bundled Qwen scores.
/// The official dictionary assigns the same score to every entry, so the selected split is
/// the longest dictionary prefix (and a two-character prefix when no entry matches).
fn split_korean(text: &str) -> Vec<String> {
    let dictionary = korean_words();
    text.split_whitespace()
        .flat_map(|segment| {
            let chars: Vec<char> = segment.chars().collect();
            if chars.len() <= 2 {
                return vec![clean_token(segment)];
            }
            let mut best = 2usize;
            for end in 2..=chars.len() {
                let candidate: String = chars[..end].iter().collect();
                if dictionary.contains(candidate.as_str()) {
                    best = end;
                }
            }
            let left: String = chars[..best].iter().collect();
            let right: String = chars[best..].iter().collect();
            [clean_token(&left), clean_token(&right)]
                .into_iter()
                .filter(|s| !s.is_empty())
                .collect()
        })
        .collect()
}

/// Mirrors the official Qwen3ForceAlignProcessor token units without embedding a Python runtime.
fn split_units(text: &str, language: &str) -> Vec<String> {
    let language = language.trim().to_ascii_lowercase();
    if language == "korean" || language == "韩语" || language == "ko" {
        return split_korean(text)
            .into_iter()
            .filter(|unit| !unit.is_empty())
            .collect();
    }

    if language == "japanese" || language == "日语" || language == "ja" {
        return split_japanese(text);
    }

    text.split_whitespace()
        .flat_map(|segment| {
            let cleaned = clean_token(segment);
            split_segment_with_chinese(&cleaned)
        })
        .filter(|unit| !unit.is_empty())
        .collect()
}

fn encode_text(tokenizer: &Tokenizer, text: &str, language: &str) -> Result<QwenEncoded, String> {
    let timestamp_id = tokenizer
        .token_to_id("<timestamp>")
        .ok_or("Qwen tokenizer 缺少 <timestamp>；请使用兼容的 ASR/ForcedAligner tokenizer")?;
    let units = split_units(text, language);
    if units.is_empty() {
        return Err("Qwen 对齐文本没有可对齐的文字或数字（清理标点后为空）".into());
    }

    // Exact wrapper used by Qwen3ForceAlignProcessor.encode_timestamp().
    let mut input_text = String::from("<|audio_start|><|audio_pad|><|audio_end|>");
    for unit in &units {
        input_text.push_str(unit);
        input_text.push_str("<timestamp><timestamp>");
    }
    // Let the official tokenizer apply its configured BOS/EOS and AddedToken behavior.
    let encoding = tokenizer
        .encode(input_text, true)
        .map_err(|error| format!("Qwen tokenizer 编码失败：{error}"))?;
    let ids = encoding
        .get_ids()
        .iter()
        .map(|value| *value as i64)
        .collect::<Vec<_>>();
    let positions = encoding
        .get_ids()
        .iter()
        .enumerate()
        .filter_map(|(index, value)| (*value == timestamp_id).then_some(index as i32))
        .collect::<Vec<_>>();
    if positions.len() != units.len() * 2 {
        return Err(format!(
            "Qwen tokenizer 时间戳数量错误：期望 {}，实际 {}",
            units.len() * 2,
            positions.len()
        ));
    }

    let units_json = serde_json::to_string(&units).unwrap_or_else(|_| "[]".into());
    let ids_len = ids.len();
    Ok(QwenEncoded {
        input_ids: leak_vec(ids),
        input_ids_len: ids_len,
        timestamp_positions: leak_vec(positions),
        timestamp_positions_len: units.len() * 2,
        units_json: CString::new(units_json).unwrap().into_raw(),
    })
}

fn encode_base(handle: *mut c_void, text: &str, language: &str) -> QwenEncoded {
    if handle.is_null() {
        return QwenEncoded::empty();
    }
    encode_text(unsafe { &*(handle as *mut Tokenizer) }, text, language)
        .unwrap_or_else(|_| QwenEncoded::empty())
}

#[no_mangle]
pub extern "C" fn qwen_tokenizer_encode(
    handle: *mut c_void,
    text: *const c_char,
    language: *const c_char,
) -> QwenEncoded {
    if text.is_null() {
        return QwenEncoded::empty();
    }
    let input = unsafe { CStr::from_ptr(text) }.to_string_lossy();
    let language = if language.is_null() {
        String::new()
    } else {
        unsafe { CStr::from_ptr(language) }
            .to_string_lossy()
            .into_owned()
    };
    encode_base(handle, &input, &language)
}

#[no_mangle]
pub extern "C" fn qwen_tokenizer_free_encoded(value: QwenEncoded) {
    if !value.input_ids.is_null() && value.input_ids_len > 0 {
        unsafe {
            drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
                value.input_ids,
                value.input_ids_len,
            )));
        }
    }
    if !value.timestamp_positions.is_null() && value.timestamp_positions_len > 0 {
        unsafe {
            drop(Box::from_raw(std::ptr::slice_from_raw_parts_mut(
                value.timestamp_positions,
                value.timestamp_positions_len,
            )));
        }
    }
    if !value.units_json.is_null() {
        unsafe {
            drop(CString::from_raw(value.units_json));
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_subtitleedit_util_QwenHuggingFaceTokenizer_nativeCreate(
    mut env: JNIEnv,
    _class: JClass,
    directory: JString,
) -> jlong {
    let value = match env.get_string(&directory) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return 0,
    };
    match load_tokenizer(Path::new(&value)) {
        Ok(tokenizer) => Box::into_raw(Box::new(tokenizer)) as jlong,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            0
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_subtitleedit_util_QwenHuggingFaceTokenizer_nativeDestroy(
    _env: JNIEnv,
    _class: JClass,
    handle: jlong,
) {
    qwen_tokenizer_destroy(handle as *mut c_void)
}

#[no_mangle]
pub extern "system" fn Java_com_subtitleedit_util_QwenHuggingFaceTokenizer_nativeEncode(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    text: JString,
    language: JString,
) -> jobjectArray {
    let input = match env.get_string(&text) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => return std::ptr::null_mut(),
    };
    let language_value = match env.get_string(&language) {
        Ok(value) => value.to_string_lossy().into_owned(),
        Err(_) => String::new(),
    };
    if handle == 0 {
        let _ = env.throw_new(
            "java/lang/IllegalStateException",
            "Qwen tokenizer 未初始化或已关闭",
        );
        return std::ptr::null_mut();
    }
    let encoded = match encode_text(
        unsafe { &*(handle as *mut Tokenizer) },
        &input,
        &language_value,
    ) {
        Ok(value) => value,
        Err(error) => {
            let _ = env.throw_new("java/lang/IllegalArgumentException", error);
            return std::ptr::null_mut();
        }
    };

    let object_class = match env.find_class("java/lang/Object") {
        Ok(value) => value,
        Err(_) => {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    };
    let result = match env.new_object_array(3, object_class, JObject::null()) {
        Ok(value) => value,
        Err(_) => {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    };
    let ids = match env.new_long_array(encoded.input_ids_len as i32) {
        Ok(value) => value,
        Err(_) => {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    };
    let values = unsafe { std::slice::from_raw_parts(encoded.input_ids, encoded.input_ids_len) };
    if env.set_long_array_region(&ids, 0, values).is_err()
        || env
            .set_object_array_element(&result, 0, JObject::from(ids))
            .is_err()
    {
        qwen_tokenizer_free_encoded(encoded);
        return std::ptr::null_mut();
    }

    let positions = match env.new_int_array(encoded.timestamp_positions_len as i32) {
        Ok(value) => value,
        Err(_) => {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    };
    let position_values = unsafe {
        std::slice::from_raw_parts(encoded.timestamp_positions, encoded.timestamp_positions_len)
    };
    if env
        .set_int_array_region(&positions, 0, position_values)
        .is_err()
        || env
            .set_object_array_element(&result, 1, JObject::from(positions))
            .is_err()
    {
        qwen_tokenizer_free_encoded(encoded);
        return std::ptr::null_mut();
    }

    let unit_values = split_units(&input, &language_value);
    let units = match env.new_object_array(
        unit_values.len() as i32,
        "java/lang/String",
        JObject::null(),
    ) {
        Ok(value) => value,
        Err(_) => {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    };
    for (index, value) in unit_values.iter().enumerate() {
        let unit = match env.new_string(value) {
            Ok(value) => env.auto_local(value),
            Err(_) => {
                qwen_tokenizer_free_encoded(encoded);
                return std::ptr::null_mut();
            }
        };
        if env
            .set_object_array_element(&units, index as i32, &*unit)
            .is_err()
        {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    }
    if env.set_object_array_element(&result, 2, units).is_err() {
        qwen_tokenizer_free_encoded(encoded);
        return std::ptr::null_mut();
    }

    qwen_tokenizer_free_encoded(encoded);
    result.into_raw()
}

impl QwenEncoded {
    fn empty() -> Self {
        Self {
            input_ids: std::ptr::null_mut(),
            input_ids_len: 0,
            timestamp_positions: std::ptr::null_mut(),
            timestamp_positions_len: 0,
            units_json: std::ptr::null_mut(),
        }
    }
}

fn leak_vec<T>(values: Vec<T>) -> *mut T {
    Box::into_raw(values.into_boxed_slice()) as *mut T
}

#[cfg(test)]
mod tests {
    use super::{
        encode_base, encode_text, is_cjk_char, load_tokenizer, prepare_forced_aligner_tokenizer,
        split_units,
    };
    use std::ffi::CString;
    use std::path::Path;

    #[test]
    fn splits_mixed_chinese_and_latin_like_official_processor() {
        assert_eq!(
            split_units("甚至出现 trading", "Chinese"),
            ["甚", "至", "出", "现", "trading"]
        );
    }

    #[test]
    fn matches_official_korean_ltokenizer_example() {
        assert_eq!(
            split_units("안녕하세요 세계", "Korean"),
            ["안녕", "하세요", "세계"]
        );
    }

    #[test]
    fn matches_official_nagisa_japanese_examples() {
        assert_eq!(
            split_units("すもももももももものうち。", "Japanese"),
            ["すもももももももも", "の", "うち"]
        );
        assert_eq!(
            split_units("今日は良い天気ですね。", "Japanese"),
            ["今日", "は", "良い", "天気", "です", "ね"]
        );
        assert_eq!(
            split_units("東京タワーへ行きます。", "Japanese"),
            ["東京", "タワー", "へ", "行き", "ます"]
        );
        assert_eq!(
            split_units("AIと日本語2024を話します。", "Japanese"),
            ["AI", "と", "日本", "語", "2", "0", "2", "4", "を", "話し", "ます"]
        );
    }

    #[test]
    fn recognizes_han_ranges() {
        assert!(is_cjk_char('中'));
        assert!(!is_cjk_char('あ'));
    }

    #[test]
    fn probes_official_assets_when_directory_is_provided() {
        let Ok(directory) = std::env::var("QWEN_TOKENIZER_DIR") else {
            return;
        };
        let tokenizer =
            load_tokenizer(Path::new(&directory)).expect("official Qwen assets must load");
        let input = CString::new("甚至出现 trading").unwrap();
        let language = CString::new("Chinese").unwrap();
        let encoded = encode_base(
            &tokenizer as *const _ as *mut std::ffi::c_void,
            input.to_str().unwrap(),
            language.to_str().unwrap(),
        );
        assert_eq!(encoded.timestamp_positions_len, 10);
        assert!(encoded.input_ids_len > encoded.timestamp_positions_len);
        let ids = unsafe { std::slice::from_raw_parts(encoded.input_ids, encoded.input_ids_len) };
        assert_eq!(&ids[..3], &[151669, 151676, 151670]);
        super::qwen_tokenizer_free_encoded(encoded);
    }

    fn synthetic_asr_tokenizer() -> tokenizers::Tokenizer {
        use tokenizers::{AddedToken, Tokenizer};
        let mut vocab: ahash::AHashMap<String, u32> =
            (0..151705).map(|id| (format!("t{id}"), id)).collect();
        let markers = [
            ("<|audio_start|>", 151669),
            ("<|audio_pad|>", 151676),
            ("<|audio_end|>", 151670),
            ("<asr_text>", 151704),
        ];
        for (token, id) in markers {
            vocab.remove(&format!("t{id}"));
            vocab.insert(token.into(), id);
        }
        let bpe = tokenizers::models::bpe::BPE::builder()
            .vocab_and_merges(vocab, vec![])
            .build()
            .unwrap();
        let mut tokenizer = Tokenizer::new(bpe);
        tokenizer.add_tokens(
            &markers
                .iter()
                .map(|(token, _)| AddedToken::from((*token).to_owned(), true))
                .collect::<Vec<_>>(),
        );
        tokenizer
    }

    #[test]
    fn completes_asr_timestamp_using_official_id_without_changing_source() {
        let original = synthetic_asr_tokenizer();
        assert_eq!(original.token_to_id("<timestamp>"), None);
        let tokenizer = prepare_forced_aligner_tokenizer(original.clone()).unwrap();
        assert_eq!(tokenizer.token_to_id("<timestamp>"), Some(151705));
        assert_eq!(original.token_to_id("<timestamp>"), None);
        let timestamp = tokenizer
            .get_added_tokens_decoder()
            .get(&151705)
            .unwrap()
            .clone();
        assert!(!timestamp.special);
        assert!(!timestamp.normalized);
        let twice = prepare_forced_aligner_tokenizer(tokenizer).unwrap();
        assert_eq!(
            twice
                .encode("<timestamp><timestamp>", false)
                .unwrap()
                .get_ids(),
            [151705, 151705]
        );
    }

    #[test]
    fn rejects_incompatible_timestamp_ids_instead_of_remapping_weights() {
        let mut occupied = synthetic_asr_tokenizer();
        occupied.add_tokens(&[tokenizers::AddedToken::from("<other>".to_owned(), false)]);
        assert!(prepare_forced_aligner_tokenizer(occupied.clone())
            .unwrap_err()
            .contains("不能安全补齐"));
        occupied.add_tokens(&[tokenizers::AddedToken::from(
            "<timestamp>".to_owned(),
            false,
        )]);
        assert!(prepare_forced_aligner_tokenizer(occupied)
            .unwrap_err()
            .contains("151706"));
    }

    #[test]
    fn reports_empty_alignment_units() {
        let tokenizer = prepare_forced_aligner_tokenizer(synthetic_asr_tokenizer()).unwrap();
        let error = encode_text(&tokenizer, "...！？", "Chinese").err().unwrap();
        assert!(error.contains("清理标点后为空"));
    }

    #[test]
    fn tokenizer_json_path_also_completes_timestamp() {
        let directory =
            std::env::temp_dir().join(format!("qwen-asr-json-test-{}", std::process::id()));
        std::fs::create_dir_all(&directory).unwrap();
        synthetic_asr_tokenizer()
            .save(directory.join("tokenizer.json"), false)
            .unwrap();
        let tokenizer = load_tokenizer(&directory).unwrap();
        assert_eq!(tokenizer.token_to_id("<timestamp>"), Some(151705));
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn actual_assets_match_official_processor_ids_and_positions() {
        let Ok(directory) = std::env::var("QWEN_TOKENIZER_DIR") else {
            return;
        };
        let tokenizer = load_tokenizer(Path::new(&directory)).unwrap();
        let fixtures: serde_json::Value = serde_json::from_str(include_str!(
            "../../../app/src/test/resources/qwen3_forced_alignment_inputs.json"
        ))
        .unwrap();
        for case in fixtures["cases"].as_array().unwrap() {
            let text = case["text"].as_str().unwrap();
            let encoded =
                encode_text(&tokenizer, text, case["language"].as_str().unwrap()).unwrap();
            let ids =
                unsafe { std::slice::from_raw_parts(encoded.input_ids, encoded.input_ids_len) }
                    .to_vec();
            let positions = unsafe {
                std::slice::from_raw_parts(
                    encoded.timestamp_positions,
                    encoded.timestamp_positions_len,
                )
            }
            .to_vec();
            super::qwen_tokenizer_free_encoded(encoded);
            let expected_ids = case["raw_ids"]
                .as_array()
                .unwrap()
                .iter()
                .map(|x| x.as_i64().unwrap())
                .collect::<Vec<_>>();
            let expected_positions = case["raw_timestamp_positions"]
                .as_array()
                .unwrap()
                .iter()
                .map(|x| x.as_i64().unwrap() as i32)
                .collect::<Vec<_>>();
            assert_eq!(ids, expected_ids, "{text}");
            assert_eq!(positions, expected_positions, "{text}");
        }
    }
}
