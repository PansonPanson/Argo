package top.panson.argo.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 一致性任务降级的相关配置
 *
 **/
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ConfigurationProperties(prefix = "top.panson.argo.action")
public class TendConsistencyFallbackConfigProperties {

    /**
     * 触发降级逻辑的阈值 任务执行次数 如果大于该值 就会进行降级
     */
    public Integer failCountThreshold = 0;

}
