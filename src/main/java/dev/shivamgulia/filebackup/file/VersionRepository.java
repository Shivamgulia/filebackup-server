package dev.shivamgulia.filebackup.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VersionRepository extends JpaRepository<VersionEntity, Long> {

    Optional<VersionEntity> findTopByFileIdOrderBySeqDesc(Long fileId);

    Optional<VersionEntity> findByFileIdAndSeq(Long fileId, int seq);

    long countByFileId(Long fileId);

    List<VersionEntity> findByFileIdAndSeqLessThan(Long fileId, int seq);
}
