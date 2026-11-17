package ai.awe.registry.production;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class ExternalArtifactTextTest {
    @Test
    void exactUtf8TextAllowsSqlWhitespaceAndValidSupplementaryCharacters() {
        assertDoesNotThrow(
                () ->
                        ProductionExternalArtifacts.requireUtf8Text(
                                "SELECT '中文 \ud83d\ude00'\r\n\t-- exact bytes"));
    }

    @Test
    void malformedSurrogatesCannotHashAsReplacementQuestionMark() {
        for (String text :
                new String[] {
                    "SELECT '\ud800'",
                    "SELECT '\udc00'",
                    "SELECT '\ud800X'",
                    "SELECT '" + (char) 0 + "'"
                })
            assertThrows(
                    IllegalArgumentException.class,
                    () -> ProductionExternalArtifacts.requireUtf8Text(text));
        assertDoesNotThrow(() -> ProductionExternalArtifacts.requireUtf8Text("SELECT '?'"));
    }
}
