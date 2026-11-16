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
  bind("readProductionPlan", async () =>
    show(
      "productionPlan",
      await author(
        "/production/plans/" +
          uuid("productionPipelineId") +
          "?contentHash=" +
          hash("productionPipelineHash"),
      ),
    ),
  );
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
  bind("observeProductionRealization", async () =>
    show(
      "productionRealization",
      await platform(
        "/production/admissions/" + uuid("productionAdmissionId") + "/realizations",
        "POST",
        {
          consumptionId: uuid("productionConsumptionId"),
          deploymentRef: text("productionDeploymentRef"),
          idempotencyKey: text("productionRealizationKey"),
        },
      ),
    ),
  );
  bind("listProductionRealizations", async () =>
    show(
      "productionRealization",
      await author("/production/datasets/" + uuid("productionDatasetId") + "/realizations"),
    ),
  );
  $("productionPrepareKey").value = key();
  $("productionRealizationKey").value = key();
})();
