/* 消费页面只提交封闭 DataTask；身份、解析、源端访问与验证仍由服务端决定。 */
(() => {
  "use strict";
  const $ = (id) => document.getElementById(id);
  let activeToken = "";
  let identityEpoch = 0;
  let capabilities = [];
  let selectedCapability = null;
  let currentTask = null;
  let busy = false;
  const pending = new Set();
  const key = () => crypto.randomUUID();
  const element = (tag, text, className) => {
    const node = document.createElement(tag);
    if (text !== undefined) node.textContent = String(text);
    if (className) node.className = className;
    return node;
  };
  const encode = (value) => JSON.stringify(value, null, 2);
  function notice(message, error = false) {
    let safe = String(message);
    for (const token of [activeToken, $("consumerToken").value])
      if (token) safe = safe.split(token).join("[凭据已隐藏]");
    $("notice").textContent = safe;
    $("notice").className = error ? "error" : "";
  }
  class SessionChanged extends Error {}
  async function safe(action) {
    try {
      await action();
    } catch (error) {
      if (!(error instanceof SessionChanged) && error.name !== "AbortError")
        notice(error.message, true);
    }
  }
  function badge(value) {
    const node = element("span", value || "未取得", "badge");
    if (
      [
        "EXTRACTED",
        "QUERIED",
        "RECORDS",
        "ZERO",
        "RESOLVED",
        "DIRECT",
        "ALLOWED",
        "PASSED",
        "READY",
      ].includes(value)
    )
      node.classList.add("good");
    else if (["DENIED", "FAILED", "BLOCKED"].includes(value)) node.classList.add("bad");
    else node.classList.add("warn");
    return node;
  }
  function metadata(rows) {
    const table = element("table", undefined, "metadata");
    for (const [label, value] of rows) {
      const row = element("tr");
      row.append(
        element("th", label),
        element(
          "td",
          value === undefined || value === null || value === ""
            ? "未取得 / 不适用"
            : typeof value === "object"
              ? encode(value)
              : value,
        ),
      );
      table.append(row);
    }
    return table;
  }
  function card(title, description, action, active = false) {
    const node = element("button", undefined, "item" + (active ? " active" : ""));
    node.append(element("strong", title), element("small", description));
    node.onclick = () => safe(action);
    return node;
  }
  function taskTime(value) {
    if (value === undefined || value === null) return "时间未取得";
    const date = new Date(
      typeof value === "number" || /^\d{13}$/.test(String(value)) ? Number(value) : value,
    );
    return Number.isNaN(date.getTime())
      ? String(value)
      : new Intl.DateTimeFormat("zh-CN", {
          timeZone: "Asia/Shanghai",
          dateStyle: "medium",
          timeStyle: "medium",
        }).format(date);
  }
  function clearVisibleData() {
    capabilities = [];
    selectedCapability = null;
    currentTask = null;
    $("capabilityList").replaceChildren(element("p", "尚未加载", "empty"));
    $("taskList").replaceChildren(element("p", "尚未加载", "empty"));
    $("capabilityDetails").classList.add("hidden");
    $("capabilityEmpty").classList.remove("hidden");
    $("capabilityEmpty").textContent = "请使用独立消费凭据加载实际授权能力。";
    $("taskDetail").classList.add("hidden");
    $("metricView").replaceChildren();
    $("metricPlanOutput").textContent = "尚未检查";
    $("taskHeading").textContent = "数据操作证据";
    $("operationStatus").textContent = "";
    for (const id of [
      "capabilityPointers",
      "fieldChoices",
      "consumerPurpose",
      "decisionAxes",
      "resultSummary",
      "typedRows",
      "taskPointers",
      "taskLimitations",
    ])
      $(id).replaceChildren();
    for (const id of [
      "capabilityScope",
      "taskScope",
      "taskVerification",
      "taskEvidence",
      "capabilityHeading",
      "timeScope",
    ])
      $(id).textContent = "";
    for (const id of [
      "rangeFrom",
      "rangeTo",
      "externalIssuer",
      "externalTaskId",
      "taskLookup",
      "requestMaxRows",
      "requestMaxBytes",
    ])
      $(id).value = "";
    $("requestOutput").textContent = "尚未生成请求；凭据不会写入请求正文。";
    $("taskRequestKey").value = key();
    $("sessionState").textContent = "未连接";
    $("sessionState").className = "badge";
    updateControls();
  }
  // 切换凭据时取消旧响应并清理数据，不能让前一主体的异步结果回填页面。
  function invalidateIdentity(clearToken = false) {
    identityEpoch++;
    for (const controller of pending) controller.abort();
    pending.clear();
    activeToken = "";
    busy = false;
    if (clearToken) $("consumerToken").value = "";
    clearVisibleData();
  }
  async function api(path, method = "GET", body) {
    if (!activeToken) throw Error("请先使用独立消费 Token 加载授权能力。");
    const epoch = identityEpoch;
    const controller = new AbortController();
    pending.add(controller);
    try {
      const response = await fetch("/api" + path, {
        method,
        cache: "no-store",
        signal: controller.signal,
        headers: {
          Authorization: "Bearer " + activeToken,
          ...(body === undefined ? {} : { "Content-Type": "application/json" }),
        },
        ...(body === undefined ? {} : { body: JSON.stringify(body) }),
      });
      const text = await response.text();
      if (epoch !== identityEpoch) throw new SessionChanged();
      let document;
      try {
        document = globalThis.AweExactJson.parse(text);
      } catch {
        throw Error("服务端返回了无法读取的响应（HTTP " + response.status + "）。");
      }
      if (!response.ok) {
        const message = document.message || document.error || "请求被服务端拒绝";
        if ([401, 403].includes(response.status)) invalidateIdentity();
        throw Error(message + "（HTTP " + response.status + "）");
      }
      return document;
    } finally {
      pending.delete(controller);
    }
  }
  function updateControls() {
    $("connectConsumer").disabled = busy;
    $("validateMetricPlan").disabled = busy || !activeToken || !selectedCapability;
    $("refreshTasks").disabled = !activeToken || busy;
    $("openTask").disabled = !activeToken || busy;
    $("submitTask").disabled =
      !activeToken ||
      !selectedCapability ||
      busy ||
      !exposedFields(selectedCapability).length ||
      !selectedCapability.purposes?.length;
    for (const id of ["newTaskKey", "previewTask", "reloadTask"]) $(id).disabled = busy;
    for (const node of document.querySelectorAll(
      "#capabilityDetails input, #capabilityDetails select",
    ))
      node.disabled = busy;
  }
  function exposedFields(capability) {
    return (Array.isArray(capability.fields) ? capability.fields : []).filter(
      (field) =>
        field.sensitive === false &&
        typeof field.name === "string" &&
        typeof field.type === "string",
    );
  }
  function renderCapabilities() {
    const list = $("capabilityList");
    list.replaceChildren();
    if (!capabilities.length)
      list.append(
        element("p", "当前凭据没有可用的已发布能力。此列表不声明企业资产覆盖范围。", "empty"),
      );
    for (const capability of capabilities)
      list.append(
        card(
          capability.name || capability.functionId,
          capability.functionId + " · " + capability.releaseId,
          () => selectCapability(capability),
          selectedCapability?.releaseId === capability.releaseId,
        ),
      );
  }
  function selectCapability(capability) {
    selectedCapability = capability;
    $("capabilityEmpty").classList.add("hidden");
    $("capabilityDetails").classList.remove("hidden");
    $("capabilityHeading").textContent = capability.name || capability.functionId;
    $("capabilityPointers").replaceChildren(
      metadata([
        ["正式定义", capability.definition],
        ["Function ID", capability.functionId],
        ["Function Release ID", capability.releaseId],
        ["Function 内容 Hash", capability.contentHash],
        ["正式 Metric", capability.metric],
        ["统计对象", capability.statisticalSubject],
      ]),
    );
    $("capabilityScope").textContent = encode({
      contract: capability.contract,
      limits: capability.limits,
      time: capability.time,
      purposes: capability.purposes,
      supply: capability.supply,
      readiness: capability.readiness,
      limitations: capability.limitations,
    });
    $("fieldChoices").replaceChildren();
    for (const field of exposedFields(capability))
      $("fieldChoices").append(
        element(
          "p",
          field.name + " · " + field.type + " · 单位 " + (field.unit || "未声明"),
          "field",
        ),
      );
    $("consumerPurpose").replaceChildren();
    for (const purpose of capability.purposes || []) {
      const option = element("option", purpose);
      option.value = purpose;
      $("consumerPurpose").append(option);
    }
    $("metricView").replaceChildren();
    for (const view of capability.views || []) {
      const option = element("option", view);
      option.value = view;
      $("metricView").append(option);
    }
    const rows = capability.limits?.maxRows,
      bytes = capability.limits?.maxBytes;
    $("requestMaxRows").value = Number.isSafeInteger(rows) && rows > 0 ? Math.min(100, rows) : "";
    $("requestMaxRows").max = rows || "";
    $("requestMaxBytes").value =
      Number.isSafeInteger(bytes) && bytes > 0 ? Math.min(65536, bytes) : "";
    $("requestMaxBytes").max = bytes || "";
    $("timeScope").textContent =
      "显式半开范围 [from,to)，业务时区 " +
      (capability.time?.businessZone || "未提供") +
      "，时间角色 " +
      (capability.time?.role || "未提供") +
      "，最大窗口 " +
      (capability.limits?.maxWindowSeconds ?? "未提供") +
      " 秒。不推断昨天或最新账期。";
    $("taskRequestKey").value = key();
    $("requestOutput").textContent = "请明确时间、视图和验证要求。指标定义由正式发布固定。";
    renderCapabilities();
    updateControls();
    notice("已选择正式指标发布；发现不代表就绪、来源授权或数值已验证。");
  }

  async function loadTasks() {
    const tasks = await api("/data-tasks");
    if (!Array.isArray(tasks)) throw Error("操作列表响应格式不受支持。");
    const list = $("taskList");
    list.replaceChildren();
    if (!tasks.length) list.append(element("p", "当前主体当前授权范围内没有可见操作。", "empty"));
    for (const task of tasks.filter((task) => !!task.resolved?.metric))
      list.append(
        card(task.id, (task.status || "未取得") + " · " + taskTime(task.createdAt), () =>
          loadTask(task.id),
        ),
      );
  }
  async function connect() {
    invalidateIdentity();
    const token = $("consumerToken").value.trim();
    if (!token) throw Error("请输入独立消费 Token；Author / Reviewer 不自动获得消费权限。");
    activeToken = token;
    busy = true;
    updateControls();
    try {
      const profiles = await api("/capabilities");
      if (!Array.isArray(profiles)) throw Error("能力发现响应格式不受支持。");
      capabilities = profiles.filter(
        (profile) => profile.intent === "QUERY_METRIC" && profile.protocolVersion === 2,
      );
      renderCapabilities();
      await loadTasks();
      $("sessionState").textContent = "已加载当前授权范围";
      $("sessionState").className = "badge good";
      if (capabilities.length) {
        const parameters = new URLSearchParams(location.search),
          wanted = parameters.get("releaseId"),
          hash = parameters.get("contentHash");
        const candidate = wanted
          ? capabilities.find(
              (cap) => cap.releaseId === wanted && (!hash || cap.contentHash === hash),
            )
          : capabilities[0];
        if (candidate) selectCapability(candidate);
        else notice("确认的候选不在当前授权指标列表中；不会替换为其他口径。", true);
      } else {
        $("capabilityEmpty").textContent = "没有可用授权能力；不生成示例发布、字段或数据。";
        notice("已读取服务端实际列表，当前没有可用授权能力。");
      }
    } finally {
      busy = false;
      updateControls();
    }
  }
  function instant(id, label) {
    const value = $(id).value.trim();
    if (
      !/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,3})?(?:Z|[+-]\d{2}:\d{2})$/.test(value) ||
      Number.isNaN(Date.parse(value))
    )
      throw Error(
        label + "必须明确日期、时分秒及时区 Z / ±HH:MM；当前表单支持毫秒精度，不推断本地时区。",
      );
    return value;
  }
  function budget(id, label, maximum) {
    const raw = $(id).value.trim();
    if (!/^[1-9]\d*$/.test(raw)) throw Error(label + "必须是正整数。");
    const value = Number(raw);
    if (!Number.isSafeInteger(value) || !Number.isSafeInteger(maximum) || value > maximum)
      throw Error(label + "超出当前能力策略预算或策略预算未提供。");
    return value;
  }
  function requestBody() {
    if (!selectedCapability) throw Error("请选择实际授权的正式指标发布。");
    const from = instant("rangeFrom", "范围起点"),
      to = instant("rangeTo", "范围终点");
    if (Date.parse(from) >= Date.parse(to)) throw Error("范围起点必须早于终点。");
    if (
      !Number.isSafeInteger(selectedCapability.limits?.maxWindowSeconds) ||
      (Date.parse(to) - Date.parse(from)) / 1000 > selectedCapability.limits.maxWindowSeconds
    )
      throw Error("超出策略允许时间窗口。");
    const purpose = $("consumerPurpose").value,
      view = $("metricView").value,
      verification = $("metricVerification").value;
    if (
      !selectedCapability.purposes?.includes(purpose) ||
      !selectedCapability.views?.includes(view)
    )
      throw Error("用途或视图不在当前授权能力声明中。");
    const body = {
      protocolVersion: 2,
      intent: "QUERY_METRIC",
      proposedFunction: {
        releaseId: selectedCapability.releaseId,
        contentHash: selectedCapability.contentHash,
      },
      requirements: { from, to, view, verification },
      consumer: { purpose },
      constraints: {
        maxRows: budget("requestMaxRows", "最大行数", selectedCapability.limits.maxRows),
        maxBytes: budget("requestMaxBytes", "最大字节数", selectedCapability.limits.maxBytes),
      },
      idempotencyKey: $("taskRequestKey").value.trim(),
    };
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(body.idempotencyKey))
      throw Error("幂等键格式不受支持。");
    const issuer = $("externalIssuer").value.trim(),
      id = $("externalTaskId").value.trim();
    if (issuer || id) {
      if (!issuer || !id) throw Error("外部任务关联需成对填写。");
      body.externalTaskRef = { issuer, id };
    }
    return body;
  }

  function preview(body) {
    $("requestOutput").textContent = encode(body);
    $("requestPreview").open = true;
  }
  async function submit() {
    const body = requestBody();
    preview(body);
    busy = true;
    updateControls();
    try {
      const task = await api("/data-tasks", "POST", body);
      renderTask(task);
      if (task.status !== "RUNNING") $("taskRequestKey").value = key();
      await loadTasks();
      notice(
        "操作已记录为 " +
          task.status +
          "。请分别查看结果、数据就绪、验证范围与限制；这不是整体业务任务完成声明。",
      );
    } finally {
      busy = false;
      updateControls();
    }
  }
  function fieldProfile(task) {
    return capabilities.find(
      (profile) =>
        profile.releaseId === task.resolved?.function?.releaseId &&
        profile.contentHash === task.resolved?.function?.contentHash,
    );
  }
  function renderRows(task) {
    const box = $("typedRows");
    box.replaceChildren();
    const result = task.result;
    if (
      !result ||
      task.payloadAvailable !== true ||
      ["EXPIRED", "INVALID", "UNAVAILABLE"].includes(result.state) ||
      !Array.isArray(result.rows) ||
      !result.rows.length
    )
      return;
    const columns = task.resolved?.outputs || fieldProfile(task)?.fields || [];
    if (!columns.length) {
      box.append(element("p", "未取得正式输出 Schema；不猜测列和单位。", "callout warn"));
      return;
    }
    const table = element("table", undefined, "result-table"),
      head = element("tr"),
      thead = element("thead"),
      tbody = element("tbody");
    for (const field of columns) {
      const th = element("th", field.name);
      th.append(element("small", field.type + " · " + (field.unit || "单位未取得")));
      head.append(th);
    }
    thead.append(head);
    table.append(thead);
    for (const source of result.rows) {
      const row = element("tr");
      for (const field of columns) {
        const value = source[field.name];
        row.append(
          element(
            "td",
            value === null
              ? "NULL（不是0）"
              : value === undefined
                ? "未取得"
                : typeof value === "object"
                  ? encode(value)
                  : String(value),
          ),
        );
      }
      tbody.append(row);
    }
    table.append(tbody);
    box.append(table);
  }

  function renderResult(task) {
    const box = $("resultSummary");
    box.replaceChildren();
    $("typedRows").replaceChildren();
    const result = task.result;
    if (!result) {
      box.append(element("p", "尚无终态数据证据，不推断成功、空集或0。", "callout warn"));
      return;
    }
    box.append(badge(result.state));
    let text = "没有可用完整结果；请查看真实执行、就绪和验证状态。";
    if (result.state === "EXPIRED")
      text = "载荷已过期，不等于NO_RECORD或0。证据保留，读取新快照需新请求键。";
    else if (task.payloadAvailable === false) text = "当前载荷不可用，不能按0、空集或已就绪使用。";
    else if (result.state === "ZERO")
      text = "正式契约在本次明确范围内返回ZERO；仅按声明的单位、统计对象与就绪范围解释。";
    else if (result.state === "NULL")
      text = "正式契约返回NULL；不能擅自改成0或解释为没有业务事件。";
    else if (result.state === "NO_RECORD")
      text = "明确范围内没有结果记录；不代表零指标或企业不存在此数据。";
    else if (result.state === "RECORDS")
      text =
        "本次实际返回 " +
        (result.rows?.length ?? "未取得") +
        " 行正式指标结果；独立对账和业务验收分别判定。";
    box.append(element("p", text, "callout"));
    renderRows(task);
  }

  function renderTask(task) {
    currentTask = task;
    $("taskDetail").classList.remove("hidden");
    $("taskHeading").textContent = "正式指标操作 · " + task.id;
    $("operationStatus").replaceWith(Object.assign(badge(task.status), { id: "operationStatus" }));
    $("decisionAxes").replaceChildren();
    for (const [label, field] of [
      ["解析", "resolution"],
      ["能力供给", "supply"],
      ["访问授权", "authorization"],
      ["数据就绪", "readiness"],
    ]) {
      const item = element("div", undefined, "decision");
      item.append(element("strong", label), badge(task.decision?.[field]));
      $("decisionAxes").append(item);
    }
    renderResult(task);
    $("taskPointers").replaceChildren(
      metadata([
        ["操作ID", task.id],
        ["服务端主体", task.principal],
        ["Function", task.resolved?.function],
        ["正式Metric", task.resolved?.metric],
        ["统计对象", task.resolved?.statisticalSubject],
        ["固定来源", task.resolved?.sources],
        ["来源事务快照", task.snapshots || task.data_snapshot_ref],
        ["结果Hash", task.resultHash],
        ["计划Hash", task.resolved?.planHash],
        ["载荷有效期", task.resultExpiresAt],
      ]),
    );
    $("taskScope").textContent = encode({
      requirements: task.resolved?.requirements,
      contract: task.resolved?.contract,
      rowScope: task.resolved?.rowScope,
      outputs: task.resolved?.outputs,
      scope: task.result?.scope,
      periodCompleteness: task.result?.periodCompleteness,
      planHash: task.resolved?.planHash,
    });
    $("taskVerification").textContent = encode({
      execution: task.execution,
      stages: task.stages,
      gate_report: task.gate_report,
      readinessEvidence: task.readinessEvidence,
      verification: task.verification,
      executionEvidence: task.executionEvidence,
    });
    $("taskLimitations").replaceChildren(
      element(
        "p",
        "数据就绪、结构校验、独立对账和整体业务验收分别成立；技术通过不能代替整体任务成功。",
        "callout warn",
      ),
    );
    const list = element("ul");
    for (const text of task.limitations || []) list.append(element("li", text));
    $("taskLimitations").append(list);
    const evidence = { ...task };
    if (task.result) {
      const { rows, ...summary } = task.result;
      evidence.result = summary;
    }
    $("taskEvidence").textContent = encode(evidence);
  }

  async function loadTask(id) {
    if (!/^[a-fA-F0-9-]{36}$/.test(id)) throw Error("请输入合法操作 UUID。");
    const task = await api("/data-tasks/" + encodeURIComponent(id));
    if (task.request?.intent !== "QUERY_METRIC")
      throw Error("该操作不是正式指标查询，请使用Dataset取数入口。");
    renderTask(task);
  }
  $("consumerToken").oninput = () => {
    invalidateIdentity();
    notice("凭据已更改；旧主体的数据已清除，请重新加载授权范围。");
  };
  $("connectConsumer").onclick = () => safe(connect);
  $("clearConsumer").onclick = () => {
    invalidateIdentity(true);
    notice("当前页面中的消费凭据和已加载数据已清除。");
  };
  $("refreshTasks").onclick = () => safe(loadTasks);
  $("openTask").onclick = () => safe(() => loadTask($("taskLookup").value.trim()));
  $("reloadTask").onclick = () =>
    safe(() => {
      if (!currentTask) throw Error("请先读取一个实际操作。");
      return loadTask(currentTask.id);
    });
  $("newTaskKey").onclick = () => {
    $("taskRequestKey").value = key();
    notice("已生成新的请求键；再次提交会申请新的来源读取，而不是重用旧操作收据。");
  };
  $("previewTask").onclick = () => safe(() => preview(requestBody()));
  $("submitTask").onclick = () => safe(submit);
  $("validateMetricPlan").onclick = () =>
    safe(async () => {
      const body = requestBody();
      preview(body);
      const report = await api("/semantic-plans/validate", "POST", body);
      $("metricPlanOutput").textContent = encode(report);
      $("metricPlanPanel").open = true;
      $("requestOutput").textContent = encode({ request: body, staticPlan: report });
      notice("静态计划检查已返回；不代表来源就绪或执行授权。");
    });
  clearVisibleData();
})();
