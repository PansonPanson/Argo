package top.panson.argoexample.order;

import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import top.panson.argo.annotation.ConsistencyTask;
import top.panson.argo.enums.PerformanceEnum;
import top.panson.argo.enums.ThreadWayEnum;
import top.panson.argoexample.fail.SendMessageFallbackHandler;

/**
 * 发送消息的组件
 **/
@Slf4j
@Component
public class SendMessageComponent {

    /**
     * 正常运行失败，降级运行成功
     * 异步调度任务测试
     * <p>
     * 验证情况：
     * 1、发送消息时，执行失败 有异常发生的情况，会标记任务状态为失败，同时记录失败的原因
     * 2、当满足 降级条件(executeTimes(执行次数) > TendConsistencyConfig.fallbackThreshold (默认值为0)) 可以触发降级逻辑 调用相关用户实现的自定义降级类的指定方法
     * 3、当满足 默认的 alertExpression(executeTimes > 1 && executeTimes < 5) 告警通知时，会触发消息的推送，并可以调用相关实现类
     *
     * @param orderInfo 订单
     */
    @ConsistencyTask(
            executeIntervalSec = 20,
            delayTime = 10, // 这个方法被调用开始，到后续被异步调度执行，至少距离调用时间已经过去了10s
            performanceWay = PerformanceEnum.PERFORMANCE_SCHEDULE,
            threadWay = ThreadWayEnum.ASYNC,
            fallbackClass = SendMessageFallbackHandler.class,
            alertActionBeanName = "normalAlerter"
    )
    // 这个任务如果这么配置的话，我们对他的运行效果期望是什么？
    // 调用的时候先进AOP切面，肯定会基于方法和注解，封装任务实例，数据会持久化落库
    // 是否直接来运行我们的这个目标方法，很明显，并不是
    // 通过调度模式来进行运行，把这个持久化的任务实例从数据库里拿出来
    // 还必须延迟10s再运行，运行的时候，是异步化来运行的`      `
    public void send(OrderInfoDTO orderInfo) { // SendMessageComponent.send(OrderInfoDTO)
        System.out.println(1 / 0); // 模拟失败
        log.info("[异步调度任务测试] 执行send(OrderInfoDTO)方法 {}", JSONUtil.toJsonStr(orderInfo));
    }

    /**
     * 正常运行失败，降级也失败，触发告警通知
     * <p>
     * 立即执行 异步任务测试 立即执行异步任务的情况下  executeIntervalSec 和 delayTime 属性无用
     * 开一个新的线程去执行任务
     * <p>
     * 验证情况：
     * 1、发送消息时，执行失败 有异常发生的情况，会标记任务状态为失败，同时记录失败的原因
     * 2、当满足 降级条件(executeTimes(执行次数) > TendConsistencyConfig.fallbackThreshold (默认值为0)) 可以触发降级逻辑 调用相关用户实现的自定义降级类的指定方法
     * 3、当满足 默认的 alertExpression(executeTimes > 1 && executeTimes < 5) 告警通知时，会触发消息的推送，并可以调用相关实现类
     *
     * @param orderInfo 订单
     */
    @ConsistencyTask(
            executeIntervalSec = 2,
            // delayTime = 5,
            performanceWay = PerformanceEnum.PERFORMANCE_RIGHT_NOW,
            threadWay = ThreadWayEnum.ASYNC,
            fallbackClass = SendMessageFallbackHandler.class,
            alertActionBeanName = "normalAlerter" // normalAlerter就是 com.ruyuan.eshop.alertm.NormalAlerter类在spring容器中的beanName
    )
    // 立即执行，但是必须要延迟5s钟后再立即执行 -> 单凭你的参数来看，是不对的，延迟5s，但是其实你的设置delayTime是无效的
    // 执行的时候，还必须是Async异步模式来进行执行
    // 重试执行的间隔是2s
    public void sendRightNowAsyncMessage(OrderInfoDTO orderInfo) {
        log.info("[异步调度任务测试] 执行sendRightNowAsyncMessage(OrderInfoDTO)方法 {}", JSONUtil.toJsonStr(orderInfo));
        System.out.println(1 / 0); // 模拟失败
    }

}
