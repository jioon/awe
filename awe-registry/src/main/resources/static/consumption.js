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
    if (["EXTRACTED", "RECORDS", "RESOLVED", "DIRECT", "ALLOWED", "PASSED"].includes(value))
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
        ["Function ID", capability.functionId],
        ["Function Release ID", capability.releaseId],
        ["Function 内容 Hash", capability.contentHash],
        ["固定 Dataset", capability.dataset],
        ["固定输出行 Schema", capability.rowSchema],
      ]),
    );
    $("capabilityScope").textContent = encode({
      limits: capability.limits,
      time: capability.time,
      purposes: capability.purposes,
      view: capability.view,
      supply: capability.supply,
      readiness: capability.readiness,
      limitations: capability.limitations,
    });
    const choices = $("fieldChoices");
    choices.replaceChildren();
    for (const field of exposedFields(capability)) {
      const label = element("label", undefined, "field");
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.value = field.name;
      checkbox.checked = true;
      label.append(
        checkbox,
        document.createTextNode(
          field.name +
            " · " +
            field.type +
            (field.required ? " · Schema 必需值" : " · Schema 可空值"),
        ),
      );
      choices.append(label);
    }
    if (!choices.children.length)
      choices.append(element("p", "该发布没有可展示的授权非敏感字段。", "empty"));
    const purposes = $("consumerPurpose");
    purposes.replaceChildren();
    for (const purpose of capability.purposes || []) {
      const option = element("option", purpose);
      option.value = purpose;
      purposes.append(option);
    }
    const rows = capability.limits?.maxRows;
    const bytes = capability.limits?.maxBytes;
    $("requestMaxRows").value = Number.isSafeInteger(rows) && rows > 0 ? Math.min(100, rows) : "";
    $("requestMaxRows").max = Number.isSafeInteger(rows) ? rows : "";
    $("requestMaxBytes").value =
      Number.isSafeInteger(bytes) && bytes > 0 ? Math.min(65536, bytes) : "";
    $("requestMaxBytes").max = Number.isSafeInteger(bytes) ? bytes : "";
    $("timeScope").textContent =
      "显式半开区间 [from,to)，时间角色 " +
      (capability.time?.role || "未提供") +
      "，模型业务时区 " +
      (capability.time?.businessZone || "未提供") +
      "；策略允许的最长窗口 " +
      (capability.limits?.maxWindowSeconds ?? "未提供") +
      " 秒。界面不填入推断日期。";
    $("taskRequestKey").value = key();
    $("requestOutput").textContent = "请选择字段并明确时间范围后检查请求。";
    renderCapabilities();
    updateControls();
    notice("已选择服务端授权的固定 Function 发布；能力发现不保证当前连接或数据就绪。");
  }
  async function loadTasks() {
    const tasks = await api("/data-tasks");
    if (!Array.isArray(tasks)) throw Error("操作列表响应格式不受支持。");
    const list = $("taskList");
    list.replaceChildren();
    if (!tasks.length) list.append(element("p", "当前主体当前授权范围内没有可见操作。", "empty"));
    for (const task of tasks)
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
      capabilities = profiles;
      renderCapabilities();
      await loadTasks();
      $("sessionState").textContent = "已加载当前授权范围";
      $("sessionState").className = "badge good";
      if (capabilities.length) selectCapability(capabilities[0]);
      else {
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
    if (!selectedCapability) throw Error("请选择实际授权的 Function 发布。");
    const fields = Array.from(
      document.querySelectorAll("#fieldChoices input:checked"),
      (input) => input.value,
    );
    if (!fields.length) throw Error("至少选择一个授权非敏感字段。");
    const from = instant("rangeFrom", "范围起点"),
      to = instant("rangeTo", "范围终点");
    if (Date.parse(from) >= Date.parse(to)) throw Error("半开区间起点必须早于终点。");
    if (
      !Number.isSafeInteger(selectedCapability.limits?.maxWindowSeconds) ||
      (Date.parse(to) - Date.parse(from)) / 1000 > selectedCapability.limits.maxWindowSeconds
    )
      throw Error("请求窗口超出服务端允许范围，或窗口预算未提供。");
    const purpose = $("consumerPurpose").value;
    if (!selectedCapability.purposes?.includes(purpose))
      throw Error("请选择服务端允许的用途声明。");
    const body = {
      protocolVersion: 1,
      intent: "EXTRACT",
      proposedFunction: {
        releaseId: selectedCapability.releaseId,
        contentHash: selectedCapability.contentHash,
      },
      requirements: { fields, from, to, view: "OBSERVED_SNAPSHOT" },
      consumer: { purpose },
      constraints: {
        maxRows: budget("requestMaxRows", "最大行数", selectedCapability.limits?.maxRows),
        maxBytes: budget("requestMaxBytes", "最大字节数", selectedCapability.limits?.maxBytes),
      },
      idempotencyKey: $("taskRequestKey").value.trim(),
    };
    if (!/^[A-Za-z0-9][A-Za-z0-9._:-]{0,119}$/.test(body.idempotencyKey))
      throw Error("幂等请求键格式不受支持。");
    const issuer = $("externalIssuer").value.trim(),
      id = $("externalTaskId").value.trim();
    if (issuer || id) {
      if (!issuer || !id) throw Error("可选外部任务关联必须同时填写 issuer 和 id。");
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
    if (!result) return;
    if (result.state === "EXPIRED" || task.payloadAvailable === false) return;
    if (
      result.state !== "RECORDS" ||
      task.payloadAvailable !== true ||
      !Array.isArray(result.rows) ||
      !result.rows.length
    )
      return;
    const profile = fieldProfile(task);
    const properties = new Map(
      profile ? exposedFields(profile).map((field) => [field.name, field]) : [],
    );
    const columns = (task.resolved?.requirements?.fields || []).filter(
      (field) => !profile || properties.has(field),
    );
    if (!columns.length) {
      box.append(
        element("p", "当前证据未提供可展开的授权投影列；不猜测行 Schema。", "callout warn"),
      );
      return;
    }
    const table = element("table", undefined, "result-table");
    const head = element("tr");
    for (const column of columns) {
      const th = element("th", column);
      th.append(
        element("small", properties.get(column)?.type || "类型见固定 Schema；当前发现未提供"),
      );
      head.append(th);
    }
    const thead = element("thead");
    thead.append(head);
    table.append(thead);
    const tbody = element("tbody");
    for (const source of result.rows) {
      const row = element("tr");
      for (const column of columns) {
        const value = source[column];
        row.append(
          element(
            "td",
            value === null
              ? "NULL（无值）"
              : value === undefined
                ? "字段未取得"
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
      box.append(element("p", "没有终态数据证据；不据此推断成功、空集或 0。", "callout warn"));
      return;
    }
    box.append(badge(result.state));
    if (result.state === "EXPIRED")
      box.append(
        element(
          "p",
          "行载荷已过期或被清理，当前不可读取。这不表示没有记录或数值为 0；重新读取数据需要新的请求键。原记录状态、Hash 和范围证据保留在下方。",
          "callout warn",
        ),
      );
    else if (task.payloadAvailable === false)
      box.append(
        element(
          "p",
          "当前没有可访问的行载荷，不能按空集、0、数据已到齐或业务成功使用。请查看执行状态和限制。",
          "callout warn",
        ),
      );
    else if (result.state === "NO_RECORD" && task.payloadAvailable === true)
      box.append(
        element(
          "p",
          "本次观察快照和显式范围内没有符合条件的记录。这不是零指标，也不证明分区到齐或业务事件没有发生。",
          "callout",
        ),
      );
    else if (
      result.state === "RECORDS" &&
      task.payloadAvailable === true &&
      Array.isArray(result.rows) &&
      result.rows.length
    )
      box.append(
        element(
          "p",
          "本次可访问载荷含 " +
            result.rows.length +
            " 行受限投影。仅适用于固定版本及当前观察快照，账期完整性仍需独立确认。",
          "callout",
        ),
      );
    else
      box.append(
        element(
          "p",
          "没有可安全展开的完整行载荷；保持未知，不显示模拟数据或推断的空集。",
          "callout warn",
        ),
      );
    renderRows(task);
  }
  function renderTask(task) {
    currentTask = task;
    $("taskDetail").classList.remove("hidden");
    $("taskHeading").textContent = "数据操作 · " + task.id;
    $("operationStatus").replaceWith(Object.assign(badge(task.status), { id: "operationStatus" }));
    const axes = $("decisionAxes");
    axes.replaceChildren();
    for (const [label, field] of [
      ["解析", "resolution"],
      ["能力供给", "supply"],
      ["访问授权", "authorization"],
      ["数据就绪", "readiness"],
    ]) {
      const item = element("div", undefined, "decision");
      item.append(element("strong", label), badge(task.decision?.[field]));
      axes.append(item);
    }
    renderResult(task);
    $("taskPointers").replaceChildren(
      metadata([
        ["操作 ID", task.id],
        ["服务端主体", task.principal],
        ["实际 Function 发布", task.resolved?.function],
        ["实际 Dataset 发布", task.resolved?.dataset],
        ["实际输出 Schema 发布", task.resolved?.schema],
        ["实际来源事务快照", task.data_snapshot_ref],
        ["行数组内容 Hash", task.resultHash],
        ["载荷有效期", task.resultExpiresAt],
        ["登记时间（北京时间）", taskTime(task.createdAt)],
        ["完成时间（北京时间）", taskTime(task.completedAt)],
      ]),
    );
    $("taskScope").textContent = encode({
      requirements: task.resolved?.requirements,
      scope: task.result?.scope,
      completeForObservedRange: task.result?.completeForObservedRange,
      periodCompleteness: task.result?.periodCompleteness,
      businessZone: task.resolved?.businessZone,
      timeRole: task.resolved?.timeRole,
      planHash: task.resolved?.planHash,
      executor: task.resolved?.executor,
    });
    $("taskVerification").textContent = encode({
      execution: task.execution,
      gate_report: task.gate_report,
      verification: task.verification,
    });
    const limits = $("taskLimitations");
    limits.replaceChildren();
    limits.append(
      element(
        "p",
        "结构检查通过仅限本次支持的技术范围。独立对账、账期就绪和整体业务验收分别判定；外部 Agent / 业务责任方保留整体任务与解释责任。",
        "callout warn",
      ),
    );
    const list = element("ul");
    for (const limitation of task.limitations || []) list.append(element("li", limitation));
    limits.append(list);
    const evidence = { ...task };
    if (task.result) {
      const { rows, ...summary } = task.result;
      evidence.result = summary;
    }
    $("taskEvidence").textContent = encode(evidence);
  }
  async function loadTask(id) {
    if (!/^[a-fA-F0-9-]{36}$/.test(id)) throw Error("请输入合法操作 UUID。");
    renderTask(await api("/data-tasks/" + encodeURIComponent(id)));
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
  clearVisibleData();
})();
