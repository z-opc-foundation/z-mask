package com.zifang.z.mask.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏端点验收（**真起 Spring 上下文**）。
 *
 * @author zifang
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "z.base.db.mask.url=jdbc:h2:mem:zmask_e2e;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "z.base.db.mask.username=sa",
                "z.base.db.mask.password="
        })
class MaskApiE2ETest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 校验位通过的身份证（L3）。 */
    private static final String ID_CARD = "110101199003078515";

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("脱敏端点：身份证被遮蔽 + 报出脱敏明细")
    void maskIdCard() throws Exception {
        ResponseEntity<String> r = apply("客户身份证 " + ID_CARD);

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("masked").asBoolean());
        String maskedText = node.get("maskedText").asText();
        assertFalse(maskedText.contains(ID_CARD), "身份证应被遮蔽：" + maskedText);
        assertTrue(node.get("count").asInt() > 0);

        JsonNode item = node.get("items").get(0);
        assertNotNullText(item.get("policyId").asText());
        assertTrue(item.get("start").asInt() >= 0);
        assertTrue(item.get("masked").asText().contains("*"));
    }

    @Test
    @DisplayName("脱敏端点：普通文本 ⇒ 不遮蔽")
    void maskPlainText() throws Exception {
        ResponseEntity<String> r = apply("今天天气不错，适合去公园散步。");

        JsonNode node = MAPPER.readTree(r.getBody());
        assertFalse(node.get("masked").asBoolean());
        assertEquals(0, node.get("count").asInt());
    }

    @Test
    @DisplayName("脱敏端点：多处命中 ⇒ 明细按位置升序")
    void maskMultipleOrdered() throws Exception {
        ResponseEntity<String> r = apply("身份证 " + ID_CARD + " 手机 13912345678");

        JsonNode node = MAPPER.readTree(r.getBody());
        assertTrue(node.get("count").asInt() >= 2);
        JsonNode items = node.get("items");
        int prev = -1;
        for (JsonNode item : items) {
            int start = item.get("start").asInt();
            assertTrue(start > prev, "明细应按位置升序");
            prev = start;
        }
    }

    @Test
    @DisplayName("健康检查可路由")
    void healthEndpoint() {
        ResponseEntity<String> r = rest.getForEntity("/api/mask/health", String.class);

        assertTrue(r.getStatusCode().is2xxSuccessful(), "状态码：" + r.getStatusCode());
    }

    /** 断言文本非空（避免用 assertNotNull 引入多余 import）。 */
    private static void assertNotNullText(String s) {
        assertFalse(s == null || s.isEmpty(), "不应为空");
    }

    private ResponseEntity<String> apply(String text) {
        String body = "{\"text\":\"" + text + "\"}";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return rest.exchange("/z-team/api/mask/apply", HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }
}
