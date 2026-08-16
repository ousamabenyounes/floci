package io.github.hectorvent.floci.services.s3;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;

@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class S3ReplicationIntegrationTest {

    private static final String BUCKET = "replication-int-test";
    private static final String CONFIG_BUCKET = "replication-config-test";
    private static final String REPLICATION_XML = """
            <ReplicationConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                <Role>arn:aws:iam::123456789012:role/replication-role</Role>
                <Rule>
                    <ID>rule-1</ID>
                    <Status>Enabled</Status>
                    <Prefix></Prefix>
                    <Destination>
                        <Bucket>arn:aws:s3:::replication-destination</Bucket>
                    </Destination>
                </Rule>
            </ReplicationConfiguration>
            """;

    @Test
    @Order(1)
    void createBucket() {
        given()
        .when()
            .put("/" + BUCKET)
        .then()
            .statusCode(200);
    }

    /**
     * Regression test for the bucket-destroying bug where {@code DELETE /{bucket}?replication}
     * (DeleteBucketReplication) was not handled and fell through to the unqualified
     * {@code DeleteBucket}, silently deleting the entire bucket. Real S3 removes only the
     * replication configuration and returns 204.
     */
    @Test
    @Order(2)
    void deleteReplicationDoesNotDeleteBucket() {
        given()
        .when()
            .delete("/" + BUCKET + "?replication")
        .then()
            .statusCode(204);
    }

    @Test
    @Order(3)
    void bucketStillExistsAfterReplicationDelete() {
        // A sub-resource-qualified DELETE must never remove the bucket itself.
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(4)
    void putVersioningAfterReplicationDeleteSucceeds() {
        given()
            .body("""
                    <VersioningConfiguration xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
                        <Status>Enabled</Status>
                    </VersioningConfiguration>
                    """)
        .when()
            .put("/" + BUCKET + "?versioning")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(5)
    void unqualifiedDeleteStillRemovesBucket() {
        given()
        .when()
            .delete("/" + BUCKET)
        .then()
            .statusCode(204);
        // Bucket is gone now: a sub-resource GET should report NoSuchBucket.
        given()
        .when()
            .get("/" + BUCKET + "?versioning")
        .then()
            .statusCode(404)
            .body(containsString("NoSuchBucket"));
    }

    // ── PutBucketReplication / GetBucketReplication routing (issue #2296) ──────────
    // Before the fix, PUT ?replication fell through to CreateBucket (BucketAlreadyOwnedByYou)
    // and GET ?replication fell through to ListObjects (a ListBucketResult read as empty config).

    @Test
    @Order(10)
    void createConfigBucket() {
        given()
        .when()
            .put("/" + CONFIG_BUCKET)
        .then()
            .statusCode(200);
    }

    @Test
    @Order(11)
    void getReplicationWithNoneConfiguredReturnsNotFound() {
        given()
        .when()
            .get("/" + CONFIG_BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"))
            .body(not(containsString("ListBucketResult")));
    }

    @Test
    @Order(12)
    void putReplicationStoresConfiguration() {
        given()
            .body(REPLICATION_XML)
        .when()
            .put("/" + CONFIG_BUCKET + "?replication")
        .then()
            .statusCode(200);
    }

    @Test
    @Order(13)
    void getReplicationReturnsStoredConfiguration() {
        given()
        .when()
            .get("/" + CONFIG_BUCKET + "?replication")
        .then()
            .statusCode(200)
            .body(containsString("ReplicationConfiguration"))
            .body(containsString("arn:aws:iam::123456789012:role/replication-role"))
            .body(containsString("arn:aws:s3:::replication-destination"))
            .body(not(containsString("ListBucketResult")));
    }

    @Test
    @Order(14)
    void deleteReplicationClearsStoredConfiguration() {
        given()
        .when()
            .delete("/" + CONFIG_BUCKET + "?replication")
        .then()
            .statusCode(204);
        // After delete, the bucket survives and replication reads as "not configured" again.
        given()
        .when()
            .get("/" + CONFIG_BUCKET + "?replication")
        .then()
            .statusCode(404)
            .body(containsString("ReplicationConfigurationNotFoundError"));
    }
}
