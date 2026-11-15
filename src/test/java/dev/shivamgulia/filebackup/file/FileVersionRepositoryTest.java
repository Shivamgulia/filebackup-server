package dev.shivamgulia.filebackup.file;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = "spring.datasource.url=jdbc:postgresql://localhost:5432/filebackup_test_repo")
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class FileVersionRepositoryTest {

    @Autowired
    FileRepository fileRepository;

    @Autowired
    VersionRepository versionRepository;

    @Test
    void versionsKeepSeqOrderingAndLatestLookup() {
        var file = fileRepository.save(new FileEntity("shivam/docs/report.pdf", "shivam"));

        versionRepository.save(new VersionEntity(file, 1, "aaa", new byte[]{1}, 10));
        versionRepository.save(new VersionEntity(file, 2, "bbb", new byte[]{2}, 20));

        var latest = versionRepository.findTopByFileIdOrderBySeqDesc(file.getId()).orElseThrow();
        assertThat(latest.getSeq()).isEqualTo(2);
        assertThat(versionRepository.countByFileId(file.getId())).isEqualTo(2);
        assertThat(versionRepository.findByFileIdAndSeq(file.getId(), 1).orElseThrow().getBlobKey()).isEqualTo("aaa");
    }

    @Test
    void tombstoneExcludesFileFromActiveList() {
        var file = new FileEntity("shivam/docs/old.pdf", "shivam");
        file.setDeletedAt(Instant.now());
        fileRepository.save(file);

        var active = fileRepository.findByCreatedByAndPathStartingWithAndDeletedAtIsNullOrderByPath("shivam", "shivam/docs/");
        assertThat(active).isEmpty();
        assertThat(fileRepository.findByCreatedByAndPath("shivam", "shivam/docs/old.pdf")).isPresent();
    }

    @Test
    void duplicateSeqPerFileIsRejected() {
        var file = fileRepository.save(new FileEntity("shivam/docs/dup.pdf", "shivam"));
        versionRepository.save(new VersionEntity(file, 1, "aaa", new byte[]{1}, 10));

        assertThatThrownBy(() -> versionRepository.saveAndFlush(new VersionEntity(file, 1, "bbb", new byte[]{2}, 20)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
