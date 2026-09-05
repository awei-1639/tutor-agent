package com.tutor.identity.resume;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PiiMaskerTest {

    @Test
    void masksPhoneEmailIdCardWithMapping() {
        String text = "张三丰\n电话 13812345678，邮箱 zsf@example.com\n身份证 110101199001011234";
        var r = PiiMasker.mask(text);
        assertThat(r.masked()).doesNotContain("13812345678", "zsf@example.com", "110101199001011234", "张三丰");
        assertThat(r.masked()).contains("[PHONE_1]", "[EMAIL_1]", "[IDCARD_1]", "[NAME_1]");
        assertThat(r.mapping())
                .containsEntry("[PHONE_1]", "13812345678")
                .containsEntry("[EMAIL_1]", "zsf@example.com")
                .containsEntry("[IDCARD_1]", "110101199001011234")
                .containsEntry("[NAME_1]", "张三丰");
    }

    @Test
    void idCardNotEatenByPhonePattern() {
        // 身份证先于手机号处理, 17位前缀不应被误识别为手机号
        var r = PiiMasker.mask("证件号 11010119900101123X 联系 15900001111");
        assertThat(r.masked()).contains("[IDCARD_1]", "[PHONE_1]");
        assertThat(r.mapping()).hasSize(2);
    }

    @Test
    void commonTitleFirstLineIsNotMaskedAsName() {
        var r = PiiMasker.mask("个人简历\n工作经历：某公司Java开发");
        assertThat(r.masked()).startsWith("个人简历"); // 标题黑名单: 不是姓名
        assertThat(r.mapping()).isEmpty();
    }

    @Test
    void multipleOccurrencesNumberedSeparately() {
        var r = PiiMasker.mask("主号13812345678 备用13987654321");
        assertThat(r.masked()).contains("[PHONE_1]", "[PHONE_2]");
    }

    @Test
    void masksLabeledContactFieldsAndWechat() {
        var r = PiiMasker.mask("姓名：张三，地址：北京市海淀区中关村1号，微信号: wxid_abc123");
        assertThat(r.masked()).doesNotContain("张三", "北京市海淀区中关村1号", "wxid_abc123");
        assertThat(r.mapping().values()).contains("张三", "北京市海淀区中关村1号", "wxid_abc123");
    }
}
