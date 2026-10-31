package com.zifang.z.mask.web.config;

import com.zifang.z.mask.core.service.MaskService;
import com.zifang.z.mask.core.support.DefaultPolicies;
import com.zifang.z.sense.core.service.SenseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * z-mask自动装配。
 *
 * <p>宿主引用本仓时，只要把 {@code z-mask-web} 放进 classpath，
 * {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports}
 * 会把本装配挂上 ⇒ 数据源 + 领域服务齐备。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
@Import(MaskModuleDataSource.class)
public class MaskAutoConfiguration {

    /**
     * 脱敏服务（**默认策略集**）。
     *
     * <p>依赖注入 {@link SenseService}：脱敏**以识别结果为输入**。
     *
     * <p>⚠ **用 {@link ObjectProvider} 取识别服务，不直接注入**：
     * 若写成两个 {@code @ConditionalOnMissingBean} 方法（脱敏服务 + 兜底识别服务），
     * Spring 按方法注册顺序评估条件 ⇒ 脱敏服务先注册时容器里还没有识别服务 ⇒ **启动失败**。
     * {@code getIfAvailable(Supplier)} 的语义是「容器里有就用容器的，没有就地建一个」⇒ 两种情况都成立。
     *
     * @param senseServiceProvider 识别服务提供者
     * @return 脱敏服务
     */
    @Bean
    @ConditionalOnMissingBean
    public MaskService maskService(ObjectProvider<SenseService> senseServiceProvider) {
        SenseService senseService = senseServiceProvider.getIfAvailable(SenseService::new);
        return new MaskService(senseService, DefaultPolicies.policies());
    }

    /**
     * 本域健康检查（探活用）。
     *
     * @return 健康标识
     */
    @Bean
    @ConditionalOnMissingBean
    public MaskHealthIndicator maskHealthIndicator() {
        return new MaskHealthIndicator("z-mask");
    }
}
