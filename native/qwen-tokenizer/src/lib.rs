use jni::objects::{JClass, JObject, JString};
use jni::sys::{jlong, jobjectArray};
use jni::JNIEnv;
use serde_json::Value;
use ahash::AHashMap;
use std::ffi::{CStr, CString};
use std::fs;
use std::os::raw::{c_char, c_void};
use std::path::Path;
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
            return Ok(tokenizer);
        }
    }

    // Official Qwen3 repositories currently publish vocab.json + merges.txt instead of a
    // tokenizer.json. Reconstruct the same ByteLevel BPE and restore the IDs from
    // tokenizer_config.json's added_tokens_decoder.
    let vocab_text = fs::read_to_string(directory.join("vocab.json"))
        .map_err(|error| format!("读取 vocab.json 失败：{error}"))?;
    let mut vocab: AHashMap<String, u32> =
        serde_json::from_str(&vocab_text).map_err(|error| format!("解析 vocab.json 失败：{error}"))?;
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
            added_tokens.push(AddedToken::from(content.to_owned(), true));
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
    tokenizer.add_special_tokens(&added_tokens);
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

/// Mirrors the official Qwen3ForceAlignProcessor token units as closely as possible without
/// pulling Python's nagisa/soynlp dependencies into the Android binary. Chinese Han characters
/// and mixed-language runs are separated exactly; Japanese kana and Korean are kept as words.
fn split_units(text: &str, language: &str) -> Vec<String> {
    let language = language.trim().to_ascii_lowercase();
    if language == "korean" || language == "韩语" || language == "ko" {
        return text
            .split_whitespace()
            .map(clean_token)
            .filter(|unit| !unit.is_empty())
            .collect();
    }

    if language == "japanese" || language == "日语" || language == "ja" {
        let mut units = Vec::new();
        let mut buffer = String::new();
        for ch in text.chars() {
            if is_cjk_char(ch) {
                if !buffer.is_empty() {
                    let cleaned = clean_token(&buffer);
                    if !cleaned.is_empty() {
                        units.push(cleaned);
                    }
                    buffer.clear();
                }
                units.push(ch.to_string());
            } else if ch.is_whitespace() {
                if !buffer.is_empty() {
                    let cleaned = clean_token(&buffer);
                    if !cleaned.is_empty() {
                        units.push(cleaned);
                    }
                    buffer.clear();
                }
            } else {
                buffer.push(ch);
            }
        }
        if !buffer.is_empty() {
            let cleaned = clean_token(&buffer);
            if !cleaned.is_empty() {
                units.push(cleaned);
            }
        }
        return units;
    }

    text.split_whitespace()
        .flat_map(|segment| {
            let cleaned = clean_token(segment);
            split_segment_with_chinese(&cleaned)
        })
        .filter(|unit| !unit.is_empty())
        .collect()
}

fn encode_base(handle: *mut c_void, text: &str, language: &str) -> QwenEncoded {
    if handle.is_null() {
        return QwenEncoded::empty();
    }
    let tokenizer = unsafe { &*(handle as *mut Tokenizer) };
    let timestamp_id = match tokenizer.token_to_id("<timestamp>") {
        Some(value) => value,
        None => return QwenEncoded::empty(),
    };
    let units = split_units(text, language);
    if units.is_empty() {
        return QwenEncoded::empty();
    }

    // Exact wrapper used by Qwen3ForceAlignProcessor.encode_timestamp().
    let mut input_text = String::from("<|audio_start|><|audio_pad|><|audio_end|>");
    for unit in &units {
        input_text.push_str(unit);
        input_text.push_str("<timestamp><timestamp>");
    }
    // Let the official tokenizer apply its configured BOS/EOS and AddedToken behavior.
    let encoding = match tokenizer.encode(input_text, true) {
        Ok(value) => value,
        Err(_) => return QwenEncoded::empty(),
    };
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
        return QwenEncoded::empty();
    }

    let units_json = serde_json::to_string(&units).unwrap_or_else(|_| "[]".into());
    let ids_len = ids.len();
    QwenEncoded {
        input_ids: leak_vec(ids),
        input_ids_len: ids_len,
        timestamp_positions: leak_vec(positions),
        timestamp_positions_len: units.len() * 2,
        units_json: CString::new(units_json).unwrap().into_raw(),
    }
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
            drop(Vec::from_raw_parts(
                value.input_ids,
                value.input_ids_len,
                value.input_ids_len,
            ));
        }
    }
    if !value.timestamp_positions.is_null() && value.timestamp_positions_len > 0 {
        unsafe {
            drop(Vec::from_raw_parts(
                value.timestamp_positions,
                value.timestamp_positions_len,
                value.timestamp_positions_len,
            ));
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
    let path = match CString::new(value) {
        Ok(value) => value,
        Err(_) => return 0,
    };
    qwen_tokenizer_create(path.as_ptr()) as jlong
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
    let input_c = match CString::new(input.as_str()) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let language_c = match CString::new(language_value.as_str()) {
        Ok(value) => value,
        Err(_) => return std::ptr::null_mut(),
    };
    let encoded = qwen_tokenizer_encode(
        handle as *mut c_void,
        input_c.as_ptr(),
        language_c.as_ptr(),
    );
    if encoded.input_ids_len == 0 || encoded.timestamp_positions_len == 0 {
        qwen_tokenizer_free_encoded(encoded);
        return std::ptr::null_mut();
    }

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
        std::slice::from_raw_parts(
            encoded.timestamp_positions,
            encoded.timestamp_positions_len,
        )
    };
    if env.set_int_array_region(&positions, 0, position_values).is_err()
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
            Ok(value) => value,
            Err(_) => {
                qwen_tokenizer_free_encoded(encoded);
                return std::ptr::null_mut();
            }
        };
        if env
            .set_object_array_element(&units, index as i32, unit)
            .is_err()
        {
            qwen_tokenizer_free_encoded(encoded);
            return std::ptr::null_mut();
        }
    }
    if env
        .set_object_array_element(&result, 2, units)
        .is_err()
    {
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

fn leak_vec<T>(mut values: Vec<T>) -> *mut T {
    let pointer = values.as_mut_ptr();
    std::mem::forget(values);
    pointer
}

#[cfg(test)]
mod tests {
    use super::{encode_base, is_cjk_char, load_tokenizer, split_units};
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
    fn keeps_korean_words_together() {
        assert_eq!(split_units("안녕하세요 세계", "Korean"), ["안녕하세요", "세계"]);
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
        let tokenizer = load_tokenizer(Path::new(&directory)).expect("official Qwen assets must load");
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
}
