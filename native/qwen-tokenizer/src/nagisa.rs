use flate2::read::GzDecoder;
use std::io::Read;
use std::sync::OnceLock;
use unicode_normalization::UnicodeNormalization;

static MODEL: OnceLock<Result<NagisaModel, String>> = OnceLock::new();
static MODEL_DATA: &[u8] = include_bytes!("../assets/nagisa_v001.bin.gz");

struct NagisaModel {
    window: usize,
    uni_dim: usize,
    bi_dim: usize,
    word_dim: usize,
    ctype_dim: usize,
    hidden_dim: usize,
    uni_ids: Vec<(Box<str>, usize)>,
    bi_ids: Vec<(Box<str>, usize)>,
    word_ids: Vec<(Box<str>, usize)>,
    uni: Vec<f32>,
    bi: Vec<f32>,
    word: Vec<f32>,
    ctype: Vec<f32>,
    forward: Lstm,
    backward: Lstm,
    output_weights: Vec<f32>,
    output_bias: Vec<f32>,
    transitions: Vec<f32>,
}

struct Lstm {
    input_weights: Vec<f32>,
    recurrent_weights: Vec<f32>,
    bias: Vec<f32>,
}

struct Reader<'a> {
    data: &'a [u8],
    offset: usize,
}

impl<'a> Reader<'a> {
    fn take(&mut self, length: usize) -> Result<&'a [u8], String> {
        let end = self
            .offset
            .checked_add(length)
            .ok_or_else(|| "Nagisa 模型文件长度溢出".to_owned())?;
        let bytes = self
            .data
            .get(self.offset..end)
            .ok_or_else(|| "Nagisa 模型文件已截断".to_owned())?;
        self.offset = end;
        Ok(bytes)
    }

    fn u32(&mut self) -> Result<usize, String> {
        let bytes: [u8; 4] = self.take(4)?.try_into().unwrap();
        Ok(u32::from_le_bytes(bytes) as usize)
    }

    fn mapping(&mut self) -> Result<Vec<(Box<str>, usize)>, String> {
        let count = self.u32()?;
        if count > 1_000_000 {
            return Err("Nagisa 模型词典条目数量异常".into());
        }
        let mut result = Vec::with_capacity(count);
        for _ in 0..count {
            let length = self.u32()?;
            let key: Box<str> = std::str::from_utf8(self.take(length)?)
                .map_err(|_| "Nagisa 模型词典含有无效 UTF-8".to_owned())?
                .into();
            if result
                .last()
                .is_some_and(|(previous, _): &(Box<str>, usize)| previous.as_ref() >= key.as_ref())
            {
                return Err("Nagisa 模型词典没有按词条排序".into());
            }
            result.push((key, self.u32()?));
        }
        Ok(result)
    }

    fn floats(&mut self, expected: usize) -> Result<Vec<f32>, String> {
        let count = self.u32()?;
        if count != expected {
            return Err(format!(
                "Nagisa 模型权重长度错误：期望 {expected}，实际 {count}"
            ));
        }
        let bytes = self.take(count * 4)?;
        Ok(bytes
            .chunks_exact(4)
            .map(|chunk| f32::from_le_bytes(chunk.try_into().unwrap()))
            .collect())
    }
}

impl NagisaModel {
    fn load() -> Result<Self, String> {
        let mut decoded = Vec::new();
        GzDecoder::new(MODEL_DATA)
            .read_to_end(&mut decoded)
            .map_err(|error| format!("解压 Nagisa 分词模型失败：{error}"))?;
        let mut reader = Reader {
            data: &decoded,
            offset: 0,
        };
        if reader.take(8)? != b"NAGISA01" {
            return Err("Nagisa 模型格式或版本不兼容".into());
        }
        let window = reader.u32()?;
        let uni_dim = reader.u32()?;
        let bi_dim = reader.u32()?;
        let word_dim = reader.u32()?;
        let ctype_dim = reader.u32()?;
        let hidden_dim = reader.u32()?;
        if window != 3 || hidden_dim % 2 != 0 {
            return Err("Nagisa 模型结构不受支持".into());
        }

        let uni_ids = reader.mapping()?;
        let bi_ids = reader.mapping()?;
        let word_ids = reader.mapping()?;
        let uni_rows = table_rows(&uni_ids)?;
        let bi_rows = table_rows(&bi_ids)?;
        let word_rows = table_rows(&word_ids)?;
        let input_dim = window * (uni_dim + bi_dim + ctype_dim) + 2 * word_dim;
        let gates = 2 * hidden_dim;
        let half_hidden = hidden_dim / 2;
        let uni = reader.floats(uni_rows * uni_dim)?;
        let bi = reader.floats(bi_rows * bi_dim)?;
        let word = reader.floats(word_rows * word_dim)?;
        let ctype = reader.floats(7 * ctype_dim)?;
        let forward = Lstm {
            input_weights: reader.floats(input_dim * gates)?,
            recurrent_weights: reader.floats(half_hidden * gates)?,
            bias: reader.floats(gates)?,
        };
        let backward = Lstm {
            input_weights: reader.floats(input_dim * gates)?,
            recurrent_weights: reader.floats(half_hidden * gates)?,
            bias: reader.floats(gates)?,
        };
        let output_weights = reader.floats(hidden_dim * 6)?;
        let output_bias = reader.floats(6)?;
        let transitions = reader.floats(36)?;
        if reader.offset != decoded.len() {
            return Err("Nagisa 模型文件含有未识别的数据".into());
        }

        Ok(Self {
            window,
            uni_dim,
            bi_dim,
            word_dim,
            ctype_dim,
            hidden_dim,
            uni_ids,
            bi_ids,
            word_ids,
            uni,
            bi,
            word,
            ctype,
            forward,
            backward,
            output_weights,
            output_bias,
            transitions,
        })
    }

    fn segment(&self, text: &str) -> Vec<String> {
        let normalized = preprocess(text);
        let chars = normalized.chars().collect::<Vec<_>>();
        if chars.is_empty() {
            return Vec::new();
        }
        let lower = chars.iter().collect::<String>().to_lowercase();
        let lower_chars = lower.chars().collect::<Vec<_>>();
        let inputs = self.features(&lower_chars);
        let forward = run_lstm(&inputs, &self.forward, self.hidden_dim / 2, false);
        let backward = run_lstm(&inputs, &self.backward, self.hidden_dim / 2, true);
        let mut observations = vec![[0.0f32; 6]; chars.len()];
        for index in 0..chars.len() {
            for unit in 0..self.hidden_dim {
                let value = if unit < self.hidden_dim / 2 {
                    forward[index][unit]
                } else {
                    backward[index][unit - self.hidden_dim / 2]
                };
                for tag in 0..6 {
                    observations[index][tag] += value * self.output_weights[unit * 6 + tag];
                }
            }
            for tag in 0..6 {
                observations[index][tag] += self.output_bias[tag];
            }
        }
        let tags = viterbi(&observations, &self.transitions);
        let mut words = Vec::new();
        let mut current = String::new();
        for (ch, tag) in chars.into_iter().zip(tags) {
            match tag {
                3 => {
                    if !current.is_empty() {
                        words.push(std::mem::take(&mut current));
                    }
                    words.push(ch.to_string());
                }
                2 => {
                    current.push(ch);
                    words.push(std::mem::take(&mut current));
                }
                _ => current.push(ch),
            }
        }
        if !current.is_empty() {
            words.push(current);
        }
        words
    }

    fn features(&self, chars: &[char]) -> Vec<Vec<f32>> {
        let lower = chars.iter().collect::<String>();
        let mut byte_offsets = lower
            .char_indices()
            .map(|(offset, _)| offset)
            .collect::<Vec<_>>();
        byte_offsets.push(lower.len());
        let lower_chars = chars;
        let unigram_oov = lookup(&self.uni_ids, "oov").unwrap_or(0);
        let bigram_oov = lookup(&self.bi_ids, "oov").unwrap_or(0);
        let word_oov = lookup(&self.word_ids, "oov").unwrap_or(0);
        let unigram_pad = lookup(&self.uni_ids, "pad").unwrap_or(1);
        let bigram_pad = lookup(&self.bi_ids, "pad").unwrap_or(1);
        let char_ids = lower_chars
            .iter()
            .map(|ch| self.char_type(*ch))
            .collect::<Vec<_>>();
        let bigrams = (0..lower_chars.len())
            .map(|index| {
                let mut bigram = lower_chars[index].to_string();
                if let Some(next) = lower_chars.get(index + 1) {
                    bigram.push(*next);
                } else {
                    bigram.push_str("<E>");
                }
                lookup(&self.bi_ids, &bigram).unwrap_or(bigram_oov)
            })
            .collect::<Vec<_>>();

        let mut features = Vec::with_capacity(chars.len());
        for index in 0..chars.len() {
            let mut row = Vec::with_capacity(
                self.window * (self.uni_dim + self.bi_dim + self.ctype_dim) + 2 * self.word_dim,
            );
            let radius = self.window / 2;
            for offset in 0..self.window {
                let position = index as isize + offset as isize - radius as isize;
                let id = if position < 0 || position >= chars.len() as isize {
                    unigram_pad
                } else {
                    lookup(&self.uni_ids, &lower_chars[position as usize].to_string())
                        .unwrap_or(unigram_oov)
                };
                append_embedding(&mut row, &self.uni, self.uni_dim, id);
            }
            for offset in 0..self.window {
                let position = index as isize + offset as isize - radius as isize;
                let id = if position < 0 || position >= chars.len() as isize {
                    bigram_pad
                } else {
                    bigrams[position as usize]
                };
                append_embedding(&mut row, &self.bi, self.bi_dim, id);
            }
            for offset in 0..self.window {
                let position = index as isize + offset as isize - radius as isize;
                let id = if position < 0 || position >= chars.len() as isize {
                    6
                } else {
                    char_ids[position as usize]
                };
                append_embedding(&mut row, &self.ctype, self.ctype_dim, id);
            }
            self.append_word_features(&mut row, &lower, &byte_offsets, index, word_oov, true);
            self.append_word_features(&mut row, &lower, &byte_offsets, index, word_oov, false);
            features.push(row);
        }
        features
    }

    fn append_word_features(
        &self,
        row: &mut Vec<f32>,
        lower: &str,
        byte_offsets: &[usize],
        index: usize,
        oov: usize,
        starts_here: bool,
    ) {
        let mut sum = vec![0.0f32; self.word_dim];
        let minimum = if starts_here {
            index
        } else {
            index.saturating_sub(7)
        };
        let char_count = byte_offsets.len() - 1;
        let maximum = if starts_here {
            (index + 8).min(char_count)
        } else {
            index + 1
        };
        let mut matches = 0;
        for boundary in minimum..maximum {
            let (start, end) = if starts_here {
                (index, boundary + 1)
            } else {
                (boundary, index + 1)
            };
            if let Some(id) = lookup(
                &self.word_ids,
                &lower[byte_offsets[start]..byte_offsets[end]],
            ) {
                add_embedding(&mut sum, &self.word, self.word_dim, id);
                matches += 1;
            }
        }
        if matches == 0 {
            add_embedding(&mut sum, &self.word, self.word_dim, oov);
        }
        row.extend(sum);
    }

    fn char_type(&self, ch: char) -> usize {
        let code = ch as u32;
        if (0x3040..=0x309f).contains(&code) {
            0
        } else if (0x30a1..=0x30fa).contains(&code) {
            1
        } else if (0x4e00..=0x9fa5).contains(&code) {
            2
        } else if ch.is_ascii_alphabetic() {
            3
        } else if ch.is_ascii_digit() {
            4
        } else {
            5
        }
    }
}

fn table_rows(mapping: &[(Box<str>, usize)]) -> Result<usize, String> {
    mapping
        .iter()
        .map(|(_, id)| *id)
        .max()
        .and_then(|max| max.checked_add(1))
        .ok_or_else(|| "Nagisa 模型词典为空".to_owned())
}

fn lookup(mapping: &[(Box<str>, usize)], key: &str) -> Option<usize> {
    mapping
        .binary_search_by(|(word, _)| word.as_ref().cmp(key))
        .ok()
        .map(|index| mapping[index].1)
}

fn append_embedding(output: &mut Vec<f32>, table: &[f32], dim: usize, index: usize) {
    let start = index * dim;
    output.extend_from_slice(&table[start..start + dim]);
}

fn add_embedding(output: &mut [f32], table: &[f32], dim: usize, index: usize) {
    let start = index * dim;
    for (target, value) in output.iter_mut().zip(&table[start..start + dim]) {
        *target += *value;
    }
}

fn run_lstm(inputs: &[Vec<f32>], lstm: &Lstm, half_hidden: usize, reverse: bool) -> Vec<Vec<f32>> {
    let gates = half_hidden * 4;
    let mut output = vec![vec![0.0f32; half_hidden]; inputs.len()];
    let mut hidden = vec![0.0f32; half_hidden];
    let mut cell = vec![0.0f32; half_hidden];
    let mut gate_values = vec![0.0f32; gates];
    for step in 0..inputs.len() {
        let index = if reverse {
            inputs.len() - step - 1
        } else {
            step
        };
        gate_values.copy_from_slice(&lstm.bias);
        for (input_index, value) in inputs[index].iter().enumerate() {
            let weights = &lstm.input_weights[input_index * gates..(input_index + 1) * gates];
            for gate in 0..gates {
                gate_values[gate] += value * weights[gate];
            }
        }
        for (hidden_index, value) in hidden.iter().enumerate() {
            let weights = &lstm.recurrent_weights[hidden_index * gates..(hidden_index + 1) * gates];
            for gate in 0..gates {
                gate_values[gate] += value * weights[gate];
            }
        }
        for unit in 0..half_hidden {
            let input_gate = sigmoid(gate_values[unit]);
            let forget_gate = sigmoid(gate_values[half_hidden + unit]);
            let output_gate = sigmoid(gate_values[2 * half_hidden + unit]);
            let candidate = gate_values[3 * half_hidden + unit].tanh();
            cell[unit] = forget_gate * cell[unit] + input_gate * candidate;
            hidden[unit] = output_gate * cell[unit].tanh();
        }
        output[index].copy_from_slice(&hidden);
    }
    output
}

fn sigmoid(value: f32) -> f32 {
    1.0 / (1.0 + (-value).exp())
}

fn viterbi(observations: &[[f32; 6]], transitions: &[f32]) -> Vec<usize> {
    let mut scores = [-1.0e10f64; 6];
    scores[4] = 0.0;
    let mut backpointers = vec![[0usize; 6]; observations.len()];
    for (index, observation) in observations.iter().enumerate() {
        let mut next = [0.0f64; 6];
        for tag in 0..6 {
            let mut best = scores[0] + transitions[tag * 6] as f64;
            let mut previous = 0;
            for from in 1..6 {
                let value = scores[from] + transitions[tag * 6 + from] as f64;
                if value > best {
                    best = value;
                    previous = from;
                }
            }
            next[tag] = best + observation[tag] as f64;
            backpointers[index][tag] = previous;
        }
        scores = next;
    }
    let mut best = scores[0] + transitions[5] as f64;
    let mut tag = 0;
    for from in 1..6 {
        let value = scores[from] + transitions[30 + from] as f64;
        if value > best {
            best = value;
            tag = from;
        }
    }
    let mut result = vec![0; observations.len()];
    for index in (0..observations.len()).rev() {
        result[index] = tag;
        tag = backpointers[index][tag];
    }
    result
}

fn preprocess(text: &str) -> String {
    text.trim_end()
        .nfkc()
        .collect::<String>()
        .replace('İ', "I")
        .replace(' ', "\u{3000}")
}

pub(super) fn split(text: &str) -> Result<Vec<String>, String> {
    let model = MODEL.get_or_init(NagisaModel::load);
    let model = model
        .as_ref()
        .map_err(|error| format!("加载 Nagisa 日语分词模型失败：{error}"))?;
    Ok(model
        .segment(text)
        .into_iter()
        .map(|word| {
            word.chars()
                .filter(|ch| ch.is_alphanumeric() || *ch == '\'')
                .collect()
        })
        .filter(|word: &String| !word.is_empty())
        .collect())
}
