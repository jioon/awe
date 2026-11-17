/* 结构化需求只表达业务约束；正式定义、合法推导、权限和来源证据由服务端决定。 */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  const key = () => crypto.randomUUID();
  const pending = new Set();
  let epoch = 0;
  let busy = false;
  let plannedRequest = null;
  let terminalRequest = null;
  const el = (tag, text, cls) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = String(text);
    if (cls) node.className = cls;
    return node;
  };
  class ContextChanged extends Error {}
  function notice(message, error = false) {
    const token = $("semanticConsumerToken").value.trim();
    $("notice").textContent = token
      ? String(message).split(token).join("[凭据已隐藏]")
      : String(message);
    $("notice").className = error ? "error" : "";
  }
  function controls() {
    const authenticated = !!$("semanticConsumerToken").value.trim();
    for (const id of [
      "assessSemanticRequirements",
      "readSemanticAssessment",
      "readSemanticOperation",
    ])
      $(id).disabled = busy || !authenticated;
    $("executeSemanticRequirements").disabled =
      busy ||
      !authenticated ||
      plannedRequest === null ||
      plannedRequest !== currentRequest();
    for (const id of ["newSemanticRequestKey", "resetSemanticTemplate"])
      $(id).disabled = busy;
    $("semanticRequest").readOnly = busy;
    for (const button of $("semanticCandidates").querySelectorAll("button"))
      button.disabled = busy;
  }
  function currentRequest() {
    return $("semanticRequest").value.trim();
  }
  function clearPlanning() {
    plannedRequest = null;
    $("semanticCandidates").replaceChildren();
    $("semanticAssessmentOutput").replaceChildren(
      el("p", "需求已变更，须重新评估。", "empty"),
    );
    controls();
  }
  // 任何身份变化均取消旧响应，避免前一主体的计划或结果回填给新主体。
  function clearIdentity() {
    epoch++;
    for (const controller of pending) controller.abort();
    pending.clear();
    busy = false;
    plannedRequest = null;
    terminalRequest = null;
    $("semanticAssessmentId").value = "";
    $("semanticOperationId").value = "";
    $("semanticCandidates").replaceChildren();
    $("semanticAssessmentOutput").replaceChildren(
      el("p", "身份已变更，原规划已清除。", "empty"),
    );
    $("semanticOperationOutput").replaceChildren(
      el("p", "原结果已清除。", "empty"),
    );
    controls();
    notice("身份已变更；请按当前身份重新评估需求。");
  }
  function requestObject() {
    const raw = currentRequest();
    if (new TextEncoder().encode(raw).length > 65536)
      throw Error("请求不得超过 64 KiB。");
    let document;
    try {
      // 请求仅有有界整数元数据；typed filter 的值是字符串，不作数值转换。
      document = JSON.parse(raw);
    } catch {
      throw Error("请填写完整的 JSON 请求对象。");
    }
    if (!document || Array.isArray(document) || typeof document !== "object")
      throw Error("请求必须是 JSON 对象。");
    if (document.protocolVersion !== 3 || document.intent !== "QUERY_METRIC")
      throw Error("此入口只提交 QUERY_METRIC v3 结构化需求。");
    return document;
  }
  async function api(path, method = "GET", raw) {
    const token = $("semanticConsumerToken").value.trim();
    if (!token) throw Error("请填写当前消费身份。");
    const version = epoch;
    const controller = new AbortController();
    pending.add(controller);
    try {
      const response = await fetch("/api" + path, {
        method,
        cache: "no-store",
        signal: controller.signal,
        headers: {
          Authorization: "Bearer " + token,
          ...(raw === undefined ? {} : { "Content-Type": "application/json" }),
        },
        ...(raw === undefined ? {} : { body: raw }),
      });
      const text = await response.text();
      if (version !== epoch) throw new ContextChanged();
      let document;
      try {
        document = globalThis.AweExactJson.parse(text);
      } catch {
        throw Error("服务端返回不可读响应（HTTP " + response.status + "）。");
      }
      if (!response.ok) {
        if ([401, 403].includes(response.status)) clearPlanning();
        throw Error(
          (document.message || document.error || "请求被服务端拒绝") +
            "（HTTP " +
            response.status +
            "）",
        );
      }
      return document;
    } finally {
      pending.delete(controller);
    }
  }
  function show(id, document) {
    const box = $(id);
    box.replaceChildren();
    const summary = el("div", undefined, "actions");
    for (const field of ["status", "resolution", "supply", "readiness"])
      if (document[field] !== undefined)
        summary.append(el("span", document[field], "badge"));
    if (document.result?.state)
      summary.append(el("span", document.result.state, "badge"));
    box.append(summary, el("pre", JSON.stringify(document, null, 2)));
  }
  function uuid(id) {
    const value = $(id).value.trim();
    if (
      !/^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/i.test(
        value,
      )
    )
      throw Error("请填写真实 UUID。");
    return value;
  }
  function chooseCandidate(candidate) {
    const request = requestObject();
    if (
      typeof candidate.releaseId !== "string" ||
      typeof candidate.contentHash !== "string" ||
      !/^[a-f0-9]{64}$/.test(candidate.contentHash)
    )
      throw Error("服务端候选缺少精确发布引用，无法采用。");
    request.candidateFunction = {
      releaseId: candidate.releaseId,
      contentHash: candidate.contentHash,
    };
    request.idempotencyKey = key();
    $("semanticRequest").value = JSON.stringify(request, null, 2);
    clearPlanning();
    notice("候选已写入需求；请重新评估该显式选择。");
  }
  function renderAssessment(document, allowChoose) {
    show("semanticAssessmentOutput", document);
    $("semanticCandidates").replaceChildren();
    if (!allowChoose || !Array.isArray(document.candidates)) return;
    for (const candidate of document.candidates) {
      const button = el("button", undefined, "item");
      button.append(
        el("strong", candidate.functionId || "已授权候选"),
        el("small", candidate.releaseId || "缺少发布引用"),
        el("small", "明确采用此候选，再重新评估"),
      );
      button.onclick = () => run(async () => chooseCandidate(candidate));
      $("semanticCandidates").append(button);
    }
  }
  async function run(action) {
    if (busy) return;
    busy = true;
    const version = epoch;
    controls();
    notice("");
    try {
      await action();
    } catch (error) {
      if (
        version === epoch &&
        !(error instanceof ContextChanged) &&
        error.name !== "AbortError"
      )
        notice(error.message, true);
    } finally {
      if (version === epoch) busy = false;
      controls();
    }
  }
  async function assess() {
    requestObject();
    const raw = currentRequest();
    plannedRequest = null;
    const document = await api("/requirements/assess", "POST", raw);
    if (raw !== currentRequest()) throw new ContextChanged();
    renderAssessment(document, true);
    if (document.id) $("semanticAssessmentId").value = document.id;
    if (document.status === "PLANNED") plannedRequest = raw;
    notice(
      document.status === "PLANNED"
        ? "已取得静态合法计划。实际执行仍会重新检查权限、来源和数据状态。"
        : "已取得实际能力裁决；请检查候选、未支持项或可见范围。",
    );
  }
  function nextKey() {
    const request = requestObject();
    request.idempotencyKey = key();
    $("semanticRequest").value = JSON.stringify(request, null, 2);
    terminalRequest = null;
    clearPlanning();
    $("semanticKeyState").textContent = "已生成新执行键，须重新评估。";
  }
  async function execute() {
    requestObject();
    const raw = currentRequest();
    if (raw !== plannedRequest)
      throw Error("当前需求尚未取得 PLANNED，请重新评估。");
    if (raw === terminalRequest)
      throw Error("该执行键已有终态，请生成新键后重新评估。");
    const document = await api("/data-tasks", "POST", raw);
    show("semanticOperationOutput", document);
    if (document.id) $("semanticOperationId").value = document.id;
    if (document.status !== "RUNNING") {
      terminalRequest = raw;
      // 终态结果中的 request 保留原执行键；编辑器换新键并失效旧规划，防止下一次误复用。
      const next = requestObject();
      next.idempotencyKey = key();
      $("semanticRequest").value = JSON.stringify(next, null, 2);
      plannedRequest = null;
      $("semanticKeyState").textContent =
        "本次已取得终态；编辑器已换新键，下次执行须重新评估。";
    } else
      $("semanticKeyState").textContent =
        "操作仍在运行；可用原键重试或读取操作。";
    notice("已取得实际数据操作状态，详细结果与证明范围见下方。");
  }
  function template() {
    $("semanticRequest").value = JSON.stringify(
      {
        protocolVersion: 3,
        intent: "QUERY_METRIC",
        metric: {
          releaseId: "<真实 Metric Release UUID>",
          contentHash: "<真实内容 SHA-256>",
        },
        requirements: {
          statisticalSubject: {
            entityId: "<正式统计对象 ID>",
            modelVersion: "<正式版本>",
          },
          unit: "<正式单位>",
          businessZone: "<领域定义的业务时区>",
          timeRole: "<正式时间角色>",
          measures: ["<正式度量输出名>"],
          dimensions: [],
          filters: [],
          from: "<明确的 ISO-8601 起点>",
          to: "<明确的 ISO-8601 终点>",
          view: "<OBSERVED_SNAPSHOT 或 PERIOD_COMPLETE>",
          verification: "<OBSERVE 或 REQUIRE_INDEPENDENT>",
        },
        consumer: { purpose: "<经授权的实际用途>" },
        constraints: { maxRows: 100, maxBytes: 65536 },
        idempotencyKey: key(),
      },
      null,
      2,
    );
    terminalRequest = null;
    clearPlanning();
    $("semanticKeyState").textContent =
      "此为结构模板；请填写真实定义与领域时间要求。";
  }
  $("semanticConsumerToken").addEventListener("input", clearIdentity);
  $("semanticRequest").addEventListener("input", () => {
    clearPlanning();
    $("semanticKeyState").textContent =
      "需求已变更；修改执行载荷后请使用新键。";
  });
  $("clearSemanticIdentity").onclick = () => {
    $("semanticConsumerToken").value = "";
    clearIdentity();
  };
  $("assessSemanticRequirements").onclick = () => run(assess);
  $("executeSemanticRequirements").onclick = () => run(execute);
  $("newSemanticRequestKey").onclick = () => run(async () => nextKey());
  $("resetSemanticTemplate").onclick = () => run(async () => template());
  $("readSemanticAssessment").onclick = () =>
    run(async () => {
      const document = await api(
        "/requirements/assessments/" + uuid("semanticAssessmentId"),
      );
      renderAssessment(document, false);
      plannedRequest = null;
      notice("已读取历史规划证据；新执行需要重新评估当前需求。");
    });
  $("readSemanticOperation").onclick = () =>
    run(async () => {
      const document = await api("/data-tasks/" + uuid("semanticOperationId"));
      show("semanticOperationOutput", document);
      notice("已读取当前身份可见的数据操作。");
    });
  template();
  controls();
})();
