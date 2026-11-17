/* 准入视图不接管工程控制流；作者、审核和平台调用各自独立认证，实际部署仍在外部平台。 */
(() => {
  "use strict";
  const C = globalThis.AweWorkbench,
    { $, bind, text, uuid, hash, show, cards, key, notice } = C;
  const author = C.client("productionAuthorToken"),
    reviewer = C.client("productionReviewerToken"),
    platform = C.client("productionPlatformToken");
  let pipelines = [],
    proposal = null;
  window.addEventListener("awe-identity-cleared", () => {
    proposal = null;
    resetRealizationIntent();
  });
  function admission(document) {
    proposal = document;
    show("productionAdmission", document);
    if (document.id) $("productionAdmissionId").value = document.id;
    $("productionCheckId").value =
      document.latestCheck?.status === "PASSED" ? document.latestCheck.id : "";
    return document;
  }
  bind("loadProductionPipelines", async () => {
    pipelines = (await author("/releases")).filter(
      (row) => row.entity_type === "ObjectType" && row.entity_id?.startsWith("pipeline."),
    );
    const select = $("productionPipeline");
    select.replaceChildren();
    if (!pipelines.length) {
      const option = C.el("option", "没有可见已发布Pipeline");
      option.value = "";
      select.append(option);
      notice("没有可用Pipeline发布，不生成示例制品。");
      return;
    }
    for (const row of pipelines) {
      const option = C.el("option", row.entity_id + " @ " + row.version);
      option.value = row.id;
      select.append(option);
    }
    fillPipeline();
  });
  function fillPipeline() {
    const row = pipelines.find((item) => item.id === $("productionPipeline").value);
    if (row) {
      $("productionPipelineId").value = row.id;
      $("productionPipelineHash").value = row.content_hash;
    }
  }
  $("productionPipeline").onchange = fillPipeline;
  bind("readProductionPlan", async () => {
    const document = await author(
      "/production/plans/" + uuid("productionPipelineId") + "?contentHash=" + hash("productionPipelineHash"),
    );
    show("productionPlan", document);
    const summary = $("productionArtifactScope");
    summary.replaceChildren();
    if (document.externalCandidate) {
      const candidate = document.externalCandidate;
      summary.append(C.el("p", "正式加工意图 Hash：" + document.contentHash, "muted"));
      if (candidate.status === "NOT_CONFIGURED") {
        $("productionArtifactId").value = "";
        summary.append(C.el("p", "REGISTERED_EXTERNAL 尚未登记外部制品与有限验证范围。当前只有正式加工意图，不能取得部署许可。", "callout warn"));
      } else {
        $("productionArtifactId").value = candidate.artifactId;
        summary.append(
          C.el("p", "已登记外部制品原文 Hash：" + candidate.contentHash, "muted"),
          C.el("p", "有限验证范围：" + candidate.proofScopeRef + "；执行范围：" + candidate.executionMode, "callout"),
        );
      }
    } else summary.append(C.el("p", "MODEL_COMPILED：平台实际制品须与服务端规范内容及其 Hash 完全一致。", "muted"));
  });
  bind("prepareProduction", async () => {
    const document = await author("/production/admissions", "POST", {
      pipelineReleaseId: uuid("productionPipelineId"),
      pipelineContentHash: hash("productionPipelineHash"),
      artifactId: text("productionArtifactId"),
      environment: text("productionEnvironment"),
      idempotencyKey: text("productionPrepareKey"),
    });
    admission(document);
    notice("提案状态来自实际平台观测，PREPARED不代表回归通过、部署许可或物化完成。");
  });
  bind("listProduction", async () =>
    cards(
      "productionList",
      await author("/production/admissions"),
      (row) => row.pipeline_entity_id + " · " + row.environment + " · " + row.status,
      async (row) =>
        admission(await author("/production/admissions/" + encodeURIComponent(row.id))),
    ),
  );
  bind("readProduction", async () =>
    admission(await author("/production/admissions/" + uuid("productionAdmissionId"))),
  );
  bind("reviewerReadProduction", async () =>
    admission(await reviewer("/production/admissions/" + uuid("productionAdmissionId"))),
  );
  bind("verifyProduction", async () =>
    admission(
      await author(
        "/production/admissions/" + uuid("productionAdmissionId") + "/verify",
        "POST",
        {},
      ),
    ),
  );
  bind("approveProduction", async () => {
    const ttl = Number(text("productionReceiptTtl"));
    if (!Number.isSafeInteger(ttl) || ttl < 30 || ttl > 900)
      throw Error("收据有效期必须是30—900秒整数");
    admission(
      await reviewer(
        "/production/admissions/" + uuid("productionAdmissionId") + "/approve",
        "POST",
        { checkId: uuid("productionCheckId"), expiresInSeconds: ttl },
      ),
    );
    notice("收据已按实际结果返回；仍需平台在线一次性消费并执行基线CAS。");
  });
  bind("fillProductionConsume", () => {
    const receipt = proposal?.receipt,
      document = receipt?.receipt_doc;
    if (!document || !receipt.unexpired || !receipt.receipt_hash)
      throw Error("请先由作者或Reviewer重新读取当前真实且未过期的批准收据。");
    $("productionConsumeDocument").value = JSON.stringify(
      {
        receiptId: document.receiptId,
        receiptHash: receipt.receipt_hash,
        artifactHash: document.artifactHash,
        pipelineReleaseId: document.pipeline.releaseId,
        pipelineContentHash: document.pipeline.contentHash,
        environment: document.environment,
        baselineRevision: document.expectedBaselineRevision,
        idempotencyKey: key(),
      },
      null,
      2,
    );
  });
  bind("consumeProduction", async () => {
    const result = await platform(
      "/production/admissions/" + uuid("productionAdmissionId") + "/consume",
      "POST",
      C.json("productionConsumeDocument"),
    );
    show("productionConsumption", result);
    if (result.id) $("productionConsumptionId").value = result.id;
    notice("CONSUMED仅表示一次性在线验票；实际部署状态仍以外部平台与后验物化观测为准。");
  });
  let realizationRequest = null;
  let realizationInFlight = false;
  let realizationGeneration = 0;
  function updateRealizationControls() {
    for (const id of [
      "newProductionRealizationKey",
      "productionRealizationKey",
      "productionConsumptionId",
      "productionDeploymentRef",
      "productionAdmissionId",
    ])
      $(id).disabled = realizationInFlight;
    $("observeProductionRealization").textContent = realizationRequest
      ? "重试同一次观测（保留原请求键）"
      : "发起新的物化观测";
  }
  function resetRealizationIntent() {
    realizationGeneration++;
    realizationRequest = null;
    realizationInFlight = false;
    $("productionRealizationKey").value = key();
    $("productionRealization").replaceChildren();
    updateRealizationControls();
  }
  function realizationContext() {
    return {
      admissionId: uuid("productionAdmissionId"),
      body: {
        consumptionId: uuid("productionConsumptionId"),
        deploymentRef: text("productionDeploymentRef"),
        idempotencyKey: text("productionRealizationKey"),
      },
    };
  }
  function realizationMessage(message, requestKey) {
    $("productionObservationStatus").replaceChildren(
      C.el("p", message, "callout warn"),
      C.el("p", "该报告对应的已提交请求键：" + requestKey, "muted"),
      C.el(
        "p",
        "PASSED仅记录该次观测在其时点与范围内通过，不是此刻平台活动版本或物化绑定仍有效的证明。",
        "muted",
      ),
    );
  }
  bind("newProductionRealizationKey", () => {
    if (realizationInFlight) throw Error("请求仍在途，请先等待本次响应。");
    resetRealizationIntent();
    $("productionObservationStatus").replaceChildren(
      C.el(
        "p",
        "已明确选择新的观测请求。它不会取消或改写服务端可能已经接受的旧请求；旧报告不作为新观测结果。",
        "callout",
      ),
    );
    notice("已生成新观测请求键；下一次提交申请新的观测，不是重读旧收据。");
  });
  bind("observeProductionRealization", async () => {
    const context = realizationContext();
    if (realizationRequest && JSON.stringify(context) !== JSON.stringify(realizationRequest))
      throw Error(
        "同一次重试必须保留原提案、Consumption、部署引用和请求键。需要修改范围时，请先明确生成新的观测请求键。",
      );
    realizationRequest = realizationRequest || context;
    const accepted = realizationRequest;
    const generation = realizationGeneration;
    realizationInFlight = true;
    updateRealizationControls();
    $("productionRealization").replaceChildren();
    realizationMessage(
      "请求正在等待服务端报告，尚无本次终态；不会沿用上一份PASSED作为本次结果。",
      accepted.body.idempotencyKey,
    );
    try {
      const result = await platform(
        "/production/admissions/" + accepted.admissionId + "/realizations",
        "POST",
        accepted.body,
      );
      if (generation !== realizationGeneration) return;
      show("productionRealization", result);
      const terminal =
        typeof result.status === "string" &&
        result.status.length > 0 &&
        !["RUNNING", "PENDING", "IN_PROGRESS"].includes(result.status);
      const historical = result.replayed === true;
      if (terminal) {
        // 服务端终态是本次收据的结束；即便返回历史报告，下一次点击也必须表达新观测意图。
        realizationRequest = null;
        $("productionRealizationKey").value = key();
        realizationMessage(
          historical
            ? "这是历史请求报告（replayed=true），服务端复用了原收据；不能把它当成刚刚重新观测的平台状态。下一次新观测键已轮换。"
            : "服务端返回本次请求的终态报告，未标记为历史重放。下一次新观测键已轮换；是否实际取得部署证据，以报告状态与限制为准。",
          accepted.body.idempotencyKey,
        );
        notice(
          historical
            ? "已显示历史收据；再次提交将使用新键申请新观测。"
            : "本次请求已取得终态收据；再次提交将申请新观测，PASSED不作实时有效证明。",
        );
      } else {
        realizationMessage(
          "尚未取得服务端终态；保留原请求键，只能重试同一次请求，不能据此判断物化成功。",
          accepted.body.idempotencyKey,
        );
        notice("观测仍未终态；重试使用同一请求键。");
      }
    } catch (error) {
      if (generation === realizationGeneration) {
        // 传输失败不证明服务端未接受：保留整个请求上下文，避免重试变成重复的新操作。
        realizationMessage(
          "未取得可确认的服务端终态。网络失败时原请求可能已被接受；原请求键和范围保留，重试不会擅自换键。",
          accepted.body.idempotencyKey,
        );
      }
      throw error;
    } finally {
      if (generation === realizationGeneration) {
        realizationInFlight = false;
        updateRealizationControls();
      }
    }
  });
  bind("listProductionRealizations", async () => {
    const rows = await author(
      "/production/datasets/" + uuid("productionDatasetId") + "/realizations",
    );
    show("productionRealization", rows);
    $("productionObservationStatus").replaceChildren(
      C.el(
        "p",
        "以下是服务端保存的物化登记记录，不是一次新的平台活动版本复检；不得仅凭历史PASSED推定当前仍有效。",
        "callout warn",
      ),
    );
  });
  $("productionPrepareKey").value = key();
  resetRealizationIntent();
})();
