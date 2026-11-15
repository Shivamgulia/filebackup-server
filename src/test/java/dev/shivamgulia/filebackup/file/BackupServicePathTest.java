package dev.shivamgulia.filebackup.file;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Covers path rules HTTP can't reach (e.g. "//" is normalized away by HTTP clients). */
class BackupServicePathTest {

    @Test
    void rejectsBadPaths() {
        for (String bad : new String[]{null, "", " ", "/abs.txt", "ends/", "a//b", "a/../b", "..", "noprefix", "a b/c", "a?b/c"}) {
            assertThatThrownBy(() -> BackupService.requirePath(bad)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void acceptsUsernamePrefixedPaths() {
        assertThatCode(() -> BackupService.requirePath("shivam/docs/report.pdf")).doesNotThrowAnyException();
    }
}
