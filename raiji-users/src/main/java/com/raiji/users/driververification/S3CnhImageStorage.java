package com.raiji.users.driververification;

import java.net.URI;

import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * S3 (produção) ou RustFS (local). O cliente é criado só no primeiro uso:
 * a aplicação sobe mesmo sem o armazenamento no ar.
 */
@Component
public class S3CnhImageStorage implements CnhImageStorage {

    private final DriverVerificationProperties.Storage config;
    private volatile S3Client client;

    public S3CnhImageStorage(DriverVerificationProperties properties) {
        this.config = properties.storage();
    }

    @Override
    public void put(String key, byte[] content, String contentType) {
        s3().putObject(
                PutObjectRequest.builder().bucket(config.bucket()).key(key).contentType(contentType).build(),
                RequestBody.fromBytes(content));
    }

    @Override
    public byte[] get(String key) {
        return s3().getObjectAsBytes(
                GetObjectRequest.builder().bucket(config.bucket()).key(key).build()).asByteArray();
    }

    @Override
    public void delete(String key) {
        s3().deleteObject(DeleteObjectRequest.builder().bucket(config.bucket()).key(key).build());
    }

    private S3Client s3() {
        S3Client current = client;
        if (current == null) {
            synchronized (this) {
                if (client == null) client = build();
                current = client;
            }
        }
        return current;
    }

    private S3Client build() {
        var builder = S3Client.builder()
                .httpClient(UrlConnectionHttpClient.create())
                .region(Region.of(blank(config.region()) ? "us-east-1" : config.region()))
                .forcePathStyle(Boolean.TRUE.equals(config.pathStyle()))
                .credentialsProvider(credentials());
        if (!blank(config.endpoint())) {
            builder.endpointOverride(URI.create(config.endpoint()))
                    // S3 compatível (RustFS): checksums só quando a operação exige
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        }
        return builder.build();
    }

    private AwsCredentialsProvider credentials() {
        if (!blank(config.accessKey()) && !blank(config.secretKey())) {
            return StaticCredentialsProvider.create(AwsBasicCredentials.create(config.accessKey(), config.secretKey()));
        }
        return DefaultCredentialsProvider.builder().build();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
