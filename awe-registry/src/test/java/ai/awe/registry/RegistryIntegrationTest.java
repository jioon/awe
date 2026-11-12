package ai.awe.registry;

import static org.junit.jupiter.api.Assertions.*;

import ai.awe.kernel.model.ActionDefinition;
import ai.awe.kernel.model.ContractDefinition;
import ai.awe.kernel.model.EvidenceAssertion;
import ai.awe.kernel.model.FunctionDefinition;
import ai.awe.kernel.model.LinkType;
import ai.awe.kernel.model.ObjectType;
import ai.awe.kernel.model.SemanticModel;
import ai.awe.registry.model.TypedModels;
import ai.awe.registry.support.JsonDocuments;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Real PostgreSQL + real application HTTP, including authentication and publication gates. The
 * local HTTP gateway is an explicit adapter-contract fixture, not a claim of real Hive/BI access.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "awe.auth.author-token=integration-http-author-token",
            "awe.auth.reviewer-token=integration-http-reviewer-token"
        })
@EnabledIfEnvironmentVariable(named = "AWE_TEST_POSTGRES", matches = "true")
final class RegistryIntegrationTest {
    private static final String AUTHOR = "integration-http-author-token";
    private static final String REVIEWER = "integration-http-reviewer-token";
    private static MetadataGatewayFixture gateway;
    private static final String V1 = "1.0.0";
    private final HttpClient client =
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Autowired JsonDocuments json;
    @Autowired JdbcTemplate db;
    @LocalServerPort int port;

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry properties) {
        properties.add("AWE_HIVE_METADATA_URL", () -> gateway().baseUrl());
        properties.add("AWE_BI_METADATA_URL", () -> "");
        properties.add("AWE_HIVE_METADATA_TOKEN", () -> "");
        properties.add("AWE_BI_METADATA_TOKEN", () -> "");
    }

    private static synchronized MetadataGatewayFixture gateway() {
        if (gateway == null) gateway = new MetadataGatewayFixture();
        return gateway;
    }

    @AfterAll
    static void closeGateway() {
        if (gateway != null) gateway.close();
    }

    @Test
    void everyMetamodelFamilyIsTypedPersistedPublishedAndReadBackOverHttp() throws Exception {
        String domain = domain();
        var user = publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        JsonNode imported = importSource(source);
        var dataset =
                publish(
                        domain,
                        "Dataset",
                        datasetSpec(domain, V1, source, imported.path("fingerprint").asText()));
        var metric =
                publish(
                        domain,
                        "Metric",
                        spec(
                                "Daily activity",
                                model(
                                        "ref",
                                        ref("metric." + domain + ".activity", V1),
                                        "grain",
                                        grain("AGGREGATE", "user_id"),
                                        "time",
                                        time(),
                                        "subjectType",
                                        ref("entity." + domain + ".user", V1),
                                        "sourceDataset",
                                        ref("dataset." + domain + ".events", V1),
                                        "aggregation",
                                        Map.of(
                                                "kind",
                                                "COUNT_DISTINCT",
                                                "permittedRollupAxes",
                                                List.of(),
                                                "forbiddenRollupAxes",
                                                List.of("day")),
                                        "allowedDimensions",
                                        List.of())));
        var feature =
                publish(
                        domain,
                        "Feature",
                        spec(
                                "Activity feature",
                                model(
                                        "ref",
                                        ref("feature." + domain + ".activity", V1),
                                        "grain",
                                        grain("FEATURE", "user_id"),
                                        "time",
                                        time(),
                                        "subjectType",
                                        ref("entity." + domain + ".user", V1),
                                        "sourceDataset",
                                        ref("dataset." + domain + ".events", V1),
                                        "observationTimeField",
                                        "event_time",
                                        "availableAtField",
                                        "available_at")));
        var link =
                publish(
                        domain,
                        "LinkType",
                        spec(
                                "Event subject",
                                model(
                                        "ref",
                                        ref("link." + domain + ".event_user", V1),
                                        "fromType",
                                        ref("dataset." + domain + ".events", V1),
                                        "toType",
                                        ref("entity." + domain + ".user", V1),
                                        "meaning",
                                        "HAS_DIMENSION",
                                        "cardinality",
                                        "MANY_TO_ONE",
                                        "temporalPolicy",
                                        "AS_OF",
                                        "identityMappingRef",
                                        "identity.user_id",
                                        "certified",
                                        false)));
        var evidence =
                publish(
                        domain,
                        "EvidenceAssertion",
                        spec(
                                "Source schema evidence",
                                model(
                                        "ref",
                                        ref("assertion." + domain + ".schema", V1),
                                        "subject",
                                        ref("dataset." + domain + ".events", V1),
                                        "kind",
                                        "SCHEMA",
                                        "result",
                                        "PASSED",
                                        "checkerRef",
                                        "checker.source.schema",
                                        "modelReleaseHash",
                                        dataset.path("content_hash").asText(),
                                        "sourceSnapshotRef",
                                        imported.path("snapshotId").asText(),
                                        "checkedAt",
                                        Instant.now().toString(),
                                        "evidenceRefs",
                                        List.of(
                                                "source-snapshot:"
                                                        + imported.path("snapshotId").asText()),
                                        "limitations",
                                        List.of(
                                                "HTTP metadata fixture checks declared schema only; no row-level or business truth proof"))));
        var metricEvidenceSpec =
                evidenceSpec(
                        domain,
                        "metric_schema",
                        metric.path("content_hash").asText(),
                        imported.path("snapshotId").asText());
        ((ObjectNode) metricEvidenceSpec.path("model"))
                .set("subject", json.parse(json.write(ref("metric." + domain + ".activity", V1))));
        var metricEvidence = publish(domain, "EvidenceAssertion", metricEvidenceSpec);
        assertEquals(
                metric.path("content_hash"),
                metricEvidence.path("spec").path("model").path("modelReleaseHash"));
        String assertion = "assertion." + domain + ".schema@" + V1;
        var function =
                publish(
                        domain,
                        "FunctionDefinition",
                        spec(
                                "Read source schema",
                                model(
                                        "ref",
                                        ref("function." + domain + ".schema", V1),
                                        "ownerType",
                                        ref("dataset." + domain + ".events", V1),
                                        "operation",
                                        "source.schema.read",
                                        "parameters",
                                        List.of(
                                                Map.of(
                                                        "name",
                                                        "snapshot",
                                                        "type",
                                                        "STRING",
                                                        "required",
                                                        true)),
                                        "resultSchemaRef",
                                        "schema.source.columns",
                                        "executorBindingRef",
                                        "adapter.metadata.read",
                                        "requiredAssertions",
                                        List.of(assertion))));
        var action =
                publish(
                        domain,
                        "ActionDefinition",
                        spec(
                                "Governed backfill declaration",
                                model(
                                        "ref",
                                        ref("action." + domain + ".backfill", V1),
                                        "ownerType",
                                        ref("dataset." + domain + ".events", V1),
                                        "operation",
                                        "dataset.backfill",
                                        "sideEffect",
                                        "DATA_BACKFILL",
                                        "executorBindingRef",
                                        "adapter.scheduler.backfill",
                                        "authorizationPolicyRef",
                                        "policy.dataset.write",
                                        "idempotencyKeySpec",
                                        "dataset.partition.request",
                                        "requiresApproval",
                                        true,
                                        "rollbackOrCompensationRef",
                                        "compensation.restore.partition",
                                        "postconditionAssertions",
                                        List.of(assertion))));
        var contract =
                publish(
                        domain,
                        "ContractDefinition",
                        spec(
                                "Dataset publication contract",
                                model(
                                        "ref",
                                        ref("contract." + domain + ".schema", V1),
                                        "subject",
                                        ref("dataset." + domain + ".events", V1),
                                        "kind",
                                        "DATA",
                                        "ownerTeam",
                                        "warehouse_team",
                                        "obligations",
                                        List.of(
                                                Map.of(
                                                        "ruleId",
                                                        "check.model.static",
                                                        "at",
                                                        "PUBLISH",
                                                        "blocking",
                                                        true)))));

        var expected =
                Map.ofEntries(
                        Map.entry(user, ObjectType.class),
                        Map.entry(dataset, SemanticModel.Dataset.class),
                        Map.entry(metric, SemanticModel.Metric.class),
                        Map.entry(feature, SemanticModel.Feature.class),
                        Map.entry(link, LinkType.class),
                        Map.entry(evidence, EvidenceAssertion.class),
                        Map.entry(metricEvidence, EvidenceAssertion.class),
                        Map.entry(function, FunctionDefinition.class),
                        Map.entry(action, ActionDefinition.class),
                        Map.entry(contract, ContractDefinition.class));
        for (var entry : expected.entrySet()) {
            JsonNode release =
                    ok("GET", "/api/releases/" + entry.getKey().path("id").asText(), AUTHOR, null);
            assertEquals(entry.getKey().path("content_hash"), release.path("content_hash"));
            assertEquals(2, release.path("spec").path("schemaVersion").asInt());
            assertInstanceOf(
                    entry.getValue(),
                    new TypedModels()
                            .decode(release.path("entity_type").asText(), release.path("spec")));
            assertTrue(release.path("content_hash").asText().matches("[a-f0-9]{64}"));
            assertEquals(
                    json.sha256(release.path("canonical_document")),
                    release.path("content_hash").asText());
            assertManifestReferences(release);
        }
        assertEquals(
                "VERIFIED", dataset.path("bindings").path(0).path("verification_status").asText());
        assertEquals(
                "dataset." + domain + ".events",
                metric.path("spec").path("model").path("sourceDataset").path("id").asText());
        assertEquals(
                V1,
                metric.path("spec")
                        .path("model")
                        .path("sourceDataset")
                        .path("modelVersion")
                        .asText());
        JsonNode pinnedDataset = null;
        for (JsonNode relation : metric.path("relations"))
            if (("dataset." + domain + ".events")
                    .equals(relation.path("target_entity_id").asText())) pinnedDataset = relation;
        assertNotNull(
                pinnedDataset, "Metric release must persist its exact source Dataset dependency");
        assertEquals(V1, pinnedDataset.path("target_version").asText());
        assertEquals(dataset.path("id").asText(), pinnedDataset.path("target_release_id").asText());
        assertEquals(
                dataset.path("content_hash").asText(),
                pinnedDataset.path("target_content_hash").asText());
        assertFalse(
                ok("GET", "/api/impact?entityId=dataset." + domain + ".events", AUTHOR, null)
                        .isEmpty());
        var audit = ok("GET", "/api/audit?entityId=dataset." + domain + ".events", REVIEWER, null);
        assertTrue(audit.toString().contains("PUBLISHED"));
        assertRejected(
                "PUT",
                "/api/drafts/" + dataset.path("draft_id").asText(),
                AUTHOR,
                Map.of("expectedRevision", 1, "spec", dataset.path("spec")));
        assertEquals(
                dataset.path("spec"),
                ok("GET", "/api/releases/" + dataset.path("id").asText(), AUTHOR, null)
                        .path("spec"));
        // The database protects immutable manifests even if application code attempts a rewrite.
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_release SET version='9.9.9' WHERE id=?::uuid",
                                user.path("id").asText()));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_release_relation SET target_version='9.9.9' WHERE release_id=?::uuid",
                                metric.path("id").asText()));
        assertThrows(
                DataAccessException.class,
                () ->
                        db.update(
                                "UPDATE awe_source_binding SET verification_status='FORGED' WHERE release_id=?::uuid",
                                dataset.path("id").asText()));
    }

    @Test
    void bootstrapRolesAreEnforcedByTheActualHttpFilter() throws Exception {
        assertEquals(401, send("GET", "/api/domains", null, null).statusCode());
        assertEquals(401, send("GET", "/api/domains", "invalid-token", null).statusCode());
        assertEquals(
                403,
                send(
                                "POST",
                                "/api/domains",
                                REVIEWER,
                                Map.of(
                                        "code",
                                        uniqueDomain(),
                                        "displayName",
                                        "Forbidden reviewer create",
                                        "ownerTeam",
                                        "warehouse_team"))
                        .statusCode());
        String domain = domain();
        JsonNode draft = createDraft(domain, "ObjectType", objectSpec(domain, "user", V1));
        String id = draft.path("id").asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        assertEquals(
                403, send("POST", "/api/drafts/" + id + "/approve", AUTHOR, Map.of()).statusCode());
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        assertEquals(
                403,
                send("POST", "/api/drafts/" + id + "/publish", AUTHOR, Map.of("version", V1))
                        .statusCode());
        assertRejected(
                "POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", "1.1.0"));
        ok("POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", V1));
    }

    @Test
    void missingLiveConnectionIsNotConfiguredAndNeverProducesAFalseSuccessfulRelease()
            throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        var unconfigured =
                ok(
                        "POST",
                        "/api/sources/bi/imports",
                        AUTHOR,
                        Map.of("externalRef", "bi." + domain + ".metric"));
        assertEquals("NOT_CONFIGURED", unconfigured.path("status").asText());
        assertTrue(
                unconfigured.path("snapshotId").isNull()
                        || unconfigured.path("snapshotId").asText().isBlank());
        var candidate = datasetSpec(domain, V1, "bi." + domain + ".events", "0".repeat(64));
        ((ObjectNode) candidate.path("bindings").path(0)).put("sourceSystem", "bi");
        var draft = createDraft(domain, "Dataset", candidate);
        String id = draft.path("id").asText();
        var report = ok("POST", "/api/drafts/" + id + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("valid").asBoolean());
        assertTrue(report.toString().contains("NOT_CONFIGURED"));
        // Static submit/review may be possible, but an unconfigured source must never publish.
        var submit = send("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        if (submit.statusCode() == 200) {
            ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
            assertRejected(
                    "POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", V1));
        } else {
            assertTrue(submit.statusCode() == 400 || submit.statusCode() == 409, submit.body());
        }
        var releases = ok("GET", "/api/releases", AUTHOR, null);
        assertFalse(containsEntity(releases, "dataset." + domain + ".events"));
    }

    @Test
    void physicalDriftIsPersistedWithImpactAndBlocksFurtherPublication() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        var imported = importSource(source);
        var dataset =
                publish(
                        domain,
                        "Dataset",
                        datasetSpec(domain, V1, source, imported.path("fingerprint").asText()));
        publish(
                domain,
                "FunctionDefinition",
                spec(
                        "Downstream schema consumer",
                        model(
                                "ref",
                                ref("function." + domain + ".consume", V1),
                                "ownerType",
                                ref("dataset." + domain + ".events", V1),
                                "operation",
                                "source.schema.read",
                                "parameters",
                                List.of(),
                                "resultSchemaRef",
                                "schema.columns",
                                "executorBindingRef",
                                "adapter.metadata.read",
                                "requiredAssertions",
                                List.of())));
        ObjectNode changed = metadata(source, "physical-v2");
        ((ObjectNode) changed.path("columns").path(0)).put("type", "BIGINT");
        gateway().put(source, changed);
        var verify =
                ok(
                        "POST",
                        "/api/releases/" + dataset.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertEquals("DRIFTED", verify.path("status").asText());
        assertFalse(verify.path("checks").isEmpty());
        assertTrue(
                containsEntityOrDependent(verify.path("impact"), "function." + domain + ".consume"),
                verify.toString());
        assertFalse(verify.path("gaps").isEmpty());
        var gaps = ok("GET", "/api/gaps", AUTHOR, null);
        assertTrue(gaps.toString().contains(dataset.path("id").asText()));
        assertEquals(
                dataset.path("content_hash"),
                ok("GET", "/api/releases/" + dataset.path("id").asText(), AUTHOR, null)
                        .path("content_hash"));
        var stale = datasetSpec(domain, "1.1.0", source, imported.path("fingerprint").asText());
        String draftId = createDraft(domain, "Dataset", stale).path("id").asText();
        var report = ok("POST", "/api/drafts/" + draftId + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("valid").asBoolean());
        assertTrue(report.toString().contains("DRIFTED"));
        var submit = send("POST", "/api/drafts/" + draftId + "/submit", AUTHOR, Map.of());
        if (submit.statusCode() == 200) {
            ok("POST", "/api/drafts/" + draftId + "/approve", REVIEWER, Map.of());
            assertRejected(
                    "POST",
                    "/api/drafts/" + draftId + "/publish",
                    REVIEWER,
                    Map.of("version", "1.1.0"));
        } else assertTrue(submit.statusCode() == 400 || submit.statusCode() == 409, submit.body());
        JsonNode firstGap = gapFor(gaps, dataset.path("id").asText());
        assertNotNull(firstGap);
        var repeated =
                ok(
                        "POST",
                        "/api/releases/" + dataset.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        JsonNode repeatedGap = gapFor(repeated.path("gaps"), dataset.path("id").asText());
        assertNotNull(repeatedGap);
        assertEquals(
                firstGap.path("id"),
                repeatedGap.path("id"),
                "Repeated drift aggregates into the original gap");
        assertTrue(repeatedGap.path("occurrences").asInt() > firstGap.path("occurrences").asInt());
        // Acknowledgment is insufficient: resolution follows actual source restoration and recheck.
        gateway().put(source, metadata(source, "physical-v1"));
        var repaired =
                ok(
                        "POST",
                        "/api/releases/" + dataset.path("id").asText() + "/verify",
                        AUTHOR,
                        Map.of());
        assertEquals("VERIFIED", repaired.path("status").asText());
        JsonNode resolved = gapFor(repaired.path("gaps"), dataset.path("id").asText());
        assertNotNull(resolved);
        assertEquals(firstGap.path("id"), resolved.path("id"));
        assertEquals("RESOLVED", resolved.path("status").asText());
        assertFalse(resolved.path("resolved_at").isNull());
    }

    @Test
    void compatibilityRequiresMajorVersionForBreakingIdentityAndPreservesPreviousRelease()
            throws Exception {
        String domain = domain();
        var original = publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        var additive = objectSpec(domain, "user", "1.1.0");
        ((ArrayNode) additive.path("model").path("properties"))
                .add(
                        json.parse(
                                "{\"name\":\"display_name\",\"type\":\"STRING\",\"required\":false,\"sensitive\":false}"));
        publish(domain, "ObjectType", additive);
        var breaking = objectSpec(domain, "user", "1.2.0");
        ((ObjectNode) breaking.path("model").path("properties").path(0)).put("type", "INTEGER");
        String badId = createDraft(domain, "ObjectType", breaking).path("id").asText();
        var report = ok("POST", "/api/drafts/" + badId + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("compatibility").path("compatible").asBoolean());
        assertTrue(report.path("compatibility").path("breaking").asBoolean());
        var submitted = send("POST", "/api/drafts/" + badId + "/submit", AUTHOR, Map.of());
        if (submitted.statusCode() == 200) {
            ok("POST", "/api/drafts/" + badId + "/approve", REVIEWER, Map.of());
            assertRejected(
                    "POST",
                    "/api/drafts/" + badId + "/publish",
                    REVIEWER,
                    Map.of("version", "1.2.0"));
        } else
            assertTrue(
                    submitted.statusCode() == 400 || submitted.statusCode() == 409,
                    submitted.body());
        ((ObjectNode) breaking.path("model").path("ref")).put("modelVersion", "2.0.0");
        var major = publish(domain, "ObjectType", breaking);
        assertNotEquals(original.path("content_hash"), major.path("content_hash"));
        assertEquals(
                "STRING",
                ok("GET", "/api/releases/" + original.path("id").asText(), AUTHOR, null)
                        .path("spec")
                        .path("model")
                        .path("properties")
                        .path(0)
                        .path("type")
                        .asText());
        var unchanged = objectSpec(domain, "user", "1.0.1");
        String oldId = createDraft(domain, "ObjectType", unchanged).path("id").asText();
        assertFalse(
                ok("POST", "/api/drafts/" + oldId + "/validate", AUTHOR, Map.of())
                        .path("valid")
                        .asBoolean());
        assertCannotPublish(oldId, "1.0.1");
    }

    @Test
    void missingPinnedDependenciesAndWrongModelFamiliesFailClosed() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        var imported = importSource(source);
        var missing = datasetSpec(domain, V1, source, imported.path("fingerprint").asText());
        ((ObjectNode) missing.path("model").path("subjectType")).put("modelVersion", "9.9.9");
        String missingId = createDraft(domain, "Dataset", missing).path("id").asText();
        assertFalse(
                ok("POST", "/api/drafts/" + missingId + "/validate", AUTHOR, Map.of())
                        .path("valid")
                        .asBoolean());
        assertCannotPublish(missingId, V1);
        var metric =
                spec(
                        "Invalid source family",
                        model(
                                "ref",
                                ref("metric." + domain + ".bad", V1),
                                "grain",
                                grain("AGGREGATE", "user_id"),
                                "time",
                                time(),
                                "subjectType",
                                ref("entity." + domain + ".user", V1),
                                "sourceDataset",
                                ref("entity." + domain + ".user", V1),
                                "aggregation",
                                Map.of(
                                        "kind",
                                        "SUM",
                                        "permittedRollupAxes",
                                        List.of("day"),
                                        "forbiddenRollupAxes",
                                        List.of()),
                                "allowedDimensions",
                                List.of()));
        String badMetric = createDraft(domain, "Metric", metric).path("id").asText();
        assertFalse(
                ok("POST", "/api/drafts/" + badMetric + "/validate", AUTHOR, Map.of())
                        .path("valid")
                        .asBoolean());
        assertCannotPublish(badMetric, V1);
        var legacy =
                Map.of(
                        "name",
                        "Legacy untyped contract",
                        "definition",
                        "No inferred identity",
                        "ownerTeam",
                        "warehouse_team");
        var legacyResponse =
                send(
                        "POST",
                        "/api/drafts",
                        AUTHOR,
                        Map.of(
                                "domain",
                                domain,
                                "entityId",
                                "entity." + domain + ".legacy",
                                "entityType",
                                "BusinessEntity",
                                "spec",
                                legacy));
        if (legacyResponse.statusCode() == 200) {
            String id = json.parse(legacyResponse.body()).path("id").asText();
            assertRejected("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        } else assertEquals(400, legacyResponse.statusCode(), legacyResponse.body());
    }

    @Test
    void matchingPhysicalFingerprintDoesNotOverrideGrainOrTimeContractMismatch() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        var imported = importSource(source);
        for (boolean wrongGrain : List.of(true, false)) {
            var candidate = datasetSpec(domain, V1, source, imported.path("fingerprint").asText());
            if (wrongGrain)
                ((ObjectNode) candidate.path("model"))
                        .set("grain", json.parse(json.write(grain("EVENT", "user_id"))));
            else
                ((ObjectNode) candidate.path("bindings").path(0).path("timeFields"))
                        .put("EVENT_TIME", "event_id");
            String id = createDraft(domain, "Dataset", candidate).path("id").asText();
            var report = ok("POST", "/api/drafts/" + id + "/validate", AUTHOR, Map.of());
            assertFalse(report.path("valid").asBoolean(), report.toString());
            assertTrue(report.toString().contains("CONTRACT_MISMATCH"), report.toString());
            String historyPath = "/api/validation-checks?entityId=dataset." + domain + ".events";
            int beforePublication = ok("GET", historyPath, AUTHOR, null).size();
            assertCannotPublish(id, V1);
            var retained = ok("GET", historyPath, AUTHOR, null);
            assertTrue(
                    retained.size() > beforePublication,
                    "Failed publication must retain its check even after rollback");
            String candidateHash =
                    json.sha256(new TypedModels().canonicalSpec("Dataset", candidate));
            boolean found = false;
            for (var check : retained)
                if ("CONTRACT_MISMATCH".equals(check.path("status").asText())
                        && candidateHash.equals(check.path("candidate_hash").asText()))
                    found = true;
            assertTrue(
                    found,
                    "Durable model checks must identify the exact failed candidate and mismatch");
        }
    }

    @Test
    void evidenceCannotClaimADifferentReleaseHashOrUnrelatedSourceSnapshot() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        var imported = importSource(source);
        var dataset =
                publish(
                        domain,
                        "Dataset",
                        datasetSpec(domain, V1, source, imported.path("fingerprint").asText()));
        var unrelated = importSource("hive." + domain + ".unrelated");
        for (boolean wrongHash : List.of(true, false)) {
            var candidate =
                    evidenceSpec(
                            domain,
                            wrongHash ? "wrong_hash" : "wrong_scope",
                            wrongHash ? "0".repeat(64) : dataset.path("content_hash").asText(),
                            wrongHash
                                    ? imported.path("snapshotId").asText()
                                    : unrelated.path("snapshotId").asText());
            String id = createDraft(domain, "EvidenceAssertion", candidate).path("id").asText();
            var report = ok("POST", "/api/drafts/" + id + "/validate", AUTHOR, Map.of());
            assertFalse(report.path("valid").asBoolean(), report.toString());
            assertCannotPublish(id, V1);
        }
    }

    @Test
    void unknownBlockingRulesAndUnsupportedEnforcementStagesCannotBePublished() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        int i = 0;
        for (var obligation :
                List.of(
                        Map.of("ruleId", "check.unknown", "at", "PUBLISH", "blocking", true),
                        Map.of(
                                "ruleId",
                                "check.model.static",
                                "at",
                                "EXECUTE",
                                "blocking",
                                true))) {
            var candidate =
                    spec(
                            "Unsupported contract",
                            model(
                                    "ref",
                                    ref("contract." + domain + ".rule_" + i++, V1),
                                    "subject",
                                    ref("entity." + domain + ".user", V1),
                                    "kind",
                                    "DATA",
                                    "ownerTeam",
                                    "warehouse_team",
                                    "obligations",
                                    List.of(obligation)));
            String id = createDraft(domain, "ContractDefinition", candidate).path("id").asText();
            var report = ok("POST", "/api/drafts/" + id + "/validate", AUTHOR, Map.of());
            assertFalse(report.path("valid").asBoolean(), report.toString());
            assertTrue(
                    report.toString().contains("Unsupported blocking rule/stage"),
                    report.toString());
            assertCannotPublish(id, V1);
        }
    }

    @Test
    void dimensionReferencesCannotUseOrdinaryBusinessObjectsAsDimensions() throws Exception {
        String domain = domain();
        publish(domain, "ObjectType", objectSpec(domain, "user", V1));
        String source = "hive." + domain + ".events";
        var imported = importSource(source);
        var candidate = datasetSpec(domain, V1, source, imported.path("fingerprint").asText());
        ((ObjectNode) candidate.path("model"))
                .set(
                        "dimensions",
                        json.parse(json.write(List.of(ref("entity." + domain + ".user", V1)))));
        String id = createDraft(domain, "Dataset", candidate).path("id").asText();
        var report = ok("POST", "/api/drafts/" + id + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("valid").asBoolean(), report.toString());
        assertTrue(report.toString().contains("ObjectType DIMENSION"), report.toString());
        assertCannotPublish(id, V1);
    }

    @Test
    void optimisticDraftRevisionPreventsLostEditsAndReviewedContentCannotChange() throws Exception {
        String domain = domain();
        var draft = createDraft(domain, "ObjectType", objectSpec(domain, "user", V1));
        String id = draft.path("id").asText();
        assertEquals(1, draft.path("revision").asInt());
        var firstEditor = objectSpec(domain, "user", V1);
        firstEditor.put("name", "First editor saved model");
        var edited =
                ok(
                        "PUT",
                        "/api/drafts/" + id,
                        AUTHOR,
                        Map.of("expectedRevision", 1, "spec", firstEditor));
        assertEquals(2, edited.path("revision").asInt());
        var staleEditor = objectSpec(domain, "user", V1);
        staleEditor.put("name", "Stale page must not overwrite");
        assertEquals(
                409,
                send(
                                "PUT",
                                "/api/drafts/" + id,
                                AUTHOR,
                                Map.of("expectedRevision", 1, "spec", staleEditor))
                        .statusCode());
        var retained = ok("GET", "/api/drafts/" + id, AUTHOR, null);
        assertEquals(firstEditor, retained.path("spec"));
        assertEquals(2, retained.path("revision").asInt());
        assertEquals(
                400,
                send("PUT", "/api/drafts/" + id, AUTHOR, Map.of("spec", staleEditor)).statusCode());
        assertEquals(
                400,
                send(
                                "PUT",
                                "/api/drafts/" + id,
                                AUTHOR,
                                Map.of("expectedRevision", "2", "spec", staleEditor))
                        .statusCode());
        staleEditor.put("name", "Reloaded page saved current revision");
        assertEquals(
                3,
                ok(
                                "PUT",
                                "/api/drafts/" + id,
                                AUTHOR,
                                Map.of("expectedRevision", 2, "spec", staleEditor))
                        .path("revision")
                        .asInt());
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        assertRejected(
                "PUT",
                "/api/drafts/" + id,
                AUTHOR,
                Map.of("expectedRevision", 3, "spec", firstEditor));
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        var release = ok("POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", V1));
        assertEquals(
                "Reloaded page saved current revision", release.path("spec").path("name").asText());
    }

    @Test
    void legacyReleaseRequiresExplicitKnownFamilyMajorMigrationAndPreservesOriginalBytes()
            throws Exception {
        String domain = domain();
        ObjectNode legacySpec =
                json.object(
                        "{\"name\":\"Existing business user\",\"definition\":\"Historical owner-reviewed definition without typed identity\",\"ownerTeam\":\"warehouse_team\"}");
        String entity = "entity." + domain + ".user";
        JsonNode legacy = seedLegacyRelease(domain, entity, "BusinessEntity", legacySpec);

        // The author supplies the actual identity and full typed definition; nothing is guessed
        // from v1.
        var minor = objectSpec(domain, "user", "1.1.0");
        String minorId = createDraft(domain, "ObjectType", minor).path("id").asText();
        var report = ok("POST", "/api/drafts/" + minorId + "/validate", AUTHOR, Map.of());
        assertFalse(report.path("valid").asBoolean(), report.toString());
        assertTrue(report.path("compatibility").path("breaking").asBoolean(), report.toString());
        assertCannotPublish(minorId, "1.1.0");

        var wrongFamily = objectSpec(domain, "user", "2.0.0");
        ((ObjectNode) wrongFamily.path("model")).put("family", "PHYSICAL_ASSET");
        String wrongId = createDraft(domain, "ObjectType", wrongFamily).path("id").asText();
        assertFalse(
                ok("POST", "/api/drafts/" + wrongId + "/validate", AUTHOR, Map.of())
                        .path("valid")
                        .asBoolean());
        assertCannotPublish(wrongId, "2.0.0");

        var migrated = publish(domain, "ObjectType", objectSpec(domain, "user", "2.0.0"));
        assertEquals("ObjectType", migrated.path("entity_type").asText());
        assertEquals("2.0.0", migrated.path("version").asText());
        assertEquals(
                "BUSINESS_ENTITY", migrated.path("spec").path("model").path("family").asText());
        var original = ok("GET", "/api/releases/" + legacy.path("id").asText(), AUTHOR, null);
        assertEquals(legacySpec, original.path("spec"));
        assertEquals(json.sha256(legacySpec), original.path("content_hash").asText());
        assertEquals("BusinessEntity", original.path("entity_type").asText());
        assertEquals(1, original.path("schema_version").asInt());
        assertEquals("1.0.0", original.path("version").asText());

        // An unrecognized historical family has no implicit migration permission.
        seedLegacyRelease(domain, "entity." + domain + ".custom", "LegacyCustom", legacySpec);
        var unknown = objectSpec(domain, "custom", "2.0.0");
        String unknownId = createDraft(domain, "ObjectType", unknown).path("id").asText();
        assertFalse(
                ok("POST", "/api/drafts/" + unknownId + "/validate", AUTHOR, Map.of())
                        .path("valid")
                        .asBoolean());
        assertCannotPublish(unknownId, "2.0.0");
    }

    private JsonNode seedLegacyRelease(String domain, String entity, String type, JsonNode spec)
            throws Exception {
        String draftId =
                ok(
                                "POST",
                                "/api/drafts",
                                AUTHOR,
                                Map.of(
                                        "domain",
                                        domain,
                                        "entityId",
                                        entity,
                                        "entityType",
                                        type,
                                        "spec",
                                        spec))
                        .path("id")
                        .asText();
        UUID releaseId = UUID.randomUUID();
        db.update("UPDATE awe_draft SET state='PUBLISHED' WHERE id=?::uuid", draftId);
        db.update(
                "INSERT INTO awe_release(id,draft_id,domain,entity_id,entity_type,version,content_hash,spec,published_by,schema_version) VALUES(?,?::uuid,?,?,?,'1.0.0',?,?::jsonb,'historical-reviewer',1)",
                releaseId,
                draftId,
                domain,
                entity,
                type,
                json.sha256(spec),
                json.canonical(spec));
        return ok("GET", "/api/releases/" + releaseId, AUTHOR, null);
    }

    @Test
    void duplicateJsonKeysAndTrailingDocumentsFailBeforePersistence() throws Exception {
        String code = uniqueDomain();
        String valid =
                json.write(
                        Map.of(
                                "code",
                                code,
                                "displayName",
                                "Strict JSON",
                                "ownerTeam",
                                "warehouse_team"));
        String duplicate =
                "{\"code\":\""
                        + code
                        + "\",\"code\":\""
                        + uniqueDomain()
                        + "\",\"displayName\":\"Strict JSON\",\"ownerTeam\":\"warehouse_team\"}";
        assertEquals(400, sendRaw("POST", "/api/domains", AUTHOR, duplicate).statusCode());
        assertEquals(400, sendRaw("POST", "/api/domains", AUTHOR, valid + " {}").statusCode());
        assertEquals(
                0,
                db.queryForObject(
                        "SELECT count(*) FROM awe_domain WHERE code=?", Integer.class, code));
    }

    private void assertManifestReferences(JsonNode release) throws Exception {
        JsonNode document = release.path("canonical_document");
        assertTrue(document.path("dependencyManifest").isArray());
        assertTrue(document.path("sourceManifest").isArray());
        assertEquals(release.path("relations").size(), document.path("dependencyManifest").size());
        for (var dependency : document.path("dependencyManifest")) {
            JsonNode relation = null;
            for (var row : release.path("relations"))
                if (dependency
                        .path("entityId")
                        .asText()
                        .equals(row.path("target_entity_id").asText())) relation = row;
            assertNotNull(
                    relation, "Canonical dependency manifest must match immutable database pins");
            assertEquals(
                    relation.path("target_version").asText(), dependency.path("version").asText());
            assertEquals(
                    relation.path("target_release_id").asText(),
                    dependency.path("releaseId").asText());
            assertEquals(
                    relation.path("target_content_hash").asText(),
                    dependency.path("contentHash").asText());
        }
        int directSources = 0;
        for (var source : document.path("sourceManifest")) {
            UUID.fromString(source.path("snapshotId").asText());
            var snapshot =
                    ok(
                            "GET",
                            "/api/sources/snapshots/" + source.path("snapshotId").asText(),
                            AUTHOR,
                            null);
            assertEquals(
                    snapshot.path("source_system").asText(), source.path("sourceSystem").asText());
            assertEquals(
                    snapshot.path("external_ref").asText(), source.path("externalRef").asText());
            assertEquals(
                    snapshot.path("source_version").asText(),
                    source.path("sourceVersion").asText());
            assertEquals(
                    snapshot.path("fingerprint").asText(), source.path("fingerprint").asText());
            if (source.path("direct").asBoolean()) {
                directSources++;
                JsonNode binding = null;
                for (var row : release.path("bindings"))
                    if (source.path("sourceSystem")
                                    .asText()
                                    .equals(row.path("source_system").asText())
                            && source.path("externalRef")
                                    .asText()
                                    .equals(row.path("external_ref").asText())) binding = row;
                assertNotNull(binding);
                assertEquals(
                        binding.path("source_snapshot_id").asText(),
                        source.path("snapshotId").asText());
                assertEquals(
                        binding.path("source_fingerprint").asText(),
                        source.path("fingerprint").asText());
            }
        }
        assertEquals(release.path("bindings").size(), directSources);
    }

    private ObjectNode evidenceSpec(String domain, String name, String hash, String snapshot) {
        return spec(
                "Scoped source assertion",
                model(
                        "ref",
                        ref("assertion." + domain + "." + name, V1),
                        "subject",
                        ref("dataset." + domain + ".events", V1),
                        "kind",
                        "SCHEMA",
                        "result",
                        "PASSED",
                        "checkerRef",
                        "checker.source.schema",
                        "modelReleaseHash",
                        hash,
                        "sourceSnapshotRef",
                        snapshot,
                        "checkedAt",
                        Instant.now().toString(),
                        "evidenceRefs",
                        List.of("source-snapshot:" + snapshot),
                        "limitations",
                        List.of(
                                "Metadata observation does not prove warehouse row-level correctness")));
    }

    private String domain() throws Exception {
        String code = uniqueDomain();
        ok(
                "POST",
                "/api/domains",
                AUTHOR,
                Map.of(
                        "code",
                        code,
                        "displayName",
                        "Integration " + code,
                        "ownerTeam",
                        "warehouse_team"));
        return code;
    }

    private static String uniqueDomain() {
        return "it_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static Map<String, String> ref(String id, String version) {
        return Map.of("id", id, "modelVersion", version);
    }

    private static Map<String, Object> grain(String kind, String... keys) {
        return Map.of("keys", List.of(keys), "kind", kind);
    }

    private static Map<String, Object> time() {
        return Map.of(
                "roles",
                List.of("EVENT_TIME", "AVAILABLE_AT"),
                "businessZone",
                "Asia/Shanghai",
                "backfillPossible",
                true);
    }

    private static Map<String, Object> model(Object... entries) {
        var model = new LinkedHashMap<String, Object>();
        for (int i = 0; i < entries.length; i += 2) model.put((String) entries[i], entries[i + 1]);
        return model;
    }

    private ObjectNode spec(String name, Map<String, Object> model) {
        return json.object(
                json.write(
                        Map.of(
                                "schemaVersion",
                                2,
                                "name",
                                name,
                                "definition",
                                "Reviewed warehouse contract",
                                "ownerTeam",
                                "warehouse_team",
                                "model",
                                model)));
    }

    private ObjectNode objectSpec(String domain, String name, String version) {
        return spec(
                "Business user",
                model(
                        "ref",
                        ref("entity." + domain + "." + name, version),
                        "family",
                        "BUSINESS_ENTITY",
                        "description",
                        "Business user with stable source identity",
                        "properties",
                        List.of(
                                Map.of(
                                        "name",
                                        "user_id",
                                        "type",
                                        "STRING",
                                        "required",
                                        true,
                                        "sensitive",
                                        false)),
                        "identityKeys",
                        List.of("user_id")));
    }

    private ObjectNode datasetSpec(
            String domain, String version, String source, String fingerprint) {
        var spec =
                spec(
                        "Activity events",
                        model(
                                "ref",
                                ref("dataset." + domain + ".events", version),
                                "grain",
                                grain("EVENT", "event_id"),
                                "time",
                                time(),
                                "subjectType",
                                ref("entity." + domain + ".user", V1),
                                "dimensions",
                                List.of()));
        spec.set(
                "bindings",
                json.parse(
                        json.write(
                                List.of(
                                        Map.of(
                                                "sourceSystem",
                                                "hive",
                                                "externalRef",
                                                source,
                                                "fingerprint",
                                                fingerprint,
                                                "timeFields",
                                                Map.of(
                                                        "EVENT_TIME",
                                                        "event_time",
                                                        "AVAILABLE_AT",
                                                        "available_at"))))));
        return spec;
    }

    private ObjectNode metadata(String source, String version) {
        return json.object(
                json.write(
                        Map.of(
                                "externalRef",
                                source,
                                "sourceVersion",
                                version,
                                "columns",
                                List.of(
                                        Map.of(
                                                "name",
                                                "event_id",
                                                "type",
                                                "STRING",
                                                "nullable",
                                                false),
                                        Map.of(
                                                "name",
                                                "user_id",
                                                "type",
                                                "STRING",
                                                "nullable",
                                                false),
                                        Map.of(
                                                "name",
                                                "event_time",
                                                "type",
                                                "TIMESTAMP",
                                                "nullable",
                                                false),
                                        Map.of(
                                                "name",
                                                "available_at",
                                                "type",
                                                "TIMESTAMP",
                                                "nullable",
                                                false)),
                                "grainKeys",
                                List.of("event_id"),
                                "timeFields",
                                List.of("event_time", "available_at"))));
    }

    private JsonNode importSource(String source) throws Exception {
        gateway().put(source, metadata(source, "physical-v1"));
        JsonNode imported =
                ok("POST", "/api/sources/hive/imports", AUTHOR, Map.of("externalRef", source));
        assertEquals("IMPORTED", imported.path("status").asText());
        assertTrue(imported.path("fingerprint").asText().matches("[a-f0-9]{64}"));
        assertFalse(imported.path("snapshotId").asText().isBlank());
        return imported;
    }

    private JsonNode createDraft(String domain, String type, JsonNode spec) throws Exception {
        return ok(
                "POST",
                "/api/drafts",
                AUTHOR,
                Map.of(
                        "domain",
                        domain,
                        "entityId",
                        spec.path("model").path("ref").path("id").asText(),
                        "entityType",
                        type,
                        "spec",
                        spec));
    }

    private JsonNode publish(String domain, String type, JsonNode spec) throws Exception {
        String id = createDraft(domain, type, spec).path("id").asText();
        ok("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
        var release =
                ok(
                        "POST",
                        "/api/drafts/" + id + "/publish",
                        REVIEWER,
                        Map.of(
                                "version",
                                spec.path("model").path("ref").path("modelVersion").asText()));
        // Include known workflow identity without relying on list endpoint projections.
        ((ObjectNode) release).put("draft_id", id);
        assertEquals(
                "PUBLISHED", ok("GET", "/api/drafts/" + id, AUTHOR, null).path("state").asText());
        return release;
    }

    private HttpResponse<String> send(String method, String path, String token, Object body)
            throws Exception {
        return sendRaw(method, path, token, body == null ? null : json.write(body));
    }

    private HttpResponse<String> sendRaw(String method, String path, String token, String body)
            throws Exception {
        var req =
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                        .timeout(Duration.ofSeconds(30));
        if (token != null) req.header("Authorization", "Bearer " + token);
        if (body != null) req.header("Content-Type", "application/json");
        req.method(
                method,
                body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        return client.send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode ok(String method, String path, String token, Object body) throws Exception {
        var response = send(method, path, token, body);
        assertEquals(
                200, response.statusCode(), () -> method + " " + path + ": " + response.body());
        assertTrue(
                response.headers()
                        .firstValue("Content-Type")
                        .orElse("")
                        .contains("application/json"));
        return json.parse(response.body());
    }

    private void assertRejected(String method, String path, String token, Object body)
            throws Exception {
        var response = send(method, path, token, body);
        assertTrue(
                response.statusCode() == 400 || response.statusCode() == 409,
                () ->
                        "Expected rejection of "
                                + method
                                + " "
                                + path
                                + ", got "
                                + response.statusCode()
                                + ": "
                                + response.body());
    }

    private void assertCannotPublish(String id, String version) throws Exception {
        var submitted = send("POST", "/api/drafts/" + id + "/submit", AUTHOR, Map.of());
        if (submitted.statusCode() == 200) {
            ok("POST", "/api/drafts/" + id + "/approve", REVIEWER, Map.of());
            assertRejected(
                    "POST", "/api/drafts/" + id + "/publish", REVIEWER, Map.of("version", version));
        } else
            assertTrue(
                    submitted.statusCode() == 400 || submitted.statusCode() == 409,
                    submitted.body());
    }

    private static JsonNode gapFor(JsonNode list, String releaseId) {
        for (JsonNode item : list)
            if (releaseId.equals(item.path("release_id").asText())) return item;
        return null;
    }

    private static boolean containsEntity(JsonNode list, String id) {
        for (JsonNode item : list) if (id.equals(item.path("entity_id").asText())) return true;
        return false;
    }

    private static boolean containsEntityOrDependent(JsonNode list, String id) {
        for (JsonNode item : list)
            if (id.equals(item.path("entity_id").asText())
                    || id.equals(item.path("dependent").asText())) return true;
        return false;
    }
}
