package top.panson.argo.custom.query;

import top.panson.argo.utils.DateUtils;

import java.util.Calendar;
import java.util.Date;


/**
 * 任务执行时间范围查询器接口
 * 如业务服务需要定制，实现该接口即可
 *
 **/
public interface TaskTimeRangeQuery {

    /**
     * 获取查询任务的初始时间
     *
     * @return 启始时间
     */
    Date getStartTime();

    /**
     * 获取查询任务的结束时间
     *
     * @return 结束时间
     */
    Date getEndTime();

    /**
     * 每次最多查询出多少个未完成的任务出来
     *
     * @return 未完成的任务数量
     */
    Long limitTaskCount();

    /**
     * 如果没有实现类，框架默认实现：获取查询任务的初始时间
     *
     * @return 启始时间
     */
    static Date getStartTimeByStatic() {
        return DateUtils.getDateByDayNum(new Date(), Calendar.HOUR, -1);
    }

    /**
     * 如果没有实现类，框架默认实现：获取查询任务的结束时间
     *
     * @return 结束时间
     */
    static Date getEndTimeByStatic() {
        return new Date();
    }

    /**
     * 如果没有实现类，框架默认实现：每次最多查询出多少个未完成的任务出来
     *
     * @return 未完成的任务数量
     */
    static Long limitTaskCountByStatic() {
        return 1000L;
    }

}
