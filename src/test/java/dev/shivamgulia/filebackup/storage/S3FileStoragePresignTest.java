package dev.shivamgulia.filebackup.storage;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Presigning is offline math — no AWS calls, static creds via the default chain's system properties. */
class S3FileStoragePresignTest {

    @AfterAll
    static void cleanup() {
        System.clearProperty("aws.region");
        System.clearProperty("aws.accessKeyId");
        System.clearProperty("aws.secretAccessKey");
    }

    @Test
    void presignsPutUrlForContentKey() {
        System.setProperty("aws.region", "us-east-1");
        System.setProperty("aws.accessKeyId", "test");
        System.setProperty("aws.secretAccessKey", "test");

        var storage = new S3FileStorage("my-bucket");
        String key = "ab".repeat(32);

        String url = storage.presignUpload(key);

        assertThat(url).startsWith("https://my-bucket.s3.").contains("amazonaws.com/" + key + "?");
        assertThat(url).contains("X-Amz-Expires=900"); // 15 minutes
    }
}
