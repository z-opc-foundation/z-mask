package com.zifang.z.mask.core.service;

import com.zifang.z.mask.core.domain.MaskPolicy;
import com.zifang.z.mask.core.domain.MaskType;
import com.zifang.z.mask.core.spi.MaskStrategy;
import com.zifang.z.sense.core.domain.SenseResult;
import com.zifang.z.sense.core.domain.SensitiveCategory;
import com.zifang.z.sense.core.domain.SensitiveHit;
import com.zifang.z.sense.core.domain.SensitiveLevel;
import com.zifang.z.sense.core.service.SenseService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脱敏门面 —— 本仓对外的**唯一主入口**。
 *
 * <p><b>核心口径</b>：脱敏**以识别结果为输入**（不是自己再扫一遍）。
 * 流程恒为「<b>识别一次 → 脱敏一次</b>」：
 * <pre>
 *   文本 ──sense.detect──▶ 命中列表（带位置）
 *                              │
 *                              ├─▶ 策略匹配（类别 + 分级 ⇒ 策略）
 *                              └─▶ 按位置替换（**倒序替换**，避免前面的替换挪动后面的位置）
 *                                  ──▶ 脱敏后文本
 * </pre>
 * 倒序替换是关键：正序替换会把后面命中的位置**推偏**（前段长度变了）⇒ 替换错位。
 *
 * <p><b>★ 两条入口（M2）</b>：
 * <ul>
 *   <li>{@link #mask(String)} —— **自识别**：本仓内部跑一次识别再脱敏（单仓自用场景）；</li>
 *   <li>{@link #mask(String, List)} / {@link #mask(String, SenseResult)} ——
 *       **按已有命中脱敏**：调用方把识别结果直接交进来 ⇒ 全文**只识别一次**。</li>
 * </ul>
 * 编排层（z-biz-security）走后者：它为了做拦截判定必须先识别，
 * 若 MASK 阶段再识别一次就是**白扫两遍**，且两次结果可能漂移（规则集热更时）
 * ⇒ 拦截判定与脱敏结果打架。**重载就是这个缺口补上的。**
 *
 * <p><b>策略匹配口径</b>（{@link #resolvePolicy}）：
 * <ol>
 *   <li>先按 <b>类别 + 分级 ≥ 策略下限</b> 精确匹配；</li>
 *   <li>没命中 ⇒ 按 <b>类别</b> 匹配（忽略分级下限，取该类别最严格的一条）；</li>
 *   <li>还没命中 ⇒ 按 <b>类别 = null</b> 的通用策略匹配；</li>
 *   <li>都没有 ⇒ <b>不脱敏</b>（该类别未配策略 ⇒ 保持原样）。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：策略表不可变（构造后只读）⇒ 可并发调用。
 *
 * @author zifang
 */
public class MaskService {

    /** 敏感识别服务（脱敏的输入来源）。 */
    private final SenseService senseService;

    /** 类型 → 策略 实现。 */
    private final Map<MaskType, MaskStrategy> strategies;

    /** 策略表（有序：先注册的在前，匹配时取更具体的在前）。 */
    private final List<MaskPolicy> policies;

    public MaskService(SenseService senseService, List<MaskPolicy> policies) {
        this(senseService, policies, BuiltinMaskStrategies.all());
    }

    public MaskService(SenseService senseService, List<MaskPolicy> policies,
                       Map<MaskType, MaskStrategy> strategies) {
        this.senseService = senseService;
        this.policies = Collections.unmodifiableList(
                new ArrayList<>(policies == null ? List.of() : policies));
        this.strategies = Collections.unmodifiableMap(
                new LinkedHashMap<>(strategies == null ? Map.of() : strategies));
    }

    /**
     * 脱敏一段文本（**内部会先识别一次**）。
     *
     * @param text 待脱敏文本（可空）
     * @return 脱敏结果（含脱敏后文本 + 命中明细）
     */
    public MaskResult mask(String text) {
        if (text == null || text.isEmpty()) {
            return new MaskResult("", List.of());
        }
        SenseResult sense = senseService.detect(text);
        return maskByHits(text, sense.getHits());
    }

    /**
     * 按**已有命中列表**脱敏（**不再识别**）—— M2 的「识别一次 → 脱敏一次」正路。
     *
     * <p><b>为什么要这个重载</b>：编排层（z-biz-security）为了做<b>拦截判定</b>
     * 需要先跑一次识别拿到分级；M1 口径下 MASK 阶段会**再识别一遍** ⇒ 同一份文本被扫两次，
     * 白费一次算力，且两次结果理论上可能漂移（规则集热更时）⇒ 拦截判定与脱敏结果打架。
     * 本重载让调用方把<b>已有的命中列表</b>直接交给脱敏 ⇒ 全文只识别一次。
     *
     * <p><b>位置契约（调用方必须保证）</b>：命中的 {@code [start, end)} 必须是针对
     * <b>同一份原文</b>的偏移。本方法会做**边界校验**（越界命中直接跳过，不抛异常）
     * 与<b>重叠消解</b>（级别高者替换旧命中，同级先注册者留 —— 与 z-sense 的口径完全一致）。
     *
     * <p><b>兜底口径</b>：命中列表为空 ⇒ 原样返回（该类别未配策略 ⇒ 不脱敏，
     * 与 {@link #mask(String)} 一致）；**绝不因为「没命中」而返回原文里该遮的东西** ——
     * 因为没命中就意味着识别认为那里没有敏感数据。
     *
     * @param text 待脱敏文本（可空）
     * @param hits 命中列表（可空 ⇒ 原样返回）
     * @return 脱敏结果
     */
    public MaskResult mask(String text, List<SensitiveHit> hits) {
        if (text == null || text.isEmpty()) {
            return new MaskResult("", List.of());
        }
        if (hits == null || hits.isEmpty()) {
            return new MaskResult(text, List.of());
        }
        return maskByHits(text, hits);
    }

    /**
     * 按**已有识别结果**脱敏（**不再识别**）—— 与 {@link #mask(String, List)} 同义，
     * 但多一道**长度一致性校验**。
     *
     * <p><b>★ 长度校验的取舍</b>：若 {@code senseResult.getTextLength() != text.length()}
     * ⇒ 说明这份识别结果**不是对当前文本做的**（调用方传错了文本）⇒ 位置不可信。
     * 此时**不能**按它脱敏（会错位 ⇒ 可能把无关内容遮掉、或漏遮敏感内容），
     * **也不能**返回原文（会漏遮）⇒ 唯一正解是**退回重新识别**（牺牲一次识别，
     * 换「不漏遮 + 不错位」）。这是安全口径优先于性能口径的取舍。
     *
     * @param text   待脱敏文本（可空）
     * @param sense  已有识别结果（可空 ⇒ 原样返回）
     * @return 脱敏结果
     */
    public MaskResult mask(String text, SenseResult sense) {
        if (text == null || text.isEmpty()) {
            return new MaskResult("", List.of());
        }
        if (sense == null) {
            return new MaskResult(text, List.of());
        }
        if (sense.getTextLength() != text.length()) {
            // 契约被破坏 ⇒ 退回自识别（保底：不漏遮、不错位）
            SenseResult fresh = senseService.detect(text);
            return maskByHits(text, fresh.getHits());
        }
        return maskByHits(text, sense.getHits());
    }

    /**
     * 按命中列表做脱敏替换（**核心实现**）。
     *
     * <p>口径：
     * <ol>
     *   <li><b>边界校验</b>：越界命中（start &lt; 0 / end ≤ start / end &gt; 文本长）直接跳过；</li>
     *   <li><b>重叠消解</b>：级别高者**替换**旧命中，同级先注册者留（**与 z-sense 完全一致**）；</li>
     *   <li><b>倒序替换</b>：避免前段长度变化导致后段位置偏移；</li>
     *   <li><b>明细排回升序</b>：对外一律按位置升序。</li>
     * </ol>
     *
     * @param text 待脱敏文本
     * @param rawHits 原始命中列表（可空）
     * @return 脱敏结果
     */
    private MaskResult maskByHits(String text, List<SensitiveHit> rawHits) {
        List<SensitiveHit> hits = resolveOverlaps(text, rawHits);
        if (hits.isEmpty()) {
            return new MaskResult(text, List.of());
        }

        // 倒序替换：避免前段长度变化导致后段位置偏移
        StringBuilder sb = new StringBuilder(text);
        List<MaskResult.MaskedItem> items = new ArrayList<>();
        for (int i = hits.size() - 1; i >= 0; i--) {
            SensitiveHit hit = hits.get(i);
            MaskPolicy policy = resolvePolicy(hit);
            if (policy == null) {
                continue;
            }
            MaskStrategy strategy = strategies.get(policy.getMaskType());
            if (strategy == null) {
                continue;
            }
            String original = text.substring(hit.getStart(), hit.getEnd());
            String masked = strategy.mask(original, policy);
            if (masked == null) {
                continue;
            }
            sb.replace(hit.getStart(), hit.getEnd(), masked);
            items.add(new MaskResult.MaskedItem(hit.getStart(), hit.getEnd(), original, masked,
                    policy.getPolicyId(), policy.getMaskType()));
        }
        // 还原为按位置升序
        items.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
        return new MaskResult(sb.toString(), items);
    }

    /**
     * 命中列表的**边界校验 + 重叠消解**。
     *
     * <p>⚠ 外部传入的命中列表**不保证**已消解（z-sense 内部产出的已消解，但调用方
     * 可能自己拼）⇒ 本方法做一次兜底消解。
     *
     * <p><b>★ 消解口径必须与 z-sense 的 {@code SenseResult#addHit} 完全一致</b>：
     * 重叠时**级别高者替换**旧命中（不是「先占位者一律留」），
     * 级别相同时**先注册者留** ⇒ 结果稳定可复现。
     * 若本仓与 z-sense 口径不一致 ⇒ 上游消过解的命中在这里被再消一次，
     * 可能与上游**消出不同结果** ⇒ 脱敏位置与识别位置对不上 ⇒ 错位遮蔽。
     *
     * @param text  原文（用于边界校验）
     * @param rawHits 原始命中列表（可空）
     * @return 校验并消解后的命中列表（按起点升序）
     */
    private static List<SensitiveHit> resolveOverlaps(String text, List<SensitiveHit> rawHits) {
        if (rawHits == null || rawHits.isEmpty()) {
            return List.of();
        }
        int len = text == null ? 0 : text.length();
        // 1) 边界校验：越界命中直接丢弃（不抛异常 —— 安全组件不得拖垮主链路）
        List<SensitiveHit> valid = new ArrayList<>();
        for (SensitiveHit h : rawHits) {
            if (h == null) {
                continue;
            }
            if (h.getStart() < 0 || h.getEnd() <= h.getStart() || h.getEnd() > len) {
                continue;
            }
            valid.add(h);
        }
        if (valid.isEmpty()) {
            return List.of();
        }
        // 2) 重叠消解（**逐条比对**，与 SenseResult#addHit 同算法：级别高者替换，同级先到先留）
        List<SensitiveHit> out = new ArrayList<>();
        for (SensitiveHit h : valid) {
            int replaceIdx = -1;
            boolean overlapped = false;
            for (int i = 0; i < out.size(); i++) {
                SensitiveHit exist = out.get(i);
                if (h.getStart() < exist.getEnd() && exist.getStart() < h.getEnd()) {
                    overlapped = true;
                    if (weightOf(h) > weightOf(exist)) {
                        replaceIdx = i;
                    }
                    break;
                }
            }
            if (!overlapped) {
                out.add(h);
            } else if (replaceIdx >= 0) {
                out.set(replaceIdx, h);
            }
        }
        out.sort((a, b) -> Integer.compare(a.getStart(), b.getStart()));
        return out;
    }

    /** 命中的级别权重（无级别 ⇒ 0）。 */
    private static int weightOf(SensitiveHit hit) {
        return hit.getLevel() == null ? 0 : hit.getLevel().getWeight();
    }

    /**
     * 给定命中匹配脱敏策略。
     *
     * <p><b>匹配口径</b>（每一档都遵守 {@link MaskPolicy#getMinLevel()} 下限）：
     * <ol>
     *   <li>按 <b>类别</b> 匹配，取该类别中**下限最严格**（级别最高）且**覆盖本命中级别**的策略；</li>
     *   <li>没命中 ⇒ 按 <b>类别 = null</b> 的通用策略匹配（同样遵守下限）；</li>
     *   <li>都没有 ⇒ <b>不脱敏</b>（该类别未配策略 ⇒ 保持原样）。</li>
     * </ol>
     *
     * <p>⚠ **下限必须贯穿每一档**：早先的实现在「按类别兜底」那档忽略了 minLevel，
     * 导致 minLevel=L4 的策略把 L3 的数据也脱敏了 —— 下限形同虚设。
     *
     * @param hit 命中
     * @return 匹配的策略（**可能为 null** —— 表示该类别未配策略 ⇒ 不脱敏）
     */
    public MaskPolicy resolvePolicy(SensitiveHit hit) {
        if (hit == null) {
            return null;
        }
        SensitiveCategory category = hit.getCategory();
        SensitiveLevel level = hit.getLevel();

        // 1) 按类别匹配，取「覆盖本命中级别」且下限最严格的一条
        MaskPolicy best = null;
        for (MaskPolicy p : policies) {
            if (p.getCategory() != category) {
                continue;
            }
            if (!covers(p, level)) {
                continue;
            }
            if (best == null || stricter(p, best)) {
                best = p;
            }
        }
        if (best != null) {
            return best;
        }
        // 2) 通用策略（category = null），同样遵守下限
        for (MaskPolicy p : policies) {
            if (p.getCategory() == null && covers(p, level)) {
                return p;
            }
        }
        // 3) 无策略 ⇒ 不脱敏
        return null;
    }

    /** 策略是否覆盖给定的敏感级别（**下限贯穿每一档匹配**）。 */
    private static boolean covers(MaskPolicy policy, SensitiveLevel level) {
        if (policy.getMinLevel() == null) {
            return true;
        }
        if (level == null) {
            return false;
        }
        return level.atLeast(policy.getMinLevel());
    }

    /** a 是否比 b 更严格（下限更高 ⇒ 更严）。 */
    private static boolean stricter(MaskPolicy a, MaskPolicy b) {
        if (a.getMinLevel() == null) {
            return false;
        }
        if (b.getMinLevel() == null) {
            return true;
        }
        return a.getMinLevel().getWeight() > b.getMinLevel().getWeight();
    }

    /** 当前策略表（只读）。 */
    public List<MaskPolicy> getPolicies() {
        return policies;
    }
}
