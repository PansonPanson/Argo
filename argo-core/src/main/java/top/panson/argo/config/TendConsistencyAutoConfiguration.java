package top.panson.argo.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;

import java.util.concurrent.TimeUnit;

import top.panson.argo.custom.query.TaskTimeRangeQuery;
import top.panson.argo.custom.shard.ShardingKeyGenerator;
import top.panson.argo.exceptions.ConsistencyException;
import top.panson.argo.utils.*;
/**
 * 提供给SpringBoot的自动装配类 SPI使用
 *
 **/
@Slf4j
@Configuration
@EnableConfigurationProperties(value = {
        TendConsistencyParallelTaskConfigProperties.class,
        TendConsistencyFallbackConfigProperties.class,
        ShardModeConfigProperties.class
})
public class TendConsistencyAutoConfiguration {

    /**
     * 执行调度任务的线程池的配置
     */
    @Autowired
    private TendConsistencyParallelTaskConfigProperties consistencyParallelTaskConfigProperties;
    /**
     * 降级逻辑相关参数配置
     */
    @Autowired
    private TendConsistencyFallbackConfigProperties tendConsistencyFallbackConfigProperties;
    /**
     * 分片模式参数配置
     */
    @Autowired
    private ShardModeConfigProperties shardModeConfigProperties;

    /**
     * 框架级配置
     *
     * @return 配置bean
     */
    @Bean
    public TendConsistencyConfiguration tendConsistencyConfigService() {
        // 对配置进行检查
        doConfigCheck(consistencyParallelTaskConfigProperties, shardModeConfigProperties);

        // 会通过builder设计模式，完成bean实例的构建
        return TendConsistencyConfiguration
                .builder()
                .threadCorePoolSize(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getThreadCorePoolSize(), 5))
                .threadMaxPoolSize(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getThreadMaxPoolSize(), 5))
                .threadPoolQueueSize(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getThreadPoolQueueSize(), 100))
                .threadPoolKeepAliveTime(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getThreadPoolKeepAliveTime(), 60L))
                .threadPoolKeepAliveTimeUnit(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getThreadPoolKeepAliveTimeUnit(), "SECONDS"))
                .taskScheduleTimeRangeClassName(DefaultValueUtils.getOrDefault(consistencyParallelTaskConfigProperties.getTaskScheduleTimeRangeClassName(), ""))
                .failCountThreshold(DefaultValueUtils.getOrDefault(tendConsistencyFallbackConfigProperties.getFailCountThreshold(), 2))
                .taskSharded(DefaultValueUtils.getOrDefault(shardModeConfigProperties.getTaskSharded(), false))
                .shardingKeyGeneratorClassName(DefaultValueUtils.getOrDefault(shardModeConfigProperties.getShardingKeyGeneratorClassName(), ""))
                .build();
    }

    /**
     * 配置检查
     *
     * @param consistencyParallelTaskConfigProperties 并行任务相关的配置
     * @param shardModeConfigProperties               分片模式相关配置
     */
    private void doConfigCheck(TendConsistencyParallelTaskConfigProperties consistencyParallelTaskConfigProperties,
                               ShardModeConfigProperties shardModeConfigProperties) {
        TimeUnit timeUnit = null;
        if (!StringUtils.isEmpty(consistencyParallelTaskConfigProperties.getThreadPoolKeepAliveTimeUnit())) {
            try {
                timeUnit = TimeUnit.valueOf(consistencyParallelTaskConfigProperties.getThreadPoolKeepAliveTimeUnit());
            } catch (IllegalArgumentException e) {
                log.error("检查threadPoolKeepAliveTimeUnit配置时，发生异常", e);
                String errMsg = "threadPoolKeepAliveTimeUnit配置错误！注意：请在[SECONDS,MINUTES,HOURS,DAYS,NANOSECONDS,MICROSECONDS,MILLISECONDS]任选其中之一";
                throw new ConsistencyException(errMsg);
            }
        }

        if (!StringUtils.isEmpty(consistencyParallelTaskConfigProperties.getTaskScheduleTimeRangeClassName())) {
            // 校验是否存在该类
            Class<?> taskScheduleTimeRangeClass = ReflectTools.checkClassByName(consistencyParallelTaskConfigProperties.getTaskScheduleTimeRangeClassName());
            if (ObjectUtils.isEmpty(taskScheduleTimeRangeClass)) {
                String errMsg = String.format("未找到 %s 类，请检查类路径是否正确", consistencyParallelTaskConfigProperties.getTaskScheduleTimeRangeClassName());
                throw new ConsistencyException(errMsg);
            }
            // 用户自定义校验：校验是否实现了TaskTimeRangeQuery接口
            boolean result = ReflectTools.isRealizeTargetInterface(taskScheduleTimeRangeClass,
                    TaskTimeRangeQuery.class.getName());
            if (!result) {
                String errMsg = String.format("%s 类，未实现TaskTimeRangeQuery接口", consistencyParallelTaskConfigProperties.getTaskScheduleTimeRangeClassName());
                throw new ConsistencyException(errMsg);
            }
        }

        if (!StringUtils.isEmpty(shardModeConfigProperties.getShardingKeyGeneratorClassName())) {
            // 校验是否存在该类
            Class<?> shardingKeyGeneratorClass = ReflectTools.checkClassByName(shardModeConfigProperties.getShardingKeyGeneratorClassName());
            if (ObjectUtils.isEmpty(shardingKeyGeneratorClass)) {
                String errMsg = String.format("未找到 %s 类，请检查类路径是否正确", shardModeConfigProperties.getShardingKeyGeneratorClassName());
                throw new ConsistencyException(errMsg);
            }
            // 用户自定义校验：校验是否实现了ShardingKeyGenerator接口
            boolean result = ReflectTools.isRealizeTargetInterface(shardingKeyGeneratorClass,
                    ShardingKeyGenerator.class.getName());
            if (!result) {
                String errMsg = String.format("%s 类，未实现ShardingKeyGenerator接口", shardModeConfigProperties.getShardingKeyGeneratorClassName());
                throw new ConsistencyException(errMsg);
            }
        }
    }


}