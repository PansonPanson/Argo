
Argo 是一款基于Java实现的最终一致性组件，主要用于处理分布式系统中的数据一致性问题。
通过 SpringAOP ，将任务保存到数据库中，然后从数据库中读取任务来执⾏。
核心目的：代码中保证“某个操作(Action)”最终⼀定可以执⾏成功。
执行流程图：
![img.png](doc/image/流程设计.png)


![img_2.png](https://github.com/PansonPanson/Argo/blob/main/doc/image/%E6%9C%80%E7%BB%88%E4%B8%80%E8%87%B4%E6%80%A7%E7%BB%84%E4%BB%B6.png?raw=true)