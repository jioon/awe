package ai.awe.kernel;

/**
 * Seven top-level metamodel abstractions.
 *
 * Types are NOT enterprise object instances. The model is metadata / executable contracts;
 * warehouse rows, job runs, and feature values stay in their source systems.
 */
public sealed interface AweDefinition permits ObjectType, LinkType, SemanticModel,
        FunctionDefinition, ActionDefinition, ContractDefinition, EvidenceAssertion {
    EntityRef ref();
}
