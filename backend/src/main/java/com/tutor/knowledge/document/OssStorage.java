package com.tutor.knowledge.document;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.AbortMultipartUploadRequest;
import com.aliyun.oss.model.CompleteMultipartUploadRequest;
import com.aliyun.oss.model.InitiateMultipartUploadRequest;
import com.aliyun.oss.model.ListPartsRequest;
import com.aliyun.oss.model.PartListing;
import com.aliyun.oss.model.PartETag;
import com.tutor.platform.config.OssProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URL;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/** 私有 OSS Bucket 的服务端适配；AccessKey 不会出现在接口响应中。 */
@Component
public class OssStorage {
    private final OssProperties properties;
    private volatile OSS client;

    public OssStorage(OssProperties properties) {
        this.properties = properties;
    }

    public void put(String objectKey, byte[] content, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(content.length);
        if (contentType != null && !contentType.isBlank()) metadata.setContentType(contentType);
        client().putObject(new PutObjectRequest(properties.bucket(), objectKey,
                new ByteArrayInputStream(content), metadata));
    }

    public byte[] get(String objectKey) {
        try (OSSObject object = client().getObject(properties.bucket(), objectKey)) {
            return object.getObjectContent().readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("读取 OSS 文档失败", e);
        }
    }

    public URL presignedPutUrl(String objectKey, String contentType, Duration validity) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(properties.bucket(), objectKey);
        request.setExpiration(new Date(System.currentTimeMillis() + validity.toMillis()));
        request.setMethod(com.aliyun.oss.HttpMethod.PUT);
        if (contentType != null && !contentType.isBlank()) request.setContentType(contentType);
        return client().generatePresignedUrl(request);
    }

    public String initiateMultipartUpload(String objectKey, String contentType) {
        ObjectMetadata metadata = new ObjectMetadata();
        if (contentType != null && !contentType.isBlank()) metadata.setContentType(contentType);
        InitiateMultipartUploadRequest request = new InitiateMultipartUploadRequest(properties.bucket(), objectKey, metadata);
        return client().initiateMultipartUpload(request).getUploadId();
    }

    public URL presignedUploadPartUrl(String objectKey, String uploadId, int partNumber, Duration validity) {
        GeneratePresignedUrlRequest request = new GeneratePresignedUrlRequest(properties.bucket(), objectKey,
                com.aliyun.oss.HttpMethod.PUT);
        request.setExpiration(new Date(System.currentTimeMillis() + validity.toMillis()));
        request.addQueryParameter("uploadId", uploadId);
        request.addQueryParameter("partNumber", String.valueOf(partNumber));
        return client().generatePresignedUrl(request);
    }

    public void completeMultipartUpload(String objectKey, String uploadId, List<PartETag> parts) {
        client().completeMultipartUpload(new CompleteMultipartUploadRequest(properties.bucket(), objectKey, uploadId, parts));
    }

    /** 返回 OSS 已接收的分片，使客户端刷新页面后可以续传。 */
    public List<PartETag> listMultipartParts(String objectKey, String uploadId) {
        ListPartsRequest request = new ListPartsRequest(properties.bucket(), objectKey, uploadId);
        request.setMaxParts(10_000);
        PartListing listing = client().listParts(request);
        return listing.getParts().stream()
                .map(part -> new PartETag(part.getPartNumber(), part.getETag()))
                .toList();
    }

    public void abortMultipartUpload(String objectKey, String uploadId) {
        client().abortMultipartUpload(new AbortMultipartUploadRequest(properties.bucket(), objectKey, uploadId));
    }

    public ObjectMetadata metadata(String objectKey) {
        return client().getObjectMetadata(properties.bucket(), objectKey);
    }

    public void delete(String objectKey) {
        client().deleteObject(properties.bucket(), objectKey);
    }

    public String documentKey(UUID documentId, String filename) {
        return properties.normalizedPrefix() + documentId + "/" + filename;
    }

    private OSS client() {
        OSS current = client;
        if (current != null) return current;
        synchronized (this) {
            if (client == null) {
                properties.requireConfigured();
                ClientBuilderConfiguration configuration = new ClientBuilderConfiguration();
                configuration.setSignatureVersion(SignVersion.V4);
                client = OSSClientBuilder.create()
                        .endpoint(properties.endpoint())
                        .credentialsProvider(new DefaultCredentialProvider(
                                properties.accessKeyId(), properties.accessKeySecret()))
                        .clientConfiguration(configuration)
                        .region(properties.region())
                        .build();
            }
            return client;
        }
    }
}
