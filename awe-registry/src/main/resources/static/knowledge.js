/* 候选确认、人工参考批准与真实回放是不同操作；页面不把召回分数认证为业务等价。 */
(() => {
  "use strict";
  const C = globalThis.AweWorkbench,
    { $, el, bind, text, uuid, hash, show, cards, key, notice } = C;
  const consumer = C.client("knowledgeConsumerToken"),
    author = C.client("knowledgeAuthorToken"),
    reviewer = C.client("knowledgeReviewerToken");
  const caseTypes = new Map();
  let lastOutcome = null;
  let consumerGeneration = 0;
  let pendingObservation = null;
  let observationInFlight = false;
  let migrationIntent = null;
  let migrationInFlight = false;
  let reviewedMigration = null;
  const pendingStatuses = new Set(["RUNNING", "PENDING", "IN_PROGRESS"]);
  function terminal(report) {
    return (
      typeof report?.status === "string" &&
      report.status.length > 0 &&
      !pendingStatuses.has(report.status)
    );
  }
  function strictFields(document, allowed, label) {
    if (!document || Array.isArray(document) || typeof document !== "object")
      throw Error(label + "必须是JSON对象");
    for (const name of Object.keys(document))
      if (!allowed.includes(name)) throw Error(label + "不接受字段：" + name);
  }
  function outcomeExpectation(document) {
    strictFields(
      document,
      ["type", "status", "httpStatus", "decision", "failureStage", "noDataReturned"],
      "outcome",
    );
    if (document.type !== undefined && document.type !== "DATA_OUTCOME")
      throw Error("期望类型必须是DATA_OUTCOME");
    const failures = new Set([
      "DENIED",
      "NOT_DISCLOSED",
      "REJECTED",
      "BLOCKED",
      "NOT_READY",
      "NOT_CONFIGURED",
      "UNAVAILABLE",
      "INCONCLUSIVE",
      "FAILED_VERIFICATION",
    ]);
    if (!failures.has(document.status))
      throw Error("DATA_OUTCOME只允许已终态的拒绝或未就绪行为，不能伪装成功或在途状态");
    if (
      !Number.isSafeInteger(document.httpStatus) ||
      ![200, 400, 403, 404, 409, 503].includes(document.httpStatus)
    )
      throw Error("outcome.httpStatus不在当前协议支持的实际HTTP状态中");
    strictFields(
      document.decision,
      ["resolution", "supply", "authorization", "readiness"],
      "outcome.decision",
    );
    for (const name of ["resolution", "supply", "authorization", "readiness"])
      if (typeof document.decision[name] !== "string" || !document.decision[name].trim())
        throw Error("outcome.decision必须明确四个决策轴");
    if (
      typeof document.failureStage !== "string" ||
      ![
        "RESOLUTION",
        "AUTHORIZATION",
        "PLANNING",
        "SOURCE_GATE",
        "EXECUTION",
        "READINESS",
        "VERIFICATION",
        "UNKNOWN",
      ].includes(document.failureStage) ||
      document.noDataReturned !== true
    )
      throw Error(
        "行为参考必须明确真实失败阶段且noDataReturned=true；不能把已返回数据的操作伪装为拒绝",
      );
    return {
      type: "DATA_OUTCOME",
      status: document.status,
      httpStatus: document.httpStatus,
      decision: {
        resolution: document.decision.resolution,
        supply: document.decision.supply,
        authorization: document.decision.authorization,
        readiness: document.decision.readiness,
      },
      failureStage: document.failureStage,
      noDataReturned: true,
    };
  }
  function canUseOutcome(document) {
    try {
      outcomeExpectation(document);
      return true;
    } catch {
      return false;
    }
  }
  function typeOfCase(document) {
    const type =
      document.expectation?.type ||
      document.expectation_doc?.type ||
      document.expectationType ||
      document.expectation_type;
    if (typeof type === "string") return type;
    if (
      typeof document.expected_state === "string" &&
      typeof document.expected_fingerprint === "string"
    )
      return "LEGACY";
    return caseTypes.get(document.id) || "UNKNOWN";
  }
  function caseReport(document) {
    show("regressionReport", document);
    if (document.id) {
      $("regressionCaseId").value = document.id;
      caseTypes.set(document.id, typeOfCase(document));
    }
    const type = typeOfCase(document);
    const legacy =
      document.expectationFormat === "LEGACY_V1"
        ? "旧格式LEGACY_V1仅派生展示期望，不改原caseHash。"
        : "";
    $("regressionCaseTypeReport").replaceChildren(
      el(
        "p",
        type === "DATA_OUTCOME"
          ? "实际参考类型为DATA_OUTCOME：回归通过仅证明行为符合受审期望，不能用于数据反馈技术闭合。"
          : legacy +
              "参考类型：" +
              type +
              "。读取和批准不改写原case、原请求或历史Hash；只有真实新回放才产生回归证据。",
        "callout",
      ),
    );
    return document;
  }
  function expectationControls() {
    const type = text("regressionExpectationType");
    $("regressionResultExpectation").classList.toggle("hidden", type === "DATA_OUTCOME");
    $("regressionOutcomeExpectationFields").classList.toggle("hidden", type !== "DATA_OUTCOME");
    const previous = $("regressionExpectedState").value;
    const states =
      type === "DATA_RESULT"
        ? ["RECORDS", "NO_RECORD", "ZERO", "NULL", "NOT_APPLICABLE"]
        : ["RECORDS", "NO_RECORD", "ZERO", "NULL", "RESOLVED", "AMBIGUOUS", "UNRESOLVED"];
    $("regressionExpectedState").replaceChildren();
    for (const state of states) {
      const option = el("option", state);
      option.value = state;
      $("regressionExpectedState").append(option);
    }
    if (states.includes(previous)) $("regressionExpectedState").value = previous;
  }
  function observationControls() {
    for (const id of [
      "regressionObservationRequest",
      "regressionObservationKey",
      "newRegressionObservationKey",
    ])
      $(id).disabled = observationInFlight;
    $("observeRegressionRequest").textContent = pendingObservation
      ? "重试同一次实际观测（原payload与键）"
      : "发起新的实际操作结局观测";
  }
  function observationStatus(message, submittedKey) {
    $("regressionObservationRequestStatus").replaceChildren(
      el("p", message, "callout warn"),
      el("p", "已提交观测请求键：" + submittedKey, "muted"),
    );
  }
  function renderOutcomeObservation(report, source) {
    show("regressionOutcomeObservation", report);
    $("regressionOutcomeObservation").prepend(
      el("p", source + "。这是实际操作结局，不是正确性参考批准，也不是数据修复证明。", "callout"),
    );
    if (report.id) {
      $("regressionOutcomeObservationId").value = report.id;
      $("regressionObservationId").value = report.id;
    }
    lastOutcome = report.outcome || null;
    $("useObservedOutcome").disabled = !canUseOutcome(lastOutcome);
    if (lastOutcome)
      $("regressionOutcomeObservation").append(
        el("h3", "服务端实际outcome（待独立审定）"),
        el("pre", JSON.stringify(lastOutcome, null, 2)),
      );
    return report;
  }
  function migrationContext() {
    return { feedbackId: uuid("migrationFeedbackId"), migrationId: uuid("migrationId") };
  }
  function clearMigrationReview() {
    reviewedMigration = null;
    $("migrationHash").value = "";
    $("approveMigration").disabled = true;
  }
  function migrationReport(document) {
    clearMigrationReview();
    show("migrationReport", document);
    const migration = document.migration || document;
    const id =
      migration.id ||
      migration.migrationId ||
      document.proposal?.id ||
      document.proposal?.migrationId;
    if (id) $("migrationId").value = id;
    return document;
  }
  function migrationHash(document) {
    const migration = document.migration || document;
    const value =
      migration.migrationHash ||
      migration.migration_hash ||
      document.migrationHash ||
      document.migration_hash;
    return typeof value === "string" && /^[a-f0-9]{64}$/.test(value) ? value : null;
  }
  async function readMigrationReview(client, independent) {
    clearMigrationReview();
    const context = migrationContext();
    const document = await client(
      "/feedback/" + context.feedbackId + "/migrations/" + context.migrationId + "/review",
    );
    migrationReport(document);
    const actual = migrationHash(document);
    if (actual) $("migrationHash").value = actual;
    if (independent && actual) {
      reviewedMigration = { ...context, hash: actual };
      $("approveMigration").disabled = false;
    }
    notice(
      independent && actual
        ? "已读取Reviewer治理差异；批准只绑定此实际migrationHash，不等于已执行重验或已完成替代验收。"
        : "已读取治理元数据；需要Reviewer独立读取并核对实际Hash后才能在页面批准。",
    );
  }
  window.addEventListener("awe-identity-cleared", () => {
    lastOutcome = null;
    $("useObservedOutcome").disabled = true;
    clearMigrationReview();
  });
  $("knowledgeConsumerToken").addEventListener("input", () => {
    consumerGeneration++;
    pendingObservation = null;
    migrationIntent = null;
    observationInFlight = false;
    migrationInFlight = false;
    caseTypes.clear();
    $("regressionObservationKey").value = key();
    $("migrationRequestKey").value = key();
    observationControls();
    $("newMigrationRequestKey").disabled = false;
  });

  function request() {
    const body = { intent: text("knowledgeIntent") };
    for (const [id, field] of [
      ["knowledgeText", "text"],
      ["knowledgeDomain", "domain"],
      ["knowledgeFunctionId", "functionId"],
      ["knowledgeReleaseId", "releaseId"],
      ["knowledgeContentHash", "contentHash"],
    ]) {
      const value = text(id, true);
      if (value) body[field] = value;
    }
    return body;
  }
  function observation(report) {
    show("knowledgeResolution", report);
    if (report.id) {
      $("feedbackObservationId").value = report.id;
      $("regressionObservationId").value = report.id;
    }
    return report;
  }
  async function discover(assessment = false) {
    const body = request(),
      report = observation(
        await consumer("/knowledge/" + (assessment ? "assess" : "resolve"), "POST", body),
      );
    const box = $("knowledgeCandidates");
    box.replaceChildren();
    if (!report.candidates?.length)
      box.append(
        el(
          "p",
          "没有可见候选；UNKNOWN不等于企业缺失。请核对服务端声明的范围与证据。",
          "callout warn",
        ),
      );
    for (const candidate of report.candidates || []) {
      const section = el("section", undefined, "panel");
      section.append(
        el("h3", candidate.name || candidate.functionId),
        el("p", candidate.definition || "定义由实际发布提供"),
        el("pre", JSON.stringify(candidate, null, 2)),
      );
      const confirm = el("button", "明确确认此候选（不会自动执行）");
      confirm.onclick = () =>
        C.safe(async () => {
          const pinned = {
            intent: body.intent,
            releaseId: candidate.releaseId,
            contentHash: candidate.contentHash,
          };
          if (body.domain) pinned.domain = body.domain;
          const confirmed = observation(await consumer("/knowledge/resolve", "POST", pinned));
          if (
            confirmed.resolution === "RESOLVED" &&
            confirmed.candidates?.some(
              (row) =>
                row.releaseId === candidate.releaseId && row.contentHash === candidate.contentHash,
            )
          ) {
            const link = el("a", "进入对应正式消费页（重新输入消费凭据）");
            link.href =
              (body.intent === "QUERY_METRIC" ? "/metric-consumption.html" : "/consumption.html") +
              "?releaseId=" +
              encodeURIComponent(candidate.releaseId) +
              "&contentHash=" +
              encodeURIComponent(candidate.contentHash);
            section.append(link);
            notice("服务端已按精确发布确认；仍需独立请求、权限、就绪和验证检查。");
          } else notice("候选尚未明确解析，不发起数据执行。", true);
        });
      section.append(confirm);
      box.append(section);
    }
  }
  bind("resolveKnowledge", () => discover(false));
  bind("assessKnowledge", () => discover(true));
  bind("loadCoverage", async () =>
    show("knowledgeCoverage", await consumer("/knowledge/coverage")),
  );
  bind("reportFeedback", async () => {
    const report = await consumer("/feedback", "POST", {
      observationId: uuid("feedbackObservationId"),
      issueCode: text("feedbackIssue"),
      stage: text("feedbackStage"),
      description: text("feedbackDescription"),
    });
    show("feedbackReport", report);
    $("feedbackId").value = report.id;
    $("migrationFeedbackId").value = report.id;
  });
  bind("loadFeedback", async () => {
    const rows = await consumer("/feedback");
    cards(
      "feedbackList",
      rows,
      (row) => row.issue_code + " · " + row.stage + " · " + row.status,
      async (row) => {
        $("feedbackId").value = row.id;
        $("migrationFeedbackId").value = row.id;
        show("feedbackReport", await consumer("/feedback/" + encodeURIComponent(row.id)));
      },
    );
  });
  bind("reviewFeedback", async () =>
    show(
      "feedbackReview",
      await author("/feedback/" + uuid("feedbackId") + "/reviews", "POST", {
        classification: text("feedbackClassification"),
        ownerTeam: text("feedbackOwner"),
        externalIssueRef: text("feedbackExternalRef", true),
        note: text("feedbackReviewNote"),
      }),
    ),
  );
  bind("closeFeedback", async () =>
    show(
      "feedbackReview",
      await reviewer("/feedback/" + uuid("feedbackId") + "/closures", "POST", {
        replayId: uuid("feedbackReplayId"),
        note: text("feedbackClosureNote"),
      }),
    ),
  );
  bind("proposeRegression", async () => {
    const body = {
      observationId: uuid("regressionObservationId"),
      referenceBasis: text("regressionBasis"),
      referenceEvidenceRef: text("regressionEvidenceRef"),
    };
    const type = text("regressionExpectationType");
    if (type === "LEGACY") {
      body.expectedState = text("regressionExpectedState");
      body.expectedFingerprint = hash("regressionFingerprint");
    } else if (type === "DATA_RESULT") {
      const state = text("regressionExpectedState");
      if (!["RECORDS", "NO_RECORD", "ZERO", "NULL", "NOT_APPLICABLE"].includes(state))
        throw Error("DATA_RESULT必须是明确的数据结果状态");
      body.expectation = { type: "DATA_RESULT", state, fingerprint: hash("regressionFingerprint") };
    } else if (type === "DATA_OUTCOME")
      body.expectation = outcomeExpectation(C.json("regressionOutcomeExpectation"));
    else throw Error("不支持的参考期望类型");
    const result = await consumer("/regressions/cases", "POST", body);
    if (result.id) caseTypes.set(result.id, type);
    caseReport(result);
  });
  bind("readRegression", async () =>
    caseReport(await consumer("/regressions/cases/" + uuid("regressionCaseId"))),
  );
  bind("approveRegression", async () =>
    caseReport(
      await reviewer("/regressions/cases/" + uuid("regressionCaseId") + "/approve", "POST"),
    ),
  );
  bind("replayRegression", async () => {
    const caseId = uuid("regressionCaseId");
    const reference = await consumer("/regressions/cases/" + caseId);
    caseTypes.set(caseId, typeOfCase(reference));
    caseReport(reference);
    const result = await consumer("/regressions/cases/" + caseId + "/replays", "POST", {
      idempotencyKey: text("regressionReplayKey"),
    });
    show("regressionReport", result);
    if (result.id) {
      $("regressionReplayId").value = result.id;
      $("regressionReplayKey").value = key();
      if (
        ["LEGACY", "DATA_RESULT", "RESOLUTION", "RESOLUTION_RESULT"].includes(typeOfCase(reference))
      ) {
        $("feedbackReplayId").value = result.id;
        if (text("migrationToCaseId", true) === caseId) $("migrationReplayId").value = result.id;
      } else $("feedbackReplayId").value = "";
    }
    notice(
      typeOfCase(reference) === "DATA_OUTCOME"
        ? "行为参考已实际回放。通过只证明操作结局符合受审期望，不能用于数据反馈技术闭合。"
        : "已按受审参考实际回放；通过不自动技术闭合，也不承诺旧版本已修复。",
    );
  });
  bind("readRegressionReplay", async () => {
    $("regressionReport").replaceChildren();
    const report = await consumer("/regressions/replays/" + uuid("regressionReplayId"));
    const caseId = report.case_id || report.caseId;
    if (caseId) {
      const reference = await consumer("/regressions/cases/" + encodeURIComponent(caseId));
      caseReport(reference);
      if (typeOfCase(reference) === "DATA_OUTCOME") {
        $("feedbackReplayId").value = "";
        notice("此回放只验证操作结局行为，不能用于数据反馈技术闭合。");
      }
    } else
      $("regressionCaseTypeReport").replaceChildren(
        el(
          "p",
          "回放未提供可读取的参考类型；只显示实际报告，不把passed解释为数据已修复。",
          "callout warn",
        ),
      );
    show("regressionReport", report);
  });
  bind("proposeCoverage", async () => {
    const report = await author("/knowledge/coverage", "POST", C.json("coverageDocument"));
    show("coverageProposal", report);
    $("coverageId").value = report.id;
  });
  bind("approveCoverage", async () =>
    show(
      "coverageProposal",
      await reviewer("/knowledge/coverage/" + uuid("coverageId") + "/approve", "POST"),
    ),
  );
  $("regressionExpectationType").onchange = expectationControls;
  bind("newRegressionObservationKey", () => {
    if (observationInFlight) throw Error("实际观测仍在途，请先等待响应");
    pendingObservation = null;
    $("regressionObservationKey").value = key();
    observationControls();
    $("regressionObservationRequestStatus").replaceChildren(
      el("p", "已明确选择另一观测意图。新键不会取消服务端可能已接受的旧请求。", "callout"),
    );
  });
  bind("observeRegressionRequest", async () => {
    const body = {
      request: C.json("regressionObservationRequest"),
      idempotencyKey: text("regressionObservationKey"),
    };
    if (pendingObservation && JSON.stringify(body) !== JSON.stringify(pendingObservation))
      throw Error(
        "网络重试必须保留完整原DataTask与同一观测键。需要改动时请先明确生成新观测请求键。",
      );
    pendingObservation = pendingObservation || body;
    const accepted = pendingObservation,
      generation = consumerGeneration;
    observationInFlight = true;
    observationControls();
    lastOutcome = null;
    $("useObservedOutcome").disabled = true;
    $("regressionOutcomeObservation").replaceChildren();
    observationStatus(
      "正在等待真实操作观测；不沿用旧报告或伪造拒绝、就绪、通过。",
      accepted.idempotencyKey,
    );
    try {
      const report = await consumer("/regressions/observations", "POST", accepted);
      if (generation !== consumerGeneration) return;
      renderOutcomeObservation(
        report,
        report.replayed === true ? "读取同一请求的历史观测收据" : "服务端返回本次实际观测收据",
      );
      if (terminal(report)) {
        pendingObservation = null;
        $("regressionObservationKey").value = key();
        observationStatus(
          "已收到服务端终态；下一次新观测键已轮换。期望仍需人工审定和独立批准，不自动证明修复。",
          accepted.idempotencyKey,
        );
      } else
        observationStatus(
          "尚未取得终态，保留原payload与键；重试仅关联同一次观测。",
          accepted.idempotencyKey,
        );
    } catch (error) {
      if (generation === consumerGeneration)
        observationStatus(
          "未取得可确认终态；原请求可能已被接受，保留原payload和键用于同一次网络重试。",
          accepted.idempotencyKey,
        );
      throw error;
    } finally {
      if (generation === consumerGeneration) {
        observationInFlight = false;
        observationControls();
      }
    }
  });
  bind("readRegressionObservation", async () => {
    lastOutcome = null;
    $("useObservedOutcome").disabled = true;
    $("regressionOutcomeObservation").replaceChildren();
    renderOutcomeObservation(
      await consumer("/regressions/observations/" + uuid("regressionOutcomeObservationId")),
      "读取已保存的实际观测，不重新执行DataTask",
    );
  });
  bind("useObservedOutcome", () => {
    if (!canUseOutcome(lastOutcome))
      throw Error(
        "当前没有可作为待审行为期望的真实拒绝/未就绪outcome；成功或有数据返回不能伪装为DATA_OUTCOME",
      );
    const expected = outcomeExpectation(lastOutcome);
    delete expected.type;
    $("regressionOutcomeExpectation").value = JSON.stringify(expected, null, 2);
    $("regressionExpectationType").value = "DATA_OUTCOME";
    expectationControls();
    notice(
      "已将实际outcome填入待审期望；请独立确认该行为是否正确，补充参考依据并由Reviewer批准，不能循环自证。",
    );
  });
  bind("newMigrationRequestKey", () => {
    if (migrationInFlight) throw Error("提案请求仍在途");
    migrationIntent = null;
    $("migrationRequestKey").value = key();
    notice("已明确选择另一迁移提案；不修改已保存的提案或批准。");
  });
  bind("inspectMigrationCases", async () => {
    const from = await consumer("/regressions/cases/" + uuid("migrationFromCaseId")),
      to = await consumer("/regressions/cases/" + uuid("migrationToCaseId"));
    if (from.id) caseTypes.set(from.id, typeOfCase(from));
    if (to.id) caseTypes.set(to.id, typeOfCase(to));
    show("migrationCases", { from, to });
  });
  bind("proposeMigration", async () => {
    const context = {
      feedbackId: uuid("migrationFeedbackId"),
      body: {
        fromCaseId: uuid("migrationFromCaseId"),
        toCaseId: uuid("migrationToCaseId"),
        reason: text("migrationReason"),
        referenceEvidenceRef: text("migrationEvidenceRef"),
        idempotencyKey: text("migrationRequestKey"),
      },
    };
    for (const caseId of [context.body.fromCaseId, context.body.toCaseId])
      if (["DATA_OUTCOME", "RESOLUTION", "RESOLUTION_RESULT"].includes(caseTypes.get(caseId)))
        throw Error("受审数据修复迁移仅支持DATA_RESULT参考；行为或解析参考不能冒充数据结果义务");
    if (migrationIntent && JSON.stringify(context) !== JSON.stringify(migrationIntent))
      throw Error("原提案键只能重试相同from/to及理由；另一提案请先明确生成新键。");
    migrationIntent = migrationIntent || context;
    const accepted = migrationIntent;
    migrationInFlight = true;
    $("newMigrationRequestKey").disabled = true;
    try {
      migrationReport(
        await consumer("/feedback/" + accepted.feedbackId + "/migrations", "POST", accepted.body),
      );
      notice(
        "迁移提案已由服务端返回。提案不等于批准、等价证明、目标case通过或旧反馈已闭合；请逐项审阅真实差异。",
      );
    } finally {
      migrationInFlight = false;
      $("newMigrationRequestKey").disabled = false;
    }
  });
  bind("listMigrations", async () => {
    const fid = uuid("migrationFeedbackId"),
      document = await consumer("/feedback/" + fid + "/migrations");
    const rows = Array.isArray(document) ? document : document.migrations;
    if (!Array.isArray(rows)) throw Error("迁移列表响应格式不受支持");
    cards(
      "migrationList",
      rows,
      (row) => (row.id || row.migrationId) + " · " + (row.status || row.state || "NONE"),
      async (row) => {
        $("migrationFeedbackId").value = fid;
        $("migrationId").value = row.id || row.migrationId;
        migrationReport(await consumer("/feedback/" + fid + "/migrations/" + uuid("migrationId")));
      },
    );
  });
  bind("readMigration", async () => {
    const context = migrationContext();
    migrationReport(
      await consumer("/feedback/" + context.feedbackId + "/migrations/" + context.migrationId),
    );
  });
  bind("authorReviewMigration", () => readMigrationReview(author, false));
  bind("reviewerReviewMigration", () => readMigrationReview(reviewer, true));
  bind("approveMigration", async () => {
    const context = migrationContext();
    if (
      !reviewedMigration ||
      context.feedbackId !== reviewedMigration.feedbackId ||
      context.migrationId !== reviewedMigration.migrationId ||
      text("migrationHash") !== reviewedMigration.hash
    )
      throw Error(
        "请先由Reviewer重新读取当前提案的治理差异和服务端migrationHash；不能自报scope或沿用其他提案Hash",
      );
    migrationReport(
      await reviewer(
        "/feedback/" + context.feedbackId + "/migrations/" + context.migrationId + "/approve",
        "POST",
        { migrationHash: reviewedMigration.hash },
      ),
    );
    notice(
      "迁移批准仅审定明确差异；仍需目标参考批准、新的实际回放与Reviewer替代验收，旧case与失败未改变。",
    );
  });
  bind("resolveMigration", async () => {
    const context = migrationContext();
    show(
      "migrationResolution",
      await reviewer(
        "/feedback/" + context.feedbackId + "/migrations/" + context.migrationId + "/resolve",
        "POST",
        { replayId: uuid("migrationReplayId"), note: text("migrationResolveNote") },
      ),
    );
    notice(
      "替代验收结果以服务端状态为准。RESOLVED_BY_REPLACEMENT不表示旧版本已修好，新的复发仍可重开。",
    );
  });
  $("regressionReplayKey").value = key();
  $("regressionObservationKey").value = key();
  $("migrationRequestKey").value = key();
  $("useObservedOutcome").disabled = true;
  clearMigrationReview();
  expectationControls();
  observationControls();
})();
