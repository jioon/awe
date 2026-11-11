package ai.awe.kernel;
/** Zero, no rows, not-ready, invalid and access withheld are never interchangeable. */
public enum ResultValueState {
    PRESENT, ZERO, NO_RECORD, NOT_READY, NOT_APPLICABLE, INVALID, ACCESS_WITHHELD, UNKNOWN
}