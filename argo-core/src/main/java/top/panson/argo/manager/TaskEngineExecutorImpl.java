package top.panson.argo.manager;

import cn.hutool.core.util.ReflectUtil;
import cn.hutool.json.JSONUtil;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.CollectionUtils;
import org.springframework.util.ObjectUtils;
import org.springframework.util.StringUtils;
import top.panson.argo.config.TendConsistencyConfiguration;
import top.panson.argo.custom.alerter.ConsistencyFrameworkAlerter;
import top.panson.argo.exceptions.ConsistencyException;
import top.panson.argo.model.ConsistencyTaskInstance;
import top.panson.argo.service.TaskStoreService;
import top.panson.argo.utils.ExpressionUtils;
import top.panson.argo.utils.ReflectTools;
import top.panson.argo.utils.SpringUtil;
import top.panson.argo.utils.TimeUtils;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;


/**
 * 任务执行引擎实现类
 *
 **/
@Slf4j
@Component
public class TaskEngineExecutorImpl implements TaskEngineExecutor {

    /**
     * 一致性任务存储的service接口
     */
    @Autowired
    private TaskStoreService taskStoreService;
    /**
     * 任务引擎管理器
     */
    @Autowired
    private TaskScheduleManager taskScheduleManager;
    /**
     * 告警通知的线程池
     */
    @Autowired
    private ThreadPoolExecutor alertNoticePool;
    /**
     * 获取框架级配置
     */
    @Autowired
    private TendConsistencyConfiguration consistencyConfig;

    /**
     * 执行指定的任务实例  这里使用try catch 是因为需要将任务的错误信息也保存到任务表 正常情况下 不能进行try catch，不然事务是无法回滚的
     *
     * @param taskInstance 任务实例信息
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public void executeTaskInstance(ConsistencyTaskInstance taskInstance) {
        try {
            // 启动任务
            int result = taskStoreService.turnOnTask(taskInstance);
            if (result <= 0) {
                log.warn("[一致性任务框架] 任务已经为启动状态，退出执行流程 task:{}", JSONUtil.toJsonStr(taskInstance));
                return;
            }

            // 获取启动好的一致性任务实例
            // 重新查询一次，数据是最新的，比如刚才SQL里更新了execute times
            // Task任务实例对象里，并没有设置这个execute times = 0
            // 但是在这里你重新查询了一次，让数据库里的数据刷新到内存对象里来，execute times = 1，就出来了
            taskInstance = taskStoreService.getTaskByIdAndShardKey(taskInstance.getId(), taskInstance.getShardKey());

            // 执行任务
            taskScheduleManager.performanceTask(taskInstance);

            // 标记为执行成功,这里会移除该任务
            int successResult = taskStoreService.markSuccess(taskInstance);

            // 针对mark success数据库操作去执行一个try cache，对于任务执行成功，但是mark success失败
            // 完全基于磁盘文件可以去打一个执行成功的标记
            // 框架里自带，带一个后台线程，不断读取磁盘文件里的执行成功的标记，自动给他去在数据库里mark success去补充执行
            // delete操作，把他删除就可以了

            // 可以让框架基于redis开启一个可选的幂等保障的选项，可以给一个参数，配置一个redis地址，开启一个幂等性的机制
            // 在redis里可以写入一个指定任务id已经运行成功的幂等标记
            // 对于定时调度运行的程序，对每个数据库里查询出来的任务id，都去redis里检查一下，自动做一个幂等的检查
            // 确保已经成功的任务不要再重复运行了

            log.info("[一致性任务框架] 标记为执行成功的结果为 [{}]", successResult > 0);
        } catch (Exception e) {
            log.error("[一致性任务框架] {} 执行一致性任务时，发生异常, taskInstance的实例信息为 {}", JSONUtil.toJsonStr(taskInstance), e);
            taskInstance.setErrorMsg(getErrorMsg(e));
            // 对于最终一致性的框架来说，他执行action，如果失败了，是要无限次重试
            // 如果说出现了一个失败，下一次必然会是要进行重试的，下一次是什么时候可以进行重试呢？
            // 在这里就需要给他去计算一个下一次进行重试的execute time
            taskInstance.setExecuteTime(getNextExecuteTime(taskInstance)); // 这个下一次执行时间，非常关键的一个运算
            int failResult = taskStoreService.markFail(taskInstance);
            log.info("[一致性任务框架] 标记为执行失败的结果为 [{}] 下次调度时间为 [{} - {}]", failResult > 0, taskInstance.getExecuteTime(), getFormatTime(taskInstance.getExecuteTime()));
            // 执行降级逻辑
            fallbackExecuteTask(taskInstance);
        }
    }

    /**
     * 当执行任务失败的时候，执行该逻辑
     *
     * @param taskInstance 任务实例
     */
    @Override
    public void fallbackExecuteTask(ConsistencyTaskInstance taskInstance) {
        // 如果注解(任务实例信息)中没有提供降级类，则退出，不执行降级
        if (StringUtils.isEmpty(taskInstance.getFallbackClassName())) {
            // 解析并对表达式结果进行校验，并执行相关的告警通知逻辑
            // 如果没配置降级类且符合告警通知的表达式，则直接进行告警
            parseExpressionAndDoAlert(taskInstance);
            return;
        }
        // 获取全局配置 默认是开启降级策略的 如果失败会进行降级
        // fail count threshold，失败重试到多少次了，此时才会去执行你的降级逻辑
        if (taskInstance.getExecuteTimes() <= consistencyConfig.getFailCountThreshold()) {
            return;
        }
        log.info("[一致性任务框架] 执行任务id为{}的降级逻辑...", taskInstance.getId());
        Class<?> fallbackClass = ReflectTools.getClassByName(taskInstance.getFallbackClassName());
        if (ObjectUtils.isEmpty(fallbackClass)) {
            return;
        }

        // 获取参数值列表的json数组字符串
        String taskParameterText = taskInstance.getTaskParameter();
        // 参数类型字符串 多个用逗号进行了分隔
        String parameterTypes = taskInstance.getParameterTypes();
        // 构造参数类数组
        Class<?>[] paramTypes = getParamTypes(parameterTypes);
        // 参数具体的值
        Object[] paramValues = ReflectTools.buildArgs(taskParameterText, paramTypes);
        // 从spring容器中获取相关降级的bean
        Object fallbackClassBean = getBeanBySpringApplicationContext(fallbackClass, paramValues);
        // 获取降级方法
        Method fallbackMethod = ReflectUtil.getMethod(fallbackClass, taskInstance.getMethodName(), paramTypes);
        try {
            // 执行降级逻辑的方法
            fallbackMethod.invoke(fallbackClassBean, paramValues);
            // 标记为执行成功 这里会移除该任务
            int successResult = taskStoreService.markSuccess(taskInstance);

            // 可以实现一套跟我们之前讲的执行成功后的mark success故障的降级机制，一模一样机制
            // 写磁盘，要对这个任务进行mark success，后台线程不停的删除任务
            // 开启redis，只要降级成功了，mark success失败了，也必须要去redis里写标记

            log.info("[一致性任务框架] 降级逻辑执行成功 标记为执行成功的结果为 [{}]", successResult > 0);
        } catch (Exception e) {
            // 解析并对表达式结果进行校验，并执行相关的告警通知逻辑
            // 在执行完降级逻辑后，再去发送消息。因为如果降级成功了，也就不用发送告警通知了。如果降级失败，再去发送告警通知。
            parseExpressionAndDoAlert(taskInstance);
            taskInstance.setFallbackErrorMsg(getErrorMsg(e));
            int failResult = taskStoreService.markFallbackFail(taskInstance);
            log.error("[一致性任务框架] 降级逻辑也失败了 标记为执行失败的结果为 [{}] 下次调度时间为 [{} - {}]", failResult > 0,
                    taskInstance.getExecuteTime(), getFormatTime(taskInstance.getExecuteTime()), e);
        }
    }

    /**
     * 从spring容器中获取相关降级的bean
     *
     * @param fallbackClass 降级的Class类对象
     * @param paramValues   参数值
     * @return 相关降级的bean
     */
    private Object getBeanBySpringApplicationContext(Class<?> fallbackClass, Object[] paramValues) {
        return SpringUtil.getBean(fallbackClass, paramValues);
    }

    /**
     * 获取参数类型数组
     *
     * @param taskParameterText 参数类型json字符串 可转为JsonArray
     * @return 参数类型数组
     */
    private Class<?>[] getParamTypes(String taskParameterText) {
        return ReflectTools.buildTypeClassArray(taskParameterText.split(","));
    }


    /**
     * 解析并对表达式结果进行校验，并执行相关的告警通知逻辑
     *
     * @param taskInstance 任务实例信息
     */
    private void parseExpressionAndDoAlert(ConsistencyTaskInstance taskInstance) {
        try {
            if (StringUtils.isEmpty(taskInstance.getAlertExpression())) {
                return;
            }
            // 使用线程的原因是不对正常业务调用造成时间的占用 一般推送消息使用的是发送短信，钉钉、企业微信、邮件等等，
            // 操作会有一定的耗时（不过这个也要看具体的实现类是怎么实现的，如果实现类中使用的是异步推送告警，其实这里也就不用放到线程池中了）
            alertNoticePool.submit(() -> {
                // 对表达式进行重写
                String expr = ExpressionUtils.rewriteExpr(taskInstance.getAlertExpression());
                // 获取表达式解析后的结果
                String exprResult = ExpressionUtils.readExpr(expr, ExpressionUtils.buildDataMap(taskInstance));
                // 执行alert告警
                doAlert(exprResult, taskInstance);
            });
        } catch (Exception e) {
            log.error("发送告警通知时，发生异常", e);
        }
    }

    /**
     * 执行告警
     *
     * @param exprResult   表达式解析后的结果
     * @param taskInstance 任务实例信息
     */
    private void doAlert(String exprResult, ConsistencyTaskInstance taskInstance) {
        if (StringUtils.isEmpty(exprResult)) {
            return;
        }
        if (!ExpressionUtils.RESULT_FLAG.equals(exprResult)) {
            return;
        }
        //  执行相关的动作告警动作 发送钉钉消息/发送短信/访问一个URL接口等等方式 这里暂时先打印一条告警日志来代替 如果业务服务实现了框架提供的接口，则会进行调用相关的告警通知逻辑
        log.warn("[一致性任务框架] 告警通知 实例id为{}的任务{}触发告警规则，请进行排查。", taskInstance.getId(), JSONUtil.toJsonPrettyStr(taskInstance));
        if (StringUtils.isEmpty(taskInstance.getAlertActionBeanName())) {
            return;
        }
        // 发送告警通知
        sendAlertNotice(taskInstance);
    }

    /**
     * 发送告警通知
     *
     * @param taskInstance 告警通知
     */
    private void sendAlertNotice(ConsistencyTaskInstance taskInstance) {
        // 获取Spring容器中所有对于ConsistencyFrameworkAlerter接口的实现类
        Map<String, ConsistencyFrameworkAlerter> beansOfTypeMap = SpringUtil.getBeansOfType(ConsistencyFrameworkAlerter.class);

        if (CollectionUtils.isEmpty(beansOfTypeMap)) {
            log.warn("[一致性任务框架] 未获取到 ConsistencyFrameworkAlerter 相关的实现类，无法进行告警通知...");
            return;
        }

        try {
            // 获取ConsistencyFrameworkAlerter的实现类并发送告警通知
            getConsistencyFrameworkAlerterImpler(beansOfTypeMap, taskInstance)
                    .sendAlertNotice(taskInstance);
        } catch (Exception e) {
            log.error("[一致性任务框架] 调用业务服务实现具体的告警通知类时，发生异常", e);
            throw new ConsistencyException(e);
        }
    }

    /**
     * 获取ConsistencyFrameworkAlerter的实现类
     *
     * @param beansOfTypeMap ConsistencyFrameworkAlerter接口实现类的map集合
     * @param taskInstance   任务实例信息
     * @return 获取ConsistencyFrameworkAlerter的实现类
     */
    private ConsistencyFrameworkAlerter getConsistencyFrameworkAlerterImpler(Map<String, ConsistencyFrameworkAlerter> beansOfTypeMap, ConsistencyTaskInstance taskInstance) {
        // 如果只有一个实现类
        if (beansOfTypeMap.size() == 1) {
            String[] beanNamesForType = SpringUtil.getBeanNamesForType(ConsistencyFrameworkAlerter.class);
            return (ConsistencyFrameworkAlerter) SpringUtil.getBean(beanNamesForType[0]);
        }

        // 如果有多个实现类 获取注解中定义好的执行告警动作的alertActionBeanName获取对应的实现类
        return beansOfTypeMap.get(taskInstance.getAlertActionBeanName());
    }

    /**
     * 获取任务下一次的执行时间
     *
     * @param taskInstance 一致性任务实例
     * @return 下次执行时间
     */
    private long getNextExecuteTime(ConsistencyTaskInstance taskInstance) {
        // 上次执行时间 + （下一次执行的次数 * 执行间隔）
        // 对于一个任务重试执行的时间间隔，是有一个参数配置
        // 第一次执行失败之后，会用第一次执行之前的时间 + （1 + 1） * 20s = 第一次执行时间往后推40s
        return taskInstance.getExecuteTime() + ((taskInstance.getExecuteTimes() + 1) * TimeUtils.secToMill(taskInstance.getExecuteIntervalSec()));
    }

    private String getFormatTime(long timestamp) {
        // 设置格式
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return format.format(timestamp);
    }

    /**
     * 获取异常信息
     *
     * @param e 异常对象
     * @return 异常信息
     */
    private String getErrorMsg(Exception e) {
        if ("".equals(e.getMessage())) {
            return "";
        }
        String errorMsg = e.getMessage();
        if (StringUtils.isEmpty(errorMsg)) {
            if (e instanceof IllegalAccessException) {
                IllegalAccessException illegalAccessException = (IllegalAccessException) e;
                errorMsg = illegalAccessException.getMessage();
            } else if (e instanceof IllegalArgumentException) {
                IllegalArgumentException illegalArgumentException = (IllegalArgumentException) e;
                errorMsg = illegalArgumentException.getMessage();
            } else if (e instanceof InvocationTargetException) {
                InvocationTargetException invocationTargetException = (InvocationTargetException) e;
                errorMsg = invocationTargetException.getTargetException().getMessage();
            }
        }
        return errorMsg.substring(0, Math.min(errorMsg.length(), 200));
    }

}
