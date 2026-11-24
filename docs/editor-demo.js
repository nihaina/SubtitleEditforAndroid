(() => {
  const editor = document.querySelector(".editor-demo");
  if (!editor) return;

  const find = (attribute) => editor.querySelector("[data-demo-" + attribute + "]");
  const action = (name) => editor.querySelector('[data-demo-action="' + name + '"]');
  const audio = find("audio");
  const wave = find("wave");
  const canvas = find("canvas");
  const clips = find("clips");
  const playhead = find("head");
  const list = find("cues");
  const position = find("position");
  const now = find("now");
  const total = find("total");
  const speed = find("speed");
  const dialog = find("dialog");
  const form = find("form");
  const dialogTitle = find("dialog-title");
  const dialogBody = find("dialog-body");
  const confirm = find("confirm");
  const playButton = action("play");
  const ctx = canvas.getContext("2d");

  function make(tag, className, text) {
    const element = document.createElement(tag);
    if (className) element.className = className;
    if (text !== undefined) element.textContent = text;
    return element;
  }

  function button(className, text, label, onClick) {
    const element = make("button", className, text);
    element.type = "button";
    if (label) {
      element.title = label;
      element.setAttribute("aria-label", label);
    }
    element.addEventListener("click", onClick);
    return element;
  }

  const bound = (value, minimum, maximum) => Math.min(maximum, Math.max(minimum, value));
  const pad = (number, digits) => String(number).padStart(digits, "0");
  const roundSeconds = (value) => Math.round(value * 1000) / 1000;

  function timecode(seconds, decimal = ".") {
    const ms = Math.round(seconds * 1000);
    return [
      pad(Math.floor(ms / 3600000), 2),
      pad(Math.floor(ms / 60000) % 60, 2),
      pad(Math.floor(ms / 1000) % 60, 2),
    ].join(":") + decimal + pad(ms % 1000, 3);
  }

  const source = new URL("./assets/editor-demo.json", document.baseURI);
  let data;
  let cues = [];
  let duration = 0;
  let cursor = 0;
  let gain = 1;
  let showSpectrum = false;
  let selected = new Set();
  let segmentEnd = null;
  let animation = 0;
  let pointer = null;
  let previewAudio;
  let readingGeneration = 0;
  let dialogAction;
  let dialogCleanup;
  let spectrumTask;
  let rendered = [];

  function notify(text) {
    const status = find("status");
    if (status) status.textContent = text;
  }

  function stopReading() {
    readingGeneration += 1;
    previewAudio?.pause();
    previewAudio = null;
    window.speechSynthesis?.cancel();
  }

  function syncClip(cue, element) {
    element.style.setProperty("--cue-start", cue.start / duration * 100 + "%");
    element.style.setProperty("--cue-width", (cue.end - cue.start) / duration * 100 + "%");
  }

  function syncPosition() {
    now.textContent = timecode(cursor);
    position.value = cursor;
    wave.setAttribute("aria-valuenow", cursor.toFixed(3));
    wave.setAttribute("aria-valuetext", timecode(cursor));
    playhead.style.left = cursor / duration * 100 + "%";
    for (const { cue, row, clip } of rendered) {
      const active = cursor >= cue.start && cursor < cue.end;
      row.classList.toggle("is-playing", active);
      clip.classList.toggle("is-playing", active);
    }
  }

  function updateSelection() {
    for (const { cue, indexButton, row, clip } of rendered) {
      const active = selected.has(cue);
      indexButton.setAttribute("aria-pressed", String(active));
      row.classList.toggle("is-selected", active);
      clip.classList.toggle("is-selected", active);
    }
  }

  function seek(seconds) {
    cursor = roundSeconds(bound(seconds, 0, duration));
    if (audio.readyState) audio.currentTime = cursor;
    segmentEnd = null;
    syncPosition();
  }

  function renderCues() {
    rendered = cues.map((cue, index) => {
      const row = make("li", "editor-demo__cue");
      const indexButton = button("editor-demo__cue-index", index + 1, "选择第 " + (index + 1) + " 段字幕", () => {
        if (selected.has(cue)) selected.delete(cue);
        else selected.add(cue);
        updateSelection();
      });
      const main = make("div", "editor-demo__cue-main");
      const timing = make("div", "editor-demo__cue-timing");
      const start = button("editor-demo__cue-time", timecode(cue.start), "编辑开始时间", () => editCue(cue, "start"));
      const end = button("editor-demo__cue-time", timecode(cue.end), "编辑结束时间", () => editCue(cue, "end"));
      timing.append(start, make("span", "", "→"), end);
      const text = button("editor-demo__cue-text", cue.text || "编辑字幕文本", "编辑第 " + (index + 1) + " 段文本", () => editCue(cue, "text"));
      text.lang = data.language;
      main.append(timing, text);
      const actions = make("div", "editor-demo__cue-actions");
      actions.append(
        button("editor-demo__cue-jump", "▷", "跳转到字幕时间", () => seek(cue.start)),
        button("editor-demo__cue-align", "✎", "将字幕开始时间设为播放位置", () => alignCue(cue)),
      );
      row.append(indexButton, main, actions);

      const clip = make("div", "editor-demo__clip");
      const clipText = button("editor-demo__clip-label", cue.text || "新字幕", "播放第 " + (index + 1) + " 段", () => play(cue));
      clipText.lang = data.language;
      const left = make("span", "editor-demo__clip-edge");
      left.dataset.demoEdge = "start";
      const right = make("span", "editor-demo__clip-edge");
      right.dataset.demoEdge = "end";
      clip.append(clipText, left, right);
      syncClip(cue, clip);

      return { cue, row, clip, start, end, indexButton };
    });
    list.replaceChildren(...rendered.map(({ row }) => row));
    clips.replaceChildren(...rendered.map(({ clip }) => clip));
    const count = find("count");
    if (count) count.textContent = String(cues.length);
    updateSelection();
    syncPosition();
  }

  function grid(width, height) {
    const line = (x, y, endX, endY) => {
      ctx.beginPath();
      ctx.moveTo(x, y);
      ctx.lineTo(endX, endY);
      ctx.stroke();
    };
    const tick = duration > 40 || width < 400 ? 5 : 2;
    const ruler = find("ruler");
    const labels = [];
    ctx.strokeStyle = "#ffffff2b";
    ctx.fillStyle = "#bbb";
    ctx.font = "11px system-ui";
    for (let seconds = 0; seconds < duration; seconds += tick) {
      const x = Math.round(seconds / duration * width) + 0.5;
      line(x, 0, x, height);
      const label = Math.floor(seconds / 60) + ":" + pad(seconds % 60, 2);
      if (ruler) {
        const element = make("span", "", label);
        element.style.left = seconds / duration * 100 + "%";
        labels.push(element);
      } else ctx.fillText(label, x + 2, 11);
    }
    if (ruler) ruler.replaceChildren(...labels);
    for (const fraction of [0.25, 0.5, 0.75]) line(0, height * fraction, width, height * fraction);
  }

  function draw() {
    if (wave.hidden || !ctx || !duration) return;
    const width = canvas.clientWidth;
    const height = canvas.clientHeight;
    if (!width || !height) return;
    const ratio = Math.min(2, window.devicePixelRatio || 1);
    canvas.width = Math.round(width * ratio);
    canvas.height = Math.round(height * ratio);
    ctx.setTransform(ratio, 0, 0, ratio, 0, 0);

    const center = height / 2;
    if (showSpectrum && data.spectrogram?.frames?.length) {
      const frames = data.spectrogram.frames;
      const barWidth = width / frames.length;
      for (const [column, frame] of frames.entries()) {
        const barHeight = (height - 16) / frame.length;
        for (const [bin, strength] of frame.entries()) {
          const power = bound(strength * gain, 0, 1);
          ctx.fillStyle = "hsl(" + (246 - power * 218) + " 88% " + (7 + power * 49) + "%)";
          ctx.fillRect(column * barWidth, height - (bin + 1) * barHeight, barWidth + 0.6, barHeight + 0.6);
        }
      }
    } else {
      ctx.strokeStyle = "#36c6ed";
      ctx.beginPath();
      const amplitude = data.waveform;
      for (let x = 0; x < width; x += 1) {
        const peak = amplitude[Math.floor(x / width * amplitude.length)] || 0;
        const value = bound(peak * gain, 0, 1) * (center - 13);
        ctx.moveTo(x + 0.5, center - value);
        ctx.lineTo(x + 0.5, center + value);
      }
      ctx.stroke();
    }
    grid(width, height);
  }

  async function buildSpectrum() {
    const response = await fetch(audio.src);
    if (!response.ok) throw new Error("Audio unavailable");
    const context = new OfflineAudioContext(1, 16000, 16000);
    const decoded = await context.decodeAudioData(await response.arrayBuffer());
    const samples = decoded.getChannelData(0);
    const windowSize = 256;
    const bins = 32;
    const frames = Array.from({ length: 200 }, (_, column) => {
      const start = Math.min(samples.length - windowSize, Math.floor(column / 200 * samples.length));
      return Array.from({ length: bins }, (_, bin) => {
        const angle = Math.PI * 2 * (bin + 1) / windowSize;
        let real = 0;
        let imaginary = 0;
        for (let i = 0; i < windowSize; i += 1) {
          const sample = (samples[start + i] || 0) * (0.5 - 0.5 * Math.cos(i * Math.PI * 2 / (windowSize - 1)));
          real += sample * Math.cos(i * angle);
          imaginary += sample * Math.sin(i * angle);
        }
        const magnitude = Math.hypot(real, imaginary) / (windowSize / 2);
        return bound((20 * Math.log10(magnitude) + 75) / 65, 0, 1);
      });
    });
    data.spectrogram = { frames };
  }

  async function toggleSpectrum() {
    showSpectrum = !showSpectrum;
    const viewButton = action("view");
    viewButton.textContent = showSpectrum ? "频谱" : "波形";
    viewButton.setAttribute("aria-pressed", String(showSpectrum));
    if (showSpectrum && !data.spectrogram?.frames?.length) {
      try {
        spectrumTask ||= buildSpectrum();
        await spectrumTask;
      } catch {
        spectrumTask = null;
        showSpectrum = false;
        viewButton.textContent = "波形";
        viewButton.setAttribute("aria-pressed", "false");
        notify("未能生成频谱。");
      }
    }
    draw();
  }

  function updatePlayButton() {
    const playing = !audio.paused;
    playButton.dataset.playing = String(playing);
    playButton.setAttribute("aria-label", playing ? "暂停" : "播放");
    if (playButton.dataset.demoIconText !== undefined) playButton.textContent = playing ? "⏸" : "▶";
  }

  function renderFrame() {
    cursor = bound(audio.currentTime, 0, duration);
    if (segmentEnd !== null && cursor >= segmentEnd) {
      audio.pause();
      seek(segmentEnd);
    }
    syncPosition();
    if (!audio.paused) animation = requestAnimationFrame(renderFrame);
  }

  async function play(cue) {
    if (!cue && !audio.paused) {
      audio.pause();
      return;
    }
    stopReading();
    if (cue) {
      seek(cue.start);
      segmentEnd = cue.end;
    } else {
      segmentEnd = null;
      if (cursor >= duration - 0.02) seek(0);
    }
    try {
      await audio.play();
    } catch {
      notify("无法播放演示音频。");
    }
  }

  function field(label, input) {
    const wrapper = make("label", "editor-demo__field");
    wrapper.append(make("span", "", label), input);
    return wrapper;
  }

  function showDialog(title, content, onConfirm, onClose) {
    dialogTitle.textContent = title;
    dialogBody.replaceChildren(...content);
    confirm.hidden = !onConfirm;
    confirm.textContent = "确定";
    dialogAction = onConfirm;
    dialogCleanup = onClose;
    dialog.showModal();
  }

  form.addEventListener("submit", (event) => {
    if (event.submitter !== confirm || confirm.hidden || !dialogAction) return;
    event.preventDefault();
    if (dialogAction() !== false) dialog.close();
  });

  dialog.addEventListener("close", () => {
    dialogCleanup?.();
    dialogAction = null;
    dialogCleanup = null;
  });

  function editCue(cue, initialField, inserting = false) {
    const text = make("textarea");
    text.rows = 3;
    text.value = cue.text;
    const start = make("input");
    const end = make("input");
    for (const input of [start, end]) {
      input.type = "number";
      input.min = "0";
      input.max = String(duration);
      input.step = "0.001";
      input.required = true;
    }
    start.value = roundSeconds(cue.start);
    end.value = roundSeconds(cue.end);
    const fields = make("div", "editor-demo__fields");
    fields.append(field("开始时间（秒）", start), field("结束时间（秒）", end));
    const error = make("p", "editor-demo__field-error");
    error.setAttribute("role", "status");
    showDialog(inserting ? "插入字幕" : "编辑字幕", [fields, field("字幕文本", text), error], () => {
      if (end.valueAsNumber - start.valueAsNumber < 0.05) {
        error.textContent = "结束时间须比开始时间至少晚 0.05 秒。";
        return false;
      }
      Object.assign(cue, { start: start.valueAsNumber, end: end.valueAsNumber, text: text.value.trim() });
      if (inserting) cues.push(cue);
      cues.sort((first, second) => first.start - second.start);
      renderCues();
      return true;
    });
    ({ start, end, text })[initialField].focus();
  }

  function alignCue(cue) {
    if (cursor >= cue.end - 0.05) {
      notify("播放位置须早于此字幕的结束时间。");
      return;
    }
    cue.start = cursor;
    cues.sort((first, second) => first.start - second.start);
    renderCues();
    notify("已将字幕开始时间设为 " + timecode(cursor) + "。");
  }

  function insert() {
    const start = Math.min(cursor, Math.max(0, duration - 0.05));
    insertInterval(start, Math.min(duration, start + 1));
  }

  function insertInterval(first, last) {
    const start = Math.min(first, last);
    const end = Math.max(first, last);
    editCue({ start: roundSeconds(start), end: roundSeconds(end), text: "" }, "text", true);
  }

  function split() {
    const cue = cues.find((item) => cursor > item.start + 0.05 && cursor < item.end - 0.05);
    if (!cue) {
      notify("将播放头移到字幕段中再拆分。");
      return;
    }
    const index = cues.indexOf(cue);
    const parts = Array.from(cue.text);
    const fraction = (cursor - cue.start) / (cue.end - cue.start);
    const middle = bound(Math.round(parts.length * fraction), 1, parts.length);
    const tail = { ...cue, start: roundSeconds(cursor), text: parts.slice(middle).join("").trim() };
    cue.end = tail.start;
    cue.text = parts.slice(0, middle).join("").trim();
    selected.delete(cue);
    cues.splice(index + 1, 0, tail);
    renderCues();
    notify("已按播放头拆分字幕。");
  }

  function targetCues() {
    if (selected.size) return cues.filter((cue) => selected.has(cue));
    const current = cues.find((cue) => cursor >= cue.start && cursor < cue.end);
    return current ? [current] : [];
  }

  function readCue() {
    const targets = targetCues().filter(({ text }) => text.trim());
    if (!targets.length) {
      notify("请先选择要朗读的字幕。");
      return;
    }
    audio.pause();
    stopReading();
    const generation = readingGeneration;
    const synth = window.speechSynthesis;
    let index = 0;

    function next() {
      if (generation !== readingGeneration) return;
      const cue = targets[index];
      if (!cue) {
        notify("");
        return;
      }
      index += 1;

      function speakLocally() {
        if (generation !== readingGeneration) return;
        const language = data.language.toLowerCase();
        const languageCode = language.split("-")[0];
        const voices = synth?.getVoices().filter(({ localService }) => localService) ?? [];
        const voice = voices.find(({ lang }) => lang.toLowerCase() === language)
          ?? voices.find(({ lang }) => lang.toLowerCase().split("-")[0] === languageCode);
        if (!voice) {
          notify("当前浏览器没有此语言的本地朗读语音。");
          return;
        }
        const utterance = new SpeechSynthesisUtterance(cue.text);
        utterance.lang = data.language;
        utterance.voice = voice;
        utterance.onend = next;
        utterance.onerror = () => {
          if (generation === readingGeneration) notify("无法朗读该字幕。");
        };
        synth.speak(utterance);
      }

      if (cue.tts && cue.text === cue.originalText) {
        previewAudio = new Audio(new URL(cue.tts, source));
        previewAudio.addEventListener("ended", next, { once: true });
        previewAudio.play().catch(speakLocally);
      } else {
        speakLocally();
      }
    }

    notify("正在朗读选中的字幕。");
    next();
  }


  function replayTranscription() {
    const targets = targetCues();
    const originals = data.cues.filter((sample) =>
      !targets.length || targets.some((cue) => sample.start < cue.end && sample.end > cue.start),
    );
    const text = originals.map((cue) => cue.text).join("\n");
    audio.pause();
    stopReading();

    const log = make("pre", "editor-demo__log");
    log.setAttribute("aria-live", "polite");
    const result = make("p", "editor-demo__transcription", text);
    result.lang = data.language;
    result.hidden = true;
    const model = data.asr?.model || "SenseVoice";
    const hint = make("p", "", data.asr?.source === "synthesis"
      ? "回放 " + model + " 对示例音频的转录结果。"
      : "回放 " + model + " 的转录结果。");
    const trace = data.asr?.logs?.length ? data.asr.logs.map(String) : ["读取音频片段", "检测语音区间", "识别语音，生成字幕"];
    let step = 0;
    let timer = 0;
    const apply = targets.length && text ? () => {
      for (const cue of targets) {
        const overlap = originals.filter((sample) => sample.start < cue.end && sample.end > cue.start);
        if (overlap.length) cue.text = overlap.map((sample) => sample.text).join("\n");
      }
      renderCues();
      notify("已应用转录结果。");
    } : null;
    showDialog("快速转录", [hint, log, result], apply, () => clearTimeout(timer));
    confirm.hidden = true;
    confirm.textContent = "应用转录结果";
    function advance() {
      if (step < trace.length) {
        log.textContent += (step ? "\n" : "") + trace[step];
        step += 1;
        timer = window.setTimeout(advance, 350);
        return;
      }
      result.hidden = false;
      confirm.hidden = !apply;
      if (!text) result.textContent = "该区间没有可回放的识别结果。";
    }
    advance();
  }

  function saveSrt() {
    const content = cues.map((cue, index) => [
      index + 1,
      timecode(cue.start, ",") + " --> " + timecode(cue.end, ","),
      cue.text,
    ].join("\n")).join("\n\n");
    const url = URL.createObjectURL(new Blob([content + "\n"], { type: "text/plain;charset=utf-8" }));
    const link = make("a");
    link.href = url;
    link.download = "TsumugiSub-demo.srt";
    link.click();
    setTimeout(() => URL.revokeObjectURL(url), 500);
  }

  function menu(kind) {
    const item = (name, onClick) => button("editor-demo__menu-item", name, "", () => {
      dialog.close();
      onClick();
    });
    if (kind === "file") {
      showDialog("文件", [
        item("下载字幕（SRT）", saveSrt),
        item("重置演示", reset),
      ]);
      return;
    }
    showDialog("编辑", [
      item("全选", () => { selected = new Set(cues); updateSelection(); }),
      item("取消选择", () => { selected.clear(); updateSelection(); }),
      item("删除选中的字幕", () => {
        if (!selected.size) {
          notify("请先选择要删除的字幕。");
          return;
        }
        cues = cues.filter((cue) => !selected.has(cue));
        selected.clear();
        renderCues();
      }),
    ]);
  }

  function reset() {
    audio.pause();
    stopReading();
    selected.clear();
    cues = data.cues.map((cue) => ({ ...cue, originalText: cue.text }));
    seek(0);
    renderCues();
  }

  function pointerTime(event) {
    const rect = wave.getBoundingClientRect();
    return roundSeconds(bound((event.clientX - rect.left) / rect.width * duration, 0, duration));
  }

  wave.addEventListener("pointerdown", (event) => {
    if (!duration || event.button !== 0 || pointer) return;
    const edge = event.target.closest("[data-demo-edge]");
    if (!edge && event.target.closest("button")) return;
    audio.pause();
    const row = edge && rendered.find(({ clip }) => clip.contains(edge));
    pointer = { id: event.pointerId, edge: edge?.dataset.demoEdge, row };
    wave.setPointerCapture(event.pointerId);
    if (!edge) seek(pointerTime(event));
  });

  wave.addEventListener("pointermove", (event) => {
    if (pointer?.id !== event.pointerId) return;
    const point = pointerTime(event);
    if (!pointer.row) {
      seek(point);
      return;
    }
    const { cue, clip, start, end } = pointer.row;
    if (pointer.edge === "start") cue.start = roundSeconds(bound(point, 0, cue.end - 0.05));
    else cue.end = roundSeconds(bound(point, cue.start + 0.05, duration));
    syncClip(cue, clip);
    start.textContent = timecode(cue.start);
    end.textContent = timecode(cue.end);
    syncPosition();
  });

  wave.addEventListener("lostpointercapture", (event) => {
    if (pointer?.id !== event.pointerId) return;
    if (pointer.row) {
      cues.sort((first, second) => first.start - second.start);
      renderCues();
    }
    pointer = null;
  });

  wave.addEventListener("keydown", (event) => {
    if (!duration || event.target !== wave) return;
    const offsets = { ArrowLeft: -0.25, ArrowDown: -0.25, ArrowRight: 0.25, ArrowUp: 0.25 };
    if (event.key === "Home") seek(0);
    else if (event.key === "End") seek(duration);
    else if (event.key in offsets) seek(cursor + offsets[event.key]);
    else return;
    event.preventDefault();
  });

  audio.addEventListener("loadedmetadata", () => {
    audio.currentTime = Math.min(cursor, audio.duration);
  });
  audio.addEventListener("play", () => {
    updatePlayButton();
    animation = requestAnimationFrame(renderFrame);
  });
  audio.addEventListener("pause", () => {
    cancelAnimationFrame(animation);
    updatePlayButton();
  });
  audio.addEventListener("ended", () => {
    cursor = duration;
    syncPosition();
    updatePlayButton();
  });
  position.addEventListener("input", () => seek(position.valueAsNumber));
  speed.addEventListener("change", () => { audio.playbackRate = Number(speed.value); });

  function supportHold(button, begin, move, finish) {
    let press = null;
    let suppressClick = false;
    button.addEventListener("pointerdown", (event) => {
      if (!duration || event.button !== 0 || press) return;
      suppressClick = false;
      button.setPointerCapture(event.pointerId);
      const currentPress = { id: event.pointerId, fired: false };
      press = currentPress;
      currentPress.timer = window.setTimeout(() => {
        currentPress.fired = true;
        begin();
      }, 500);
    });
    button.addEventListener("pointermove", (event) => {
      if (press?.id === event.pointerId && press.fired) move?.(event);
    });
    button.addEventListener("contextmenu", (event) => event.preventDefault());
    button.addEventListener("lostpointercapture", (event) => {
      if (press?.id !== event.pointerId) return;
      clearTimeout(press.timer);
      suppressClick = press.fired;
      if (press.fired && !press.released) finish?.(true);
      press = null;
    });
    button.addEventListener("pointerup", (event) => {
      if (press?.id !== event.pointerId) return;
      press.released = true;
      if (press.fired) finish?.(false);
    });
    button.addEventListener("click", (event) => {
      if (!suppressClick || !event.detail) return;
      suppressClick = false;
      event.stopImmediatePropagation();
    }, true);
  }

  let intervalStart;
  supportHold(action("insert"), () => {
    intervalStart = cursor;
    action("insert").setAttribute("aria-pressed", "true");
    notify("拖到结束位置后松开，完成打轴。");
  }, (event) => {
    const bounds = wave.getBoundingClientRect();
    if (event.clientY >= bounds.top && event.clientY <= bounds.bottom) seek(pointerTime(event));
  }, (cancelled) => {
    action("insert").setAttribute("aria-pressed", "false");
    if (cancelled) {
      notify("");
      return;
    }
    if (Math.abs(intervalStart - cursor) >= 0.05) insertInterval(intervalStart, cursor);
    else insert();
  });

  supportHold(action("gain-up"), () => {
    gain = 1;
    draw();
    notify("已重置振幅。");
  });

  const actions = {
    back: () => { audio.pause(); stopReading(); seek(0); },
    play: () => play(),
    collapse: () => {
      wave.hidden = !wave.hidden;
      const collapse = action("collapse");
      collapse.setAttribute("aria-expanded", String(!wave.hidden));
      collapse.textContent = wave.hidden ? "▶" : "▼";
      draw();
    },
    view: toggleSpectrum,
    "gain-down": () => { gain = Math.max(0.4, gain / 1.35); draw(); },
    "gain-up": () => { gain = Math.min(6, gain * 1.35); draw(); },
    insert,
    split,
    transcribe: replayTranscription,
    tts: readCue,
    file: () => menu("file"),
    edit: () => menu("edit"),
  };
  for (const [name, callback] of Object.entries(actions)) {
    action(name)?.addEventListener("click", () => { if (duration) callback(); });
  }

  async function load() {
    try {
      const response = await fetch(source);
      if (!response.ok) throw new Error("Demo unavailable");
      data = await response.json();
      if (!(data.duration > 0) || !Array.isArray(data.cues) || !Array.isArray(data.waveform)) {
        throw new Error("Invalid demo data");
      }
      data.language ||= document.documentElement.lang;
      duration = data.duration;
      audio.src = new URL(data.audio, source);
      position.max = String(duration);
      wave.setAttribute("aria-valuemax", String(duration));
      total.textContent = timecode(duration);
      const title = find("title");
      if (title && data.title) title.textContent = data.title;
      reset();
      updatePlayButton();
      draw();
      notify("");
      new ResizeObserver(draw).observe(wave);
    } catch {
      notify("演示素材暂时无法加载。");
    }
  }

  load();
})();
