package dev.shivamgulia.filebackup.storage;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;

/**
 * S3 store (aws profile). Each file is an object keyed by its backup path — same one-copy,
 * real-names contract as {@link LocalFileStorage}: hash while streaming to a temp file
 * (S3 needs a known length), then upload to the path key.
 */
@Component
@Profile("aws")
public class S3FileStorage implements FileStorage {

    private final S3Client s3;
    private final String bucket;

    public S3FileStorage(@Value("${filebackup.aws.bucket}") String bucket) {
        this.bucket = bucket;
        this.s3 = S3Client.create(); // region + credentials from the default provider chain
    }

    @Override
    public String store(String path, InputStream in) {
        try {
            Files.createDirectories(Path.of(System.getProperty("java.io.tmpdir")));
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            Path temp = Files.createTempFile("upload-", ".tmp");
            try (in) {
                Files.copy(new DigestInputStream(in, sha256), temp);
            }
            try {
                s3.putObject(b -> b.bucket(bucket).key(path), RequestBody.fromFile(temp));
                return HexFormat.of().formatHex(sha256.digest());
            } finally {
                Files.delete(temp);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        } catch (IOException e) {
            throw new UncheckedIOException("File store failure: " + path, e);
        }
    }

    @Override
    public InputStream read(String path) {
        return s3.getObject(b -> b.bucket(bucket).key(path));
    }

    @Override
    public void delete(String path) {
        s3.deleteObject(b -> b.bucket(bucket).key(path));
    }

    @Override
    public boolean exists(String path) {
        try {
            s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(path).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        }
    }

    @Override
    public long size(String path) {
        return s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(path).build()).contentLength();
    }

    @Override
    public String presignUpload(String path) {
        var presign = S3Presigner.create().presignPutObject(b -> b
                .signatureDuration(Duration.ofMinutes(15))
                .putObjectRequest(p -> p.bucket(bucket).key(path)));
        return presign.url().toString();
    }
}
