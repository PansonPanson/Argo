package top.panson.argo.aspect;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.JoinPoint;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import top.panson.argo.annotation.ConsistencyTask;
import top.panson.argo.config.TendConsistencyConfiguration;
import top.panson.argo.custom.shard.SnowflakeShardingKeyGenerator;
import top.panson.argo.enums.ConsistencyTaskStatusEnum;
import top.panson.argo.enums.PerformanceEnum;
import top.panson.argo.model.ConsistencyTaskInstance;
import top.panson.argo.service.TaskStoreService;
import top.panson.argo.utils.ReflectTools;
import top.panson.argo.utils.ThreadLocalUtil;
import top.panson.argo.utils.TimeUtils;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Date;

/**
 * 一致性事务框架切面
 *
 */
@Slf4j
@Aspect
@Component
public class ConsistencyAspect {

    /**
     * 缓存生成任务分片key的对象实例
     */
    private static Object cacheGenerateShardKeyClassInstance = null;
    /**
     * 缓存生成任务分片key的方法
     */
    private static Method cacheGenerateShardKeyMethod = null;

    /**
     * 一致性任务的service
     */
    @Autowired
    private TaskStoreService taskStoreService;
    /**
     * 框架配置
     */
    @Autowired
    private TendConsistencyConfiguration tendConsistencyConfiguration;

    /**
     * 标注了ConsistencyTask的注解的方法执行前要做的工作
     *
     * @param point 切面信息
     */
    @Around("@annotation(consistencyTask)")
    public Object markConsistencyTask(ProceedingJoinPoint point, ConsistencyTask consistencyTask) throws Throwable {
        log.info("access method:{} is called on {} args {}", point.getSignature().getName(), point.getThis(), point.getArgs());

        // 是否是调度器在执行任务，如果是则直接执行任务即可，因为之前已经进行了任务持久化
        // 这个大家肯定是看不懂的，对于这个执行方式，我们后续再来进行讲解，不要在这里直接来讲解了
        if (ThreadLocalUtil.getFlag()) {
            return point.proceed();
        }

        // 根据注解构造构造最终一致性任务的实例
        // @ConsistencyTask是我们给那个方法定义的一个注解，通过这个注解可以拿到我们自定义的各种框架参数
        // JoinPoint，切入点，方法信息，包括方法入参、方法名称、方法签名
        // 我们就可以封装出来对应的一致性任务实例
        // 本次针对我们的目标方法的调用，@ConsistencyTask注解，他是不是就是一个任务，Action
        // 一旦把目标任务抽象和封装起来以后，包括在数据库里做持久化，我们这些工作都可以做了
        ConsistencyTaskInstance taskInstance = createTaskInstance(consistencyTask, point);

        // 初始化任务数据到数据库
        taskStoreService.initTask(taskInstance);

        // 无论是调度执行还是立即执行的任务，任务初始化完成后不对目标方法进行访问，因此返回null
        return null;
    }

    /**
     * 根据注解构造构造最终一致性任务的实例
     *
     * @param task  一致性任务注解信息 相当于任务的模板
     * @param point 方法切入点
     * @return 一致性任务实例
     */
    private ConsistencyTaskInstance createTaskInstance(ConsistencyTask task, JoinPoint point) {
        // 通过@ConsistencyTask注解提取一系列的参数
        // 通过JoinPoint切入点，可以提取一系列的方法相关的数据

        // 根据入参数组获取对应的Class对象的数组
        Class<?>[] argsClazz = ReflectTools.getArgsClass(point.getArgs());
        // 获取被拦截方法的全限定名称 格式：类路径#方法名(参数1的类型,参数2的类型,...参数N的类型)
        String fullyQualifiedName = ReflectTools.getTargetMethodFullyQualifiedName(point, argsClazz);
        // 获取入参的类名称数组
        String parameterTypes = ReflectTools.getArgsClassNames(point.getSignature());

        Date date = new Date();

        // 一次任务执行 = 一次方法调用
        ConsistencyTaskInstance instance = ConsistencyTaskInstance.builder()
                // taskId，他默认用的就是方法全限定名称，所以说，针对一个方法n多次调用，taskId是一样的
                // taskId并不是唯一的id标识
                .taskId(StringUtils.isEmpty(task.id()) ? fullyQualifiedName : task.id())
                .methodName(point.getSignature().getName()) // 调用方法名称
                .parameterTypes(parameterTypes) // 调用方法入参的类型名称
                .methodSignName(fullyQualifiedName) // 方法签名
                .taskParameter(JSONUtil.toJsonStr(point.getArgs())) // 调用方法入参的对象数组，json串的转化
                .performanceWay(task.performanceWay().getCode()) // 注解里配置的执行模式，直接执行 vs 调度执行
                .threadWay(task.threadWay().getCode()) // 注解里配置的直接执行，同步还是异步，sync还是async，async会用我们自己初始化的线程池
                .executeIntervalSec(task.executeIntervalSec()) // 每次任务执行间隔时间
                .delayTime(task.delayTime())  // 任务执行延迟时间
                .executeTimes(0) // 任务执行次数
                .taskStatus(ConsistencyTaskStatusEnum.INIT.getCode()) // 任务当前所处的一个状态
                .errorMsg("") // 任务执行的时候异常信息
                .alertExpression(StringUtils.isEmpty(task.alertExpression()) ? "" : task.alertExpression()) // 限定了你的报警要在任务执行失败多少次的范围内去报警
                .alertActionBeanName(StringUtils.isEmpty(task.alertActionBeanName()) ? "" : task.alertActionBeanName()) // 如果要告警的话，他的告警逻辑的调用bean是谁
                .fallbackClassName(ReflectTools.getFullyQualifiedClassName(task.fallbackClass())) // 如果执行失败了，你的降级类是谁
                .fallbackErrorMsg("") // 如果降级也失败了，降级失败的异常信息
                .gmtCreate(date)
                .gmtModified(date)
                .build();

        // 设置预期执行的时间
        instance.setExecuteTime(getExecuteTime(instance));
        // 设置分片key
        instance.setShardKey(tendConsistencyConfiguration.getTaskSharded() ? generateShardKey() : 0L);

        return instance;
    }

    /**
     * 获取任务执行时间
     *
     * @param taskInstance 一致性任务实例
     * @return 下次执行时间
     */
    private long getExecuteTime(ConsistencyTaskInstance taskInstance) {
        if (PerformanceEnum.PERFORMANCE_SCHEDULE.getCode().equals(taskInstance.getPerformanceWay())) {
            long delayTimeMillSecond = TimeUtils.secToMill(taskInstance.getDelayTime());
            return System.currentTimeMillis() + delayTimeMillSecond; // 如果你是调度模式，一般是跟delay time配合使用的，你要延迟多少时间去执行
        } else {
            // 如果你要是设置了right now模式来执行的话，delayTime你设置了也是无效的
            return System.currentTimeMillis(); // 执行时间就是当前时间
        }
    }

    /**
     * 获取分片键
     *
     * @return 生成分片键
     */
    private Long generateShardKey() {
        // 如果配置文件中，没有配置自定义任务分片键生成类，则使用框架自带的
        if (StringUtils.isEmpty(tendConsistencyConfiguration.getShardingKeyGeneratorClassName())) {
            return SnowflakeShardingKeyGenerator.getInstance().generateShardKey();
        }
        // 如果生成任务CACHE_GENERATE_SHARD_KEY_METHOD的方法存在，就直接调用该方法
        if (!ObjectUtils.isEmpty(cacheGenerateShardKeyMethod)
                && !ObjectUtils.isEmpty(cacheGenerateShardKeyClassInstance)) {
            try {
                return (Long) cacheGenerateShardKeyMethod.invoke(cacheGenerateShardKeyClassInstance);
            } catch (IllegalAccessException | InvocationTargetException e) {
                log.error("使用自定义类生成任务分片键时，发生异常", e);
            }
        }
        // 获取用户自定义的任务分片键的class
        Class<?> shardingKeyGeneratorClass = getUserCustomShardingKeyGenerator();
        if (!ObjectUtils.isEmpty(shardingKeyGeneratorClass)) {
            String methodName = "generateShardKey";
            Method generateShardKeyMethod = ReflectUtil.getMethod(shardingKeyGeneratorClass, methodName);
            try {
                Constructor<?> constructor = ReflectUtil.getConstructor(shardingKeyGeneratorClass);
                cacheGenerateShardKeyClassInstance = constructor.newInstance();
                cacheGenerateShardKeyMethod = generateShardKeyMethod;
                return (Long) cacheGenerateShardKeyMethod.invoke(cacheGenerateShardKeyClassInstance);
            } catch (IllegalAccessException | InvocationTargetException | InstantiationException e) {
                log.error("使用自定义类生成任务分片键时，发生异常", e);
                // 如果指定的自定义分片键生成报错，使用框架自带的
                return SnowflakeShardingKeyGenerator.getInstance().generateShardKey();
            }
        }
        return SnowflakeShardingKeyGenerator.getInstance().generateShardKey();
    }

    /**
     * 获取ShardingKeyGenerator的实现类
     */
    private Class<?> getUserCustomShardingKeyGenerator() {
        return ReflectTools.getClassByName(tendConsistencyConfiguration.getShardingKeyGeneratorClassName());
    }

}
