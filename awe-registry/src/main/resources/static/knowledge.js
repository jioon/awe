/* 候选确认、人工参考批准与真实回放是不同操作；页面不把召回分数认证为业务等价。 */
(() => {
  "use strict";
  const C = globalThis.AweWorkbench,
    { $, el, bind, text, uuid, hash, show, cards, key, notice } = C;
  const consumer = C.client("knowledgeConsumerToken"),
    author = C.client("knowledgeAuthorToken"),
    reviewer = C.client("knowledgeReviewerToken");
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
  });
  bind("loadFeedback", async () => {
    const rows = await consumer("/feedback");
    cards(
      "feedbackList",
      rows,
      (row) => row.issue_code + " · " + row.stage + " · " + row.status,
      async (row) => {
        $("feedbackId").value = row.id;
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
    const result = await consumer("/regressions/cases", "POST", {
      observationId: uuid("regressionObservationId"),
      expectedState: text("regressionExpectedState"),
      expectedFingerprint: hash("regressionFingerprint"),
      referenceBasis: text("regressionBasis"),
      referenceEvidenceRef: text("regressionEvidenceRef"),
    });
    show("regressionReport", result);
    $("regressionCaseId").value = result.id;
  });
  bind("readRegression", async () =>
    show("regressionReport", await consumer("/regressions/cases/" + uuid("regressionCaseId"))),
  );
  bind("approveRegression", async () =>
    show(
      "regressionReport",
      await reviewer("/regressions/cases/" + uuid("regressionCaseId") + "/approve", "POST"),
    ),
  );
  bind("replayRegression", async () => {
    const result = await consumer(
      "/regressions/cases/" + uuid("regressionCaseId") + "/replays",
      "POST",
      { idempotencyKey: text("regressionReplayKey") },
    );
    show("regressionReport", result);
    if (result.id) {
      $("regressionReplayId").value = result.id;
      $("feedbackReplayId").value = result.id;
      $("regressionReplayKey").value = key();
    }
    notice("仅按受审参考进行真实回放；passed并非整体业务成功，也不自动技术闭合反馈。");
  });
  bind("readRegressionReplay", async () =>
    show("regressionReport", await consumer("/regressions/replays/" + uuid("regressionReplayId"))),
  );
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
  $("regressionReplayKey").value = key();
})();
