package com.tutor.knowledge.document;

import com.aliyun.ocr_api20210707.Client;
import com.aliyun.ocr_api20210707.models.RecognizeAllTextRequest;
import com.aliyun.teautil.models.RuntimeOptions;
import com.tutor.platform.config.AliyunOcrProperties;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;

/** 对 OCR 统一识别（RecognizeAllText）的轻量适配器。 */
@Component
public class AliyunOcrClient {
    private final AliyunOcrProperties properties;

    public AliyunOcrClient(AliyunOcrProperties properties) { this.properties = properties; }
    public boolean enabled() { return properties.enabled(); }
    public int textDensityThreshold() { return properties.effectiveDensityThreshold(); }
    public int maxPages() { return properties.effectiveMaxPages(); }

    public String recognize(byte[] image) {
        if (!enabled()) return "";
        if (image.length == 0 || image.length > 10 * 1024 * 1024) throw new IllegalArgumentException("OCR 图片大小不符合限制");
        try {
            var config = new com.aliyun.teaopenapi.models.Config()
                    .setAccessKeyId(properties.accessKeyId())
                    .setAccessKeySecret(properties.accessKeySecret())
                    .setConnectTimeout(properties.effectiveTimeoutSeconds() * 1000)
                    .setReadTimeout(properties.effectiveTimeoutSeconds() * 1000);
            config.endpoint = "ocr-api." + properties.effectiveRegion() + ".aliyuncs.com";
            Client client = new Client(config);
            RecognizeAllTextRequest request = new RecognizeAllTextRequest()
                    .setType("General")
                    .setBody(new ByteArrayInputStream(image));
            var response = client.recognizeAllTextWithOptions(request, new RuntimeOptions());
            var data = response.getBody().getData();
            String content = data == null || data.getContent() == null ? "" : data.getContent().strip();
            if (content.isBlank()) throw new IllegalArgumentException("OCR 未识别出文本");
            return content;
        } catch (Exception e) {
            throw new IllegalStateException("阿里云 OCR 识别失败", e);
        }
    }
}
