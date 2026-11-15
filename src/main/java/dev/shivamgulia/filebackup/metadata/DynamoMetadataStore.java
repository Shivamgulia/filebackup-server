package dev.shivamgulia.filebackup.metadata;

import dev.shivamgulia.filebackup.config.NotFoundException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single-table DynamoDB metadata store (aws profile), per LLD §7:
 * PK = path, SK = "#meta" (file record) / "v000001"... (versions, zero-padded so sort order is numeric).
 * Table must exist out of band (console/IaC); blob content lives in S3, never here.
 * shortcut: list() does a filtered Scan plus a per-file latest-version query — fine at personal
 * scale; switch to the GSI-1 browse pattern if the library outgrows it.
 */
@Component
@Profile("aws")
public class DynamoMetadataStore implements MetadataStore {

    private static final String META_SK = "#meta";

    private final DynamoDbClient db;
    private final String table;

    public DynamoMetadataStore(@Value("${filebackup.aws.table}") String table) {
        this.table = table;
        this.db = DynamoDbClient.create(); // region + credentials from the default provider chain
    }

    @Override
    public VersionRecord saveVersion(String path, String createdBy, String blobKey, String sha256Hex, long sizeBytes) {
        int seq = latestSeq(path) + 1;
        putMeta(path, createdBy, seq == 1); // fresh insert with condition; re-upload only clears the tombstone
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("PK", AttributeValue.fromS(path));
        item.put("SK", AttributeValue.fromS(sk(seq)));
        item.put("blobKey", AttributeValue.fromS(blobKey));
        item.put("sha256", AttributeValue.fromS(sha256Hex));
        item.put("sizeBytes", AttributeValue.fromN(String.valueOf(sizeBytes)));
        String createdAt = Instant.now().toString();
        item.put("createdAt", AttributeValue.fromS(createdAt));
        try {
            db.putItem(PutItemRequest.builder().tableName(table).item(item)
                    .conditionExpression("attribute_not_exists(SK)").build());
        } catch (ConditionalCheckFailedException e) {
            throw new DataIntegrityViolationException("Version " + seq + " already exists for " + path, e);
        }
        return new VersionRecord(path, seq, blobKey, sha256Hex, sizeBytes, Instant.parse(createdAt));
    }

    @Override
    public Optional<VersionRecord> latestVersion(String path) {
        Optional<Map<String, AttributeValue>> meta = metaItem(path);
        if (meta.isEmpty()) {
            return Optional.empty();
        }
        int seq = latestSeq(path);
        if (seq == 0) {
            return Optional.empty();
        }
        return Optional.ofNullable(toRecord(path, getItem(path, sk(seq))));
    }

    @Override
    public Optional<VersionRecord> version(String path, long version) {
        if (metaItem(path).isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(toRecord(path, getItem(path, sk((int) version))));
    }

    @Override
    public List<FileSummary> list(String pathPrefix) {
        // Scan with a filter: every item, meta items only, path prefix match, no tombstones
        Map<String, String> names = Map.of("#pk", "PK", "#sk", "SK");
        Map<String, AttributeValue> values = Map.of(
                ":meta", AttributeValue.fromS(META_SK),
                ":prefix", AttributeValue.fromS(pathPrefix));
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            var builder = ScanRequest.builder().tableName(table)
                    .filterExpression("#sk = :meta AND begins_with(#pk, :prefix) AND attribute_not_exists(deletedAt)")
                    .expressionAttributeNames(names).expressionAttributeValues(values);
            if (startKey != null) {
                builder = builder.exclusiveStartKey(startKey);
            }
            var result = db.scan(builder.build());
            items.addAll(result.items());
            startKey = result.lastEvaluatedKey().isEmpty() ? null : result.lastEvaluatedKey();
        } while (startKey != null);

        return items.stream()
                .map(item -> {
                    String path = item.get("PK").s();
                    int seq = latestSeq(path);
                    VersionRecord latest = seq == 0 ? null : toRecord(path, getItem(path, sk(seq)));
                    return latest == null ? null
                            : new FileSummary(path, latest.sizeBytes(), seq, latest.createdAt());
                })
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(FileSummary::path))
                .toList();
    }

    @Override
    public void tombstone(String path) {
        Map<String, AttributeValue> key = Map.of("PK", AttributeValue.fromS(path), "SK", AttributeValue.fromS(META_SK));
        if (db.getItem(GetItemRequest.builder().tableName(table).key(key).build()).item().isEmpty()) {
            throw new NotFoundException("No backup for path: " + path);
        }
        db.updateItem(UpdateItemRequest.builder().tableName(table).key(key)
                .updateExpression("SET deletedAt = :now")
                .expressionAttributeValues(Map.of(":now", AttributeValue.fromS(Instant.now().toString())))
                .build());
    }

    @Override
    public List<VersionRecord> versionsToPrune(String path, int keep) {
        int latest = latestSeq(path);
        if (latest <= keep) {
            return List.of();
        }
        // versions sorted desc by SK; everything past the newest `keep` is prunable
        List<VersionRecord> all = new ArrayList<>();
        Map<String, AttributeValue> values = Map.of(":p", AttributeValue.fromS(path), ":v", AttributeValue.fromS("v"));
        var result = db.query(QueryRequest.builder().tableName(table)
                .keyConditionExpression("#pk = :p AND begins_with(#sk, :v)")
                .expressionAttributeNames(Map.of("#pk", "PK", "#sk", "SK"))
                .expressionAttributeValues(values)
                .scanIndexForward(false)
                .build());
        result.items().forEach(item -> all.add(toRecord(path, item)));
        return all.stream().skip(keep).toList();
    }

    @Override
    public void deleteVersion(VersionRecord record) {
        db.deleteItem(DeleteItemRequest.builder().tableName(table)
                .key(Map.of("PK", AttributeValue.fromS(record.path()), "SK", AttributeValue.fromS(sk((int) record.version()))))
                .build());
    }

    // --- helpers ---

    private static String sk(int seq) {
        return String.format("v%06d", seq);
    }

    /** Highest version seq for a path, 0 when none. */
    private int latestSeq(String path) {
        var result = db.query(QueryRequest.builder().tableName(table)
                .keyConditionExpression("#pk = :p AND begins_with(#sk, :v)")
                .expressionAttributeNames(Map.of("#pk", "PK", "#sk", "SK"))
                .expressionAttributeValues(Map.of(":p", AttributeValue.fromS(path), ":v", AttributeValue.fromS("v")))
                .scanIndexForward(false)
                .limit(1)
                .build());
        if (result.items().isEmpty()) {
            return 0;
        }
        String sk = result.items().getFirst().get("SK").s();
        return Integer.parseInt(sk.substring(1));
    }

    /** Creates the meta item on first upload; afterwards only clears the tombstone. */
    private void putMeta(String path, String createdBy, boolean fresh) {
        Map<String, AttributeValue> key = Map.of("PK", AttributeValue.fromS(path), "SK", AttributeValue.fromS(META_SK));
        if (fresh) {
            try {
                db.putItem(PutItemRequest.builder().tableName(table).item(new HashMap<>(key))
                        .conditionExpression("attribute_not_exists(SK)")
                        .build());
                db.updateItem(UpdateItemRequest.builder().tableName(table).key(key)
                        .updateExpression("SET createdBy = :u, createdAt = :now")
                        .expressionAttributeValues(Map.of(
                                ":u", AttributeValue.fromS(createdBy),
                                ":now", AttributeValue.fromS(Instant.now().toString())))
                        .build());
            } catch (ConditionalCheckFailedException e) {
                // lost a race for first upload — fall through to the non-fresh update
            }
        }
        db.updateItem(UpdateItemRequest.builder().tableName(table).key(key)
                .updateExpression("REMOVE deletedAt")
                .build());
    }

    private Optional<Map<String, AttributeValue>> metaItem(String path) {
        Map<String, AttributeValue> item = getItem(path, META_SK);
        return item.containsKey("deletedAt") ? Optional.empty() : Optional.of(item);
    }

    private Map<String, AttributeValue> getItem(String path, String sk) {
        return db.getItem(GetItemRequest.builder().tableName(table)
                .key(Map.of("PK", AttributeValue.fromS(path), "SK", AttributeValue.fromS(sk)))
                .build()).item();
    }

    private static VersionRecord toRecord(String path, Map<String, AttributeValue> item) {
        if (item == null || item.isEmpty()) {
            return null;
        }
        return new VersionRecord(
                path,
                Integer.parseInt(item.get("SK").s().substring(1)),
                item.get("blobKey").s(),
                item.get("sha256").s(),
                Long.parseLong(item.get("sizeBytes").n()),
                Instant.parse(item.get("createdAt").s()));
    }
}
