package top.panson.argo.example.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import top.panson.argo.example.order.OrderInfoDTO;
import top.panson.argo.example.order.SendMessageComponent;

import javax.annotation.Resource;


@RestController
public class TestController {

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(TestController.class);

    /**
     * 发送消息的组件
     */
    @Resource
    private SendMessageComponent sendMessageComponent;

    /**
     * 正常运行失败，降级运行成功
     * 异步调度任务测试
     * <p>
     * 验证情况：
     * 1、发送消息时，执行失败 有异常发生的情况，会标记任务状态为失败，同时记录失败的原因
     * 2、当满足 降级条件(executeTimes(执行次数) > TendConsistencyConfig.fallbackThreshold (默认值为0)) 可以触发降级逻辑 调用相关用户实现的自定义降级类的指定方法
     * 3、当满足 默认的 alertExpression(executeTimes % 2 == 0) 告警通知时，会触发消息的推送，并可以调用相关实现类
     */
    @GetMapping("/test")
    public String send() {
        // 模拟插库操作
        OrderInfoDTO orderInfoDTO = OrderInfoDTO.builder()
                .id("111")
                .build();
        // 发消息操作
        sendMessageComponent.send(orderInfoDTO);
        return "ok";
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
     * 3、当满足 默认的 alertExpression(executeTimes % 2 == 0) 告警通知时，会触发消息的推送，并可以调用相关实现类
     */
    @GetMapping("/test1")
    public String sendRightNowAsyncMessage() {
        // 模拟插库操作
        OrderInfoDTO orderInfoDTO = OrderInfoDTO.builder()
                .id("222")
                .build();
        // 发送立即执行异步任务
        sendMessageComponent.sendRightNowAsyncMessage(orderInfoDTO);
        return "ok";
    }

}
