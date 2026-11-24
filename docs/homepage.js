async function showRelease() {
  const release = document.getElementById("release");
  if (!release) return;
  let data;
  try {
    const response = await fetch("./version.json");
    if (!response.ok) return;
    data = await response.json();
  } catch {
    return;
  }

  if (typeof data?.versionName !== "string" || !data.versionName.trim()) return;
  const version = data.versionName.trim();
  document.getElementById("ver").textContent = version;

  const notes = document.getElementById("notes");
  if (typeof data.releaseNotes === "string") {
    for (const line of data.releaseNotes.split(/\r?\n/)) {
      const note = line.replace(/^\s*\d+(?:\.(?!\d)|[、．)])\s*/, "").trim();
      if (!note) continue;
      const item = document.createElement("li");
      item.textContent = note;
      notes.appendChild(item);
    }
  }
  notes.hidden = notes.childElementCount === 0;
  release.hidden = false;

  const heroVersion = document.getElementById("hero-ver");
  if (heroVersion) {
    heroVersion.textContent = "当前版本 " + version;
    heroVersion.hidden = false;
  }
}

// 滚动进入动效：内容滚到视口时才触发一次。
// 每类内容用不同的方式出现（截图擦出、分隔线画出、步骤编号弹出），
// 同一时刻进入视口的元素依次错开。
function setupReveal() {
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) return;
  if (!("IntersectionObserver" in window)) return;

  const groups = [
    [".shots .phone", "wipe"],
    [".feature", "line"],
    [".option", "fade"],
    [".guide", "steps"],
  ];
  // 被裁剪掉的元素会被判定为“不可见”，永远等不到触发，
  // 所以截图用外层的 figure 来观察，显现的仍然是截图本身。
  const revealed = new Map();
  for (const [selector, kind] of groups) {
    for (const element of document.querySelectorAll(selector)) {
      element.dataset.reveal = kind;
      revealed.set(kind === "wipe" ? element.closest(".shot") || element : element, element);
    }
  }

  document.documentElement.classList.add("motion");
  const observer = new IntersectionObserver((entries) => {
    let order = 0;
    for (const entry of entries) {
      if (!entry.isIntersecting) continue;
      const element = revealed.get(entry.target);
      element.style.setProperty("--d", Math.min(order, 4) * 90 + "ms");
      element.classList.add("is-in");
      observer.unobserve(entry.target);
      order += 1;
    }
  }, { threshold: 0.15, rootMargin: "0px 0px -6% 0px" });
  revealed.forEach((_, trigger) => observer.observe(trigger));
}

setupReveal();
showRelease();
