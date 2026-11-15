package dev.shivamgulia.filebackup.file;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface FileRepository extends JpaRepository<FileEntity, Long> {

    Optional<FileEntity> findByPath(String path);

    Optional<FileEntity> findByCreatedByAndPath(String createdBy, String path);

    List<FileEntity> findByPathStartingWithAndDeletedAtIsNullOrderByPath(String pathPrefix);

    List<FileEntity> findByCreatedByAndPathStartingWithAndDeletedAtIsNullOrderByPath(String createdBy, String prefix);
}
