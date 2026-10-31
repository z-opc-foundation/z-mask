package com.zifang.z.mask.starter;

import com.example.embedhost.StarterHostTestApplication;
import com.zifang.z.mask.core.service.MaskResult;
import com.zifang.z.mask.core.service.MaskService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * z-mask starter <b>嵌入式装配</b>验收（M2 #23）。
 *
 * <p>判据不只是「bean 长出来了」，还要**真的能脱敏** —— 否则装配出来的可能是空壳。
 * 宿主只把 starter jar 放进 classpath（无 web、无手工 @Import）⇒ 引擎可用。
 *
 * @author zifang
 */
class StarterEmbedTest {

    @Test
    @DisplayName("★ 宿主只放 starter jar ⇒ 脱敏服务自动装配，且真能把手机号遮住")
    void hostEmbeddingStarterGetsWorkingEngine() {
        try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(StarterHostTestApplication.class)
                .web(WebApplicationType.NONE)
                .run()) {

            MaskService service = ctx.getBean(MaskService.class);
            assertNotNull(service, "★ 宿主应用必须能仅靠 AutoConfiguration.imports 装配出 MaskService");

            String raw = "请拨打 13912345678 联系我";
            MaskResult result = service.mask(raw);
            String masked = result.getMaskedText();

            assertNotNull(masked, "★ 脱敏结果不能为空");
            assertFalse(masked.contains("13912345678"),
                    "★ 装配出来的脱敏服务必须真能遮蔽手机号，而不是空壳。实际输出：" + masked);
        }
    }
}
