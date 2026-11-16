/* 身份只在各自密码输入框中存在；响应只按当前页面身份世代回填，不共享作者/消费/平台凭据。 */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id),
    pending = new Set();
  let epoch = 0;
  class IdentityChanged extends Error {}
  const el = (tag, text, cls) => {
    const n = document.createElement(tag);
    if (text !== undefined) n.textContent = String(text);
    if (cls) n.className = cls;
    return n;
  };
  function notice(text, error = false) {
    let value = String(text);
    for (const input of document.querySelectorAll('input[type="password"]'))
      if (input.value) value = value.split(input.value).join("[凭据已隐藏]");
    $("notice").textContent = value;
    $("notice").className = error ? "error" : "";
  }
  function clear() {
    epoch++;
    for (const controller of pending) controller.abort();
    pending.clear();
    for (const output of document.querySelectorAll("[data-output]")) output.replaceChildren();
    window.dispatchEvent(new Event("awe-identity-cleared"));
    notice("身份已更改；旧响应已取消、结果已清除。凭据不会互相代用。");
  }
  function client(inputId) {
    const input = $(inputId);
    input.addEventListener("input", clear);
    return async (path, method = "GET", body) => {
      const token = input.value.trim();
      if (!token) throw Error("请填写本操作所需的独立身份：" + input.getAttribute("aria-label"));
      const version = epoch,
        controller = new AbortController();
      pending.add(controller);
      try {
        const response = await fetch("/api" + path, {
          method,
          cache: "no-store",
          signal: controller.signal,
          headers: {
            Authorization: "Bearer " + token,
            ...(body === undefined ? {} : { "Content-Type": "application/json" }),
          },
          ...(body === undefined ? {} : { body: JSON.stringify(body) }),
        });
        const raw = await response.text();
        if (version !== epoch) throw new IdentityChanged();
        let document;
        try {
          document = globalThis.AweExactJson.parse(raw);
        } catch {
          throw Error("服务端返回不可读响应（HTTP " + response.status + "）");
        }
        if (!response.ok)
          throw Error(
            (document.message || document.error || "服务端拒绝请求") +
              "（HTTP " +
              response.status +
              "）",
          );
        return document;
      } finally {
        pending.delete(controller);
      }
    };
  }
  async function safe(action) {
    try {
      notice("");
      return await action();
    } catch (error) {
      if (!(error instanceof IdentityChanged) && error.name !== "AbortError")
        notice(error.message, true);
      return null;
    }
  }
  function bind(id, action) {
    $(id).onclick = () =>
      safe(async () => {
        const button = $(id);
        button.disabled = true;
        try {
          return await action();
        } finally {
          button.disabled = false;
        }
      });
  }
  function text(id, optional = false) {
    const value = $(id).value.trim();
    if (!value && !optional) throw Error("请填写：" + ($(id).getAttribute("aria-label") || id));
    return value;
  }
  function uuid(id) {
    const value = text(id);
    if (!/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(value))
      throw Error(id + " 必须是实际UUID");
    return value;
  }
  function hash(id) {
    const value = text(id);
    if (!/^[a-f0-9]{64}$/.test(value)) throw Error(id + " 必须是实际64位SHA-256");
    return value;
  }
  function json(id) {
    const document = globalThis.AweExactJson.parse(text(id));
    if (!document || Array.isArray(document) || typeof document !== "object")
      throw Error("请填写规范JSON对象");
    return document;
  }
  function badge(value) {
    const n = el("span", value || "NONE", "badge");
    if (
      ["PASSED", "RESOLVED", "VERIFIED", "READY", "CONSUMED", "TECHNICALLY_CLOSED"].includes(value)
    )
      n.classList.add("good");
    else if (["FAILED", "BLOCKED", "INVALID"].includes(value)) n.classList.add("bad");
    else n.classList.add("warn");
    return n;
  }
  function show(id, document) {
    const output = $(id);
    output.replaceChildren();
    const row = el("div", undefined, "actions");
    for (const field of ["status", "resolution", "supply", "readiness", "state", "deploymentState"])
      if (document?.[field] !== undefined) row.append(badge(document[field]));
    if (document?.passed !== undefined) row.append(badge(document.passed ? "PASSED" : "FAILED"));
    output.append(row, el("pre", JSON.stringify(document, null, 2)));
    return document;
  }
  function cards(id, rows, title, action) {
    const box = $(id);
    box.replaceChildren();
    if (!rows.length)
      box.append(el("p", "当前没有可见记录；不表示企业范围不存在数据或已经验证完成。", "empty"));
    for (const row of rows) {
      const button = el("button", title(row), "item");
      button.onclick = () => safe(() => action(row));
      box.append(button);
    }
  }
  const key = () => crypto.randomUUID();
  globalThis.AweWorkbench = Object.freeze({
    $,
    el,
    client,
    safe,
    bind,
    text,
    uuid,
    hash,
    json,
    badge,
    show,
    cards,
    key,
    notice,
  });
})();
