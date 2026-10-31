package com.zifang.z.mask.starter.config;

import com.zifang.z.mask.core.service.MaskService;
import com.zifang.z.mask.core.support.DefaultPolicies;
import com.zifang.z.sense.core.service.SenseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * z-mask <b>starter 自动装配</b>（引擎装配，M2 #23 starter 拆分）。
 *
 * <p><b>定位</b>：只把<b>引擎</b>（{@link MaskService}）装配进宿主应用。宿主只需把
 * {@code z-mask-starter} 放进 classpath，{@code META-INF/spring/org.springframework.boot.
 * autoconfigure.AutoConfiguration.imports} 会把本装配挂上 ⇒ 零手工接线。
 *
 * <p><b>为什么不放 web</b>：早先引擎装配长在 {@code z-mask-web} 里 ⇒ 宿主要嵌入脱敏能力就得连
 * {@code spring-boot-starter-web}、健康检查、{@code z-boot-datasource-starter} 一起拖进来。
 * starter 把引擎单独摘出来 ⇒ 引擎是引擎，部署是部署。
 *
 * <p><b>让位口径</b>：全部 {@code @ConditionalOnMissingBean} ⇒ 宿主自己装配了同类型 bean
 * （比如想用自己的策略集）时本装配让位。
 *
 * <p>⚠️ 本装配**不扫描**包树（无 {@code @ComponentScan}）—— 跨域交互按组织口径走 HTTP 边界，
 * 不把别人的 jar 拖进本仓上下文。
 *
 * @author zifang
 */
@AutoConfiguration
public class MaskStarterAutoConfiguration {

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
}
