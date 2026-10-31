package com.zifang.z.mask.web.api;

import com.zifang.z.mask.core.service.MaskResult;
import com.zifang.z.mask.core.service.MaskService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 脱敏端点。
 *
 * <p><b>路由前缀</b>：{@code /z-team/api/mask/**}。
 *
 * <p><b>返回脱敏后文本 + 脱敏明细**（明细含起止 / 原文 / 遮蔽后 / 策略 ID / 类型），
 * 供调用方自证「哪几处被遮了、按哪条策略遮的」。
 *
 * @author zifang
 */
@RestController
@RequestMapping("/z-team/api/mask")
public class MaskController {

    /** 脱敏服务（可空 ⇒ 未装配）。 */
    private final ObjectProvider<MaskService> maskServiceProvider;

    /**
     * 构造。
     *
     * @param maskServiceProvider 脱敏服务提供者
     */
    public MaskController(ObjectProvider<MaskService> maskServiceProvider) {
        this.maskServiceProvider = maskServiceProvider;
    }

    /**
     * 脱敏一段文本。
     *
     * @param request 请求体
     * @return 脱敏结果
     */
    @PostMapping("/apply")
    public ResponseEntity<Map<String, Object>> apply(@RequestBody MaskRequest request) {
        MaskService service = maskServiceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(503).body(error("脱敏服务未装配"));
        }
        MaskResult result = service.mask(request.getText());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("masked", result.isMasked());
        body.put("maskedText", result.getMaskedText());
        body.put("count", result.getItems() == null ? 0 : result.getItems().size());
        body.put("items", result.getItems() == null ? java.util.List.of()
                : result.getItems().stream().map(item -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("start", item.getStart());
                    m.put("end", item.getEnd());
                    m.put("original", item.getOriginal());
                    m.put("masked", item.getMasked());
                    m.put("policyId", item.getPolicyId());
                    m.put("maskType", item.getMaskType() == null ? null : item.getMaskType().name());
                    return m;
                }).toList());
        return ResponseEntity.ok(body);
    }

    /** 错误体。 */
    private static Map<String, Object> error(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", false);
        body.put("message", message);
        return body;
    }

    /**
     * 脱敏请求体。
     */
    public static class MaskRequest {

        /** 待脱敏文本。 */
        private String text;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }
    }
}
