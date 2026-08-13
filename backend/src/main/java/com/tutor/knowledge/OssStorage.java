package com.tutor.knowledge;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.common.auth.DefaultCredentialProvider;
import com.aliyun.oss.common.comm.SignVersion;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import com.aliyun.oss.model.PutObjectRequest;
import com.tutor.config.OssProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
