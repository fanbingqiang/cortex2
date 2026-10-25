package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.cortex.lab.dto.KnowledgeNodeDTO;
import com.cortex.lab.dto.ProjectInfoDTO;
import com.cortex.lab.dto.ScenarioDto;
import com.cortex.lab.entity.LabScenario;
import com.cortex.lab.entity.AssistantConfig;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.mapper.AssistantConfigMapper;
import com.cortex.lab.mapper.LabScenarioMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import com.cortex.lab.service.PythonAIService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeTreeService {

    private final LabScenarioMapper scenarioMapper;
    private final PythonAIService pythonAIService;
    private final AssistantConfigMapper configMapper;
    private final SpringProjectService springProjectService;
    private final QuestionBankMapper questionBankMapper;
    private final KnowledgeCardService knowledgeCardService;

    private final ExecutorService cardGeneratorExecutor = Executors.newSingleThreadExecutor(r ->
        new Thread(r, "card-generator-" + r.hashCode()));

    /** 内存缓存：nodeId → 已生成内容（DB 为持久层） */
    private final ConcurrentHashMap<String, ScenarioDto> scenarioCache = new ConcurrentHashMap<>();

    // ======================== 知识树构建 ========================

    public List<KnowledgeNodeDTO> getTree() {
        List<KnowledgeNodeDTO> tree = new ArrayList<>();

        // ==================== 1. Redis篇 ====================
        tree.add(new KnowledgeNodeDTO("redis", "Redis篇", "Redis核心面试知识点", List.of(
            new KnowledgeNodeDTO("redis-cache", "Redis使用场景-缓存", "缓存相关问题及解决方案", List.of(
                conceptNode("redis-cache-penetration", "缓存穿透", "缓存穿透的原因及解决方案"),
                conceptNode("redis-cache-breakdown", "缓存击穿", "缓存击穿的原因及解决方案"),
                conceptNode("redis-cache-avalanche", "缓存雪崩", "缓存雪崩的原因及解决方案"),
                conceptNode("redis-cache-consistency", "双写一致性", "缓存与数据库双写一致性问题"),
                conceptNode("redis-consistency-strong", "强一致方案：Redisson读写锁", "基于Redisson的强一致性实现"),
                conceptNode("redis-consistency-eventual", "最终一致方案：MQ通知删缓存、Canal监听binlog更新缓存", "基于事件通知的最终一致性方案"),
                conceptNode("redis-consistency-delay", "延迟双删（缺点：无法保证强一致）", "延迟双删策略及其局限性")
            ), false, null),
            conceptNode("redis-persistence", "Redis持久化", "RDB快照与AOF日志持久化机制"),
            conceptNode("redis-expire", "Redis数据过期策略", "过期键的删除策略"),
            conceptNode("redis-eviction", "Redis数据淘汰策略", "内存淘汰算法"),
            new KnowledgeNodeDTO("redis-lock", "Redis分布式锁", "分布式锁的实现与应用", List.of(
                conceptNode("redis-lock-scenario", "使用场景", "分布式锁的典型应用场景"),
                conceptNode("redis-lock-principle", "实现原理（setnx、Redisson）", "基于setnx和Redisson的实现方式")
            ), false, null),
            new KnowledgeNodeDTO("redis-cluster", "Redis集群相关", "Redis集群架构与运维", List.of(
                conceptNode("redis-cluster-replication", "主从复制、主从同步", "主从复制原理及数据同步机制"),
                conceptNode("redis-cluster-sentinel", "哨兵模式、集群脑裂", "哨兵模式原理及脑裂问题处理"),
                conceptNode("redis-cluster-sharding", "分片集群、数据读写", "分片集群的数据分布与访问策略")
            ), false, null),
            new KnowledgeNodeDTO("redis-extra", "Redis补充拓展", "Redis进阶知识点", List.of(
                conceptNode("redis-extra-fast", "Redis是单线程为什么速度快", "单线程模型的优势及高效原因"),
                conceptNode("redis-extra-structure", "Redis常用数据结构", "String、Hash、List等数据结构特性")
            ), false, null),
            new KnowledgeNodeDTO("redis-other", "其他", "Redis其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 2. MySQL数据库篇 ====================
        tree.add(new KnowledgeNodeDTO("mysql", "MySQL数据库篇", "MySQL核心面试知识点", List.of(
            new KnowledgeNodeDTO("mysql-optimization", "SQL优化", "SQL性能优化全流程", List.of(
                conceptNode("mysql-slow-query", "定位慢查询", "慢查询日志开启与分析"),
                conceptNode("mysql-slow-analysis", "SQL执行缓慢分析方式", "Explain执行计划解读"),
                conceptNode("mysql-index-structure", "索引概念&底层数据结构", "B+树索引结构与原理"),
                conceptNode("mysql-index-clustered", "聚簇索引 & 非聚簇索引、回表查询", "聚簇与非聚簇索引的区别"),
                conceptNode("mysql-index-covering", "覆盖索引、超大分页优化", "覆盖索引避免回表与分页优化"),
                conceptNode("mysql-index-principle", "索引创建原则", "索引创建的最佳实践"),
                conceptNode("mysql-index-failure", "索引失效场景", "索引失效的常见原因"),
                conceptNode("mysql-sql-practice", "SQL优化实战经验", "SQL优化实践总结")
            ), false, null),
            new KnowledgeNodeDTO("mysql-transaction", "事务相关", "MySQL事务机制", List.of(
                conceptNode("mysql-acid", "事务四大特性ACID", "原子性、一致性、隔离性、持久性"),
                conceptNode("mysql-isolation", "并发事务问题、事务隔离级别", "脏读、不可重复读、幻读与隔离级别"),
                conceptNode("mysql-log", "redo log与undo log区别", "重做日志与回滚日志的作用"),
                conceptNode("mysql-mvcc", "MVCC原理", "多版本并发控制实现原理")
            ), false, null),
            new KnowledgeNodeDTO("mysql-architecture", "MySQL架构拓展", "MySQL高可用与扩展", List.of(
                conceptNode("mysql-replication", "主从同步原理", "binlog复制与主从同步机制"),
                conceptNode("mysql-sharding", "分库分表", "垂直拆分与水平拆分策略")
            ), false, null),
            new KnowledgeNodeDTO("mysql-other", "其他", "MySQL其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 3. Spring框架篇 ====================
        tree.add(new KnowledgeNodeDTO("spring", "Spring框架篇", "Spring框架核心面试知识点", List.of(
            new KnowledgeNodeDTO("spring-core", "Spring核心", "Spring IoC与AOP核心原理", List.of(
                conceptNode("spring-bean-thread-safe", "单例Bean是否线程安全", "单例Bean的线程安全性分析"),
                conceptNode("spring-aop", "AOP原理与面试题", "AOP底层实现与常见面试题"),
                conceptNode("spring-tx-failure", "Spring事务失效场景", "事务失效的常见原因与解决"),
                conceptNode("spring-bean-lifecycle", "Bean生命周期", "Bean从创建到销毁的完整流程"),
                conceptNode("spring-circular-dependency", "Bean循环依赖（循环引用）", "三级缓存解决循环依赖")
            ), false, null),
            new KnowledgeNodeDTO("spring-mvc", "SpringMVC", "SpringMVC请求处理流程", List.of(
                conceptNode("spring-mvc-flow", "SpringMVC执行流程", "DispatcherServlet请求处理流程")
            ), false, null),
            new KnowledgeNodeDTO("spring-boot", "SpringBoot", "SpringBoot自动配置原理", List.of(
                conceptNode("spring-boot-autoconfig", "自动配置原理", "@EnableAutoConfiguration实现原理")
            ), false, null),
            new KnowledgeNodeDTO("mybatis", "MyBatis", "MyBatis执行流程与缓存", List.of(
                conceptNode("mybatis-flow", "MyBatis执行流程", "MyBatis从代理到SQL执行的完整流程"),
                conceptNode("mybatis-lazy", "延迟加载原理与使用", "MyBatis懒加载实现机制"),
                conceptNode("mybatis-cache", "一级缓存、二级缓存", "SqlSession级别与Mapper级别缓存")
            ), false, null),
            new KnowledgeNodeDTO("spring-other", "其他", "Spring相关其他知识点", List.of(), false, null)
        ), false, null));

        // ==================== 4. 微服务篇（SpringCloud） ====================
        tree.add(new KnowledgeNodeDTO("microservice", "微服务篇（SpringCloud）", "微服务架构核心面试知识点", List.of(
            new KnowledgeNodeDTO("microservice-basic", "基础组件", "注册中心与负载均衡", List.of(
                conceptNode("microservice-registry", "注册中心", "Nacos/Eureka服务注册与发现"),
                conceptNode("microservice-ribbon", "Ribbon负载均衡", "客户端负载均衡策略")
            ), false, null),
            new KnowledgeNodeDTO("microservice-ha", "高可用防护", "服务雪崩与熔断降级", List.of(
                conceptNode("microservice-circuit-breaker", "服务雪崩、熔断、降级", "Sentinel熔断降级机制")
            ), false, null),
            new KnowledgeNodeDTO("microservice-solution", "业务解决方案", "分布式事务与限流方案", List.of(
                conceptNode("microservice-rate-limit", "微服务限流（漏桶、令牌桶算法）", "漏桶与令牌桶限流算法"),
                conceptNode("microservice-cap", "CAP理论与BASE理论", "分布式系统CAP取舍与BASE"),
                conceptNode("microservice-distributed-tx", "分布式事务解决方案", "Seata、TCC、可靠消息最终一致性"),
                conceptNode("microservice-idempotent", "分布式接口幂等性实现", "Token机制、去重表等幂等方案"),
                conceptNode("microservice-distributed-lock", "分布式锁（跨服务场景）", "Redisson分布式锁跨服务使用")
            ), false, null),
            new KnowledgeNodeDTO("microservice-other", "其他", "微服务其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 5. 消息中间件篇 ====================
        tree.add(new KnowledgeNodeDTO("mq", "消息中间件篇", "消息中间件核心面试知识点", List.of(
            new KnowledgeNodeDTO("rabbitmq", "RabbitMQ", "RabbitMQ核心机制", List.of(
                conceptNode("rabbitmq-reliability", "消息可靠性保障", "生产者确认、消费者确认、持久化机制"),
                conceptNode("rabbitmq-dlq", "死信队列", "死信交换机与延迟队列实现"),
                conceptNode("rabbitmq-backlog", "海量消息堆积处理", "消息堆积原因分析与解决方案"),
                conceptNode("rabbitmq-ha", "RabbitMQ高可用方案", "镜像队列与集群模式")
            ), false, null),
            new KnowledgeNodeDTO("kafka", "Kafka", "Kafka核心机制", List.of(
                conceptNode("kafka-no-loss", "消息不丢失保障方案", "生产者ack、副本机制、消费者offset提交"),
                conceptNode("kafka-ordering", "消费顺序性保证", "分区内有序与全局有序方案"),
                conceptNode("kafka-ha", "Kafka高可用机制", "ISR、Leader选举、副本同步"),
                conceptNode("kafka-cleanup", "磁盘数据清理机制", "日志段删除与压缩策略"),
                conceptNode("kafka-performance", "高性能设计原理", "顺序写入、零拷贝、页缓存")
            ), false, null),
            new KnowledgeNodeDTO("mq-other", "其他", "消息中间件其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 6. Java集合篇 ====================
        tree.add(new KnowledgeNodeDTO("java-collection", "Java集合篇", "Java集合框架核心面试知识点", List.of(
            new KnowledgeNodeDTO("collection-list", "List", "List接口实现类", List.of(
                conceptNode("collection-arraylist", "ArrayList底层原理、源码分析", "数组扩容机制与源码分析"),
                conceptNode("collection-arraylist-vs-linkedlist", "ArrayList与LinkedList区别", "数组与链表结构对比"),
                conceptNode("collection-linkedlist", "LinkedList底层结构", "双向链表实现原理")
            ), false, null),
            new KnowledgeNodeDTO("collection-datastructure", "数据结构基础", "基础数据结构", List.of(
                conceptNode("collection-tree-hash", "二叉树、红黑树、散列表", "TreeMap与HashMap底层数据结构")
            ), false, null),
            new KnowledgeNodeDTO("collection-map", "Map", "Map接口实现类", List.of(
                conceptNode("collection-hashmap", "HashMap原理", "哈希表结构与扩容机制"),
                conceptNode("collection-hashmap-put", "put执行流程", "HashMap插入元素的完整流程"),
                conceptNode("collection-hashmap-addressing", "寻址算法、数组容量设计", "hash扰动与2的幂次容量设计"),
                conceptNode("collection-hashmap-jdk7", "JDK1.7 HashMap多线程死循环问题", "头插法导致的环形链表问题"),
                conceptNode("collection-concurrenthashmap", "ConcurrentHashMap并发原理", "CAS+synchronized分段锁机制")
            ), false, null),
            new KnowledgeNodeDTO("collection-other", "其他", "Java集合其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 7. 并发编程篇 ====================
        tree.add(new KnowledgeNodeDTO("concurrency", "并发编程篇", "Java并发编程核心面试知识点", List.of(
            new KnowledgeNodeDTO("concurrency-thread-basic", "线程基础", "线程基础概念", List.of(
                conceptNode("concurrency-process-vs-thread", "进程与线程区别", "进程与线程的定义与区别"),
                conceptNode("concurrency-concurrent-vs-parallel", "并发与并行区别", "并发与并行的概念区别"),
                conceptNode("concurrency-thread-create", "线程创建方式", "Thread、Runnable、Callable创建线程"),
                conceptNode("concurrency-thread-state", "线程状态", "6种线程状态及状态转换"),
                conceptNode("concurrency-thread-join-wait", "join顺序执行、wait与sleep区别", "join实现顺序执行与wait/sleep对比"),
                conceptNode("concurrency-thread-stop", "如何安全停止线程", "interrupt标志位与安全停止")
            ), false, null),
            new KnowledgeNodeDTO("concurrency-thread-safe", "线程安全", "线程安全机制", List.of(
                conceptNode("concurrency-synchronized", "synchronized关键字原理", "对象头MarkWord与锁升级"),
                conceptNode("concurrency-jmm", "JMM Java内存模型", "主内存与工作内存、happens-before"),
                conceptNode("concurrency-cas", "CAS原理", "Compare-And-Swap与ABA问题"),
                conceptNode("concurrency-volatile", "volatile关键字作用", "可见性与有序性保证"),
                conceptNode("concurrency-aqs", "AQS原理", "AbstractQueuedSynchronizer实现原理"),
                conceptNode("concurrency-reentrantlock", "ReentrantLock实现原理", "AQS独占锁实现"),
                conceptNode("concurrency-sync-vs-lock", "synchronized与Lock对比", "两者功能与性能对比"),
                conceptNode("concurrency-deadlock", "死锁产生条件与解决方案", "死锁四条件与排查解决"),
                conceptNode("concurrency-chm", "ConcurrentHashMap", "并发集合的线程安全实现")
            ), false, null),
            new KnowledgeNodeDTO("concurrency-threadpool", "线程池", "线程池核心知识", List.of(
                conceptNode("concurrency-threadpool-params", "核心参数", "corePoolSize、maxPoolSize、keepAliveTime等"),
                conceptNode("concurrency-threadpool-queue", "常见阻塞队列", "ArrayBlockingQueue、LinkedBlockingQueue等"),
                conceptNode("concurrency-threadpool-formula", "核心线程数计算公式", "CPU密集型与IO密集型线程数计算"),
                conceptNode("concurrency-threadpool-types", "线程池种类", "Fixed、Cached、Scheduled、Single等"),
                conceptNode("concurrency-threadpool-executors", "禁止直接使用Executors创建线程池", "OOM风险与手动创建规范"),
                conceptNode("concurrency-threadpool-practice", "项目中线程池实战场景", "实际项目中的线程池使用经验")
            ), false, null),
            new KnowledgeNodeDTO("concurrency-extra", "拓展", "并发编程拓展", List.of(
                conceptNode("concurrency-threadlocal", "ThreadLocal原理、内存泄漏问题", "ThreadLocalMap结构与内存泄漏分析")
            ), false, null),
            new KnowledgeNodeDTO("concurrency-other", "其他", "并发编程其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 8. JVM虚拟机篇 ====================
        tree.add(new KnowledgeNodeDTO("jvm", "JVM虚拟机篇", "JVM核心面试知识点", List.of(
            new KnowledgeNodeDTO("jvm-basic", "JVM基础", "JVM基础概念", List.of(
                conceptNode("jvm-intro", "JVM介绍、整体运行流程", "JVM整体架构与运行流程"),
                conceptNode("jvm-memory", "JVM内存区域划分", "堆、栈、方法区、程序计数器等"),
                conceptNode("jvm-pc", "程序计数器", "PC寄存器的作用与特点"),
                conceptNode("jvm-stack", "虚拟机栈", "栈帧结构与局部变量表"),
                conceptNode("jvm-method-area", "方法区", "方法区与元空间的变化"),
                conceptNode("jvm-direct-memory", "直接内存", "NIO直接内存与堆外内存")
            ), false, null),
            new KnowledgeNodeDTO("jvm-classloader", "类加载器", "类加载机制", List.of(
                conceptNode("jvm-classloader-types", "类加载器分类", "Bootstrap、Extension、Application类加载器"),
                conceptNode("jvm-classloader-parent", "类加载执行流程、双亲委派模型", "双亲委派模型与打破机制")
            ), false, null),
            new KnowledgeNodeDTO("jvm-gc", "垃圾回收GC", "GC机制与算法", List.of(
                conceptNode("jvm-gc-reachable", "对象判定回收条件", "可达性分析与GC Roots"),
                conceptNode("jvm-gc-algorithm", "常见GC算法", "标记-清除、标记-复制、标记-整理"),
                conceptNode("jvm-gc-generational", "分代回收机制", "新生代、老年代、永久代/元空间"),
                conceptNode("jvm-gc-collectors", "垃圾收集器（G1、ZGC等）", "Serial、Parallel、CMS、G1、ZGC"),
                conceptNode("jvm-gc-references", "四种引用：强、软、弱、虚引用", "强软弱虚引用与ReferenceQueue")
            ), false, null),
            new KnowledgeNodeDTO("jvm-tuning", "JVM调优实战", "JVM调优工具与方法", List.of(
                conceptNode("jvm-tuning-params", "常用调优参数", "-Xms、-Xmx、-XX:+UseG1GC等"),
                conceptNode("jvm-tuning-tools", "调优工具", "jstack、jmap、jstat、Arthas等"),
                conceptNode("jvm-tuning-memory-leak", "内存泄漏排查思路", "dump分析、MAT工具使用"),
                conceptNode("jvm-tuning-cpu", "CPU飙升线上排查方案", "top、jstack定位CPU飙高线程")
            ), false, null),
            new KnowledgeNodeDTO("jvm-other", "其他", "JVM其他相关知识点", List.of(), false, null)
        ), false, null));

        // ==================== 9. 企业场景&设计模式篇 ====================
        tree.add(new KnowledgeNodeDTO("enterprise", "企业场景&设计模式篇", "企业级开发实战", List.of(
            new KnowledgeNodeDTO("enterprise-design-pattern", "设计模式", "常用设计模式", List.of(
                conceptNode("enterprise-factory", "工厂模式（简单工厂、工厂方法、抽象工厂）", "三种工厂模式的区别与应用"),
                conceptNode("enterprise-strategy", "策略模式", "策略模式消除if-else"),
                conceptNode("enterprise-chain", "责任链模式", "责任链模式实现请求处理链")
            ), false, null),
            new KnowledgeNodeDTO("enterprise-scenario", "业务场景实战", "企业业务场景方案", List.of(
                conceptNode("enterprise-sso", "单点登录实现方案", "SSO单点登录的技术实现"),
                conceptNode("enterprise-auth", "登录认证、权限框架设计", "认证授权与RBAC权限模型"),
                conceptNode("enterprise-rate-limit", "接口防重、限流落地", "接口幂等与限流方案落地")
            ), false, null),
            new KnowledgeNodeDTO("enterprise-other", "其他", "企业场景其他相关知识点", List.of(), false, null)
        ), false, null));

        setPaths(tree, "");
        return tree;
    }

    /** 递归设置所有节点的路径 */
    private void setPaths(List<KnowledgeNodeDTO> nodes, String parentPath) {
        for (KnowledgeNodeDTO node : nodes) {
            String nodePath = parentPath.isEmpty() ? node.getName() : parentPath + " / " + node.getName();
            node.setPath(nodePath);
            if (node.getChildren() != null && !node.getChildren().isEmpty()) {
                setPaths(node.getChildren(), nodePath);
            }
        }
    }

    // ======================== 掌握状态 ========================

    public void toggleMastered(String nodeId, String userId, boolean mastered) {
        try {
            String key = "tree_mastered_" + userId + "_" + nodeId;
            List<AssistantConfig> existing = configMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssistantConfig>()
                    .eq(AssistantConfig::getConfigKey, key)
                    .last("LIMIT 1")
            );
            if (!existing.isEmpty()) {
                AssistantConfig c = existing.get(0);
                c.setConfigValue(String.valueOf(mastered));
                c.setGmtModified(LocalDateTime.now());
                configMapper.updateById(c);
            } else {
                AssistantConfig c = new AssistantConfig();
                c.setConfigKey(key);
                c.setConfigValue(String.valueOf(mastered));
                c.setConfigType("boolean");
                c.setGmtCreate(LocalDateTime.now());
                c.setGmtModified(LocalDateTime.now());
                configMapper.insert(c);
            }
        } catch (Exception e) {
            log.error("保存节点掌握状态失败: nodeId={}, userId={}", nodeId, userId, e);
        }
    }

    /** 获取知识树所有节点ID（扁平化） */
    public List<String> getAllNodeIds() {
        List<String> ids = new ArrayList<>();
        collectAllNodeIds(getTree(), ids);
        return ids;
    }

    private void collectAllNodeIds(List<KnowledgeNodeDTO> nodes, List<String> ids) {
        if (nodes == null) return;
        for (KnowledgeNodeDTO node : nodes) {
            ids.add(node.getId());
            if (node.getChildren() != null) {
                collectAllNodeIds(node.getChildren(), ids);
            }
        }
    }

    public List<String> getMasteredNodeIds(String userId) {
        try {
            String prefix = "tree_mastered_" + userId + "_";
            List<AssistantConfig> all = configMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<AssistantConfig>()
                    .likeRight(AssistantConfig::getConfigKey, prefix)
            );
            return all.stream()
                .filter(c -> "true".equals(c.getConfigValue()))
                .map(c -> c.getConfigKey().substring(prefix.length()))
                .collect(java.util.stream.Collectors.toList());
        } catch (Exception e) {
            log.warn("获取节点掌握状态失败: {}", e.getMessage());
            return List.of();
        }
    }

    // ======================== 节点工厂 ========================

    private static KnowledgeNodeDTO node(String id, String name, String description) {
        return new KnowledgeNodeDTO(id, name, description, null, true, null);
    }

    private static KnowledgeNodeDTO projectNode(String id, String name, String description) {
        return new KnowledgeNodeDTO(id, name, description, null, true, "project");
    }

    private static KnowledgeNodeDTO conceptNode(String id, String name, String description) {
        return new KnowledgeNodeDTO(id, name, description, null, true, "concept");
    }

    private static KnowledgeNodeDTO commandNode(String id, String name, String description) {
        return new KnowledgeNodeDTO(id, name, description, null, true, "command");
    }

    private static KnowledgeNodeDTO algoNode(String id, String name, String description) {
        return new KnowledgeNodeDTO(id, name, description, null, true, "algorithm");
    }

    // ======================== 场景映射: 节点ID → 自然语言问题 ========================

    private static final Map<String, String> SCENARIO_MAP = buildScenarioMap();

    private static Map<String, String> buildScenarioMap() {
        Map<String, String> map = new HashMap<>();
        // --- Java基础 ---
        map.put("java-basics-datatype", "为什么 Integer 用 == 比较 100 是 true，200 却是 false？");
        map.put("java-basics-string", "为什么 new String(\"hello\") 用 == 比较结果是 false？");
        map.put("java-basics-passing", "为什么方法里改了 List，外面的 List 也变了？");
        map.put("java-basics-equals", "HashMap 用 Person 做 key，为什么 get 不到值？");
        map.put("java-basics-generics", "ArrayList<String> 转 ArrayList<Object> 为什么编译报错？");
        map.put("java-basics-reflection", "为什么反射可以访问 private 字段修改 final 变量？");
        map.put("java-basics-annotation", "自定义注解为什么有时候获取不到值？");
        map.put("java-basics-lambda", "Lambda 表达式里用外部变量为什么要 effectively final？");
        map.put("java-basics-stream", "Stream 的 filter 里打印日志为什么没执行？");
        map.put("java-basics-optional", "Optional.of(null) 为什么报 NPE？");
        map.put("java-basics-serial", "为什么反序列化会破坏单例？");
        map.put("java-basics-io", "BufferedReader 为什么需要配合 InputStreamReader 使用？");
        map.put("java-basics-enum", "枚举是否可以延迟加载？为什么枚举单例是安全的？");
        map.put("java-basics-bigdecimal", "BigDecimal 用 double 构造为什么精度不对？");
        map.put("java-basics-datetime", "SimpleDateFormat 为什么线程不安全？");
        map.put("java-basics-copy", "ArrayList 的 clone 是深拷贝还是浅拷贝？");
        map.put("java-basics-static", "静态方法能不能被子类重写？");
        map.put("java-basics-final", "final 修饰的数组内容能修改吗？");
        map.put("java-basics-inner-class", "匿名内部类访问局部变量为什么要 final？");
        map.put("java-basics-interface", "接口的 default 方法和抽象类有什么区别？");
        // --- 面向对象 ---
        map.put("oop-encapsulation", "Java 的访问修饰符 public/protected/default/private 各有什么用？");
        map.put("oop-extends", "为什么 Java 不支持多继承？");
        map.put("oop-polymorphism", "重载和重写有什么区别？哪个是编译时多态？");
        map.put("oop-abstraction", "面向接口编程到底有什么好处？");
        map.put("oop-composition", "为什么说组合优先于继承？你见过哪些继承滥用导致的问题？");
        map.put("oop-singleton", "DCL 单例为什么需要 volatile？");
        map.put("oop-proxy", "JDK 动态代理为什么必须传接口？CGLIB 呢？");
        map.put("oop-solid-single", "一个类既处理订单又发送邮件，违反了哪个原则？");
        map.put("oop-solid-open", "开闭原则怎么理解？如何在不修改代码的情况下扩展功能？");
        map.put("oop-solid-liskov", "子类重写父类方法抛出更多异常违反了哪个原则？");
        map.put("oop-solid-interface-seg", "一个接口有 10 个方法，但实现类只用其中 5 个，哪里不对？");
        map.put("oop-solid-di", "依赖反转和依赖注入是一回事吗？");
        // --- 异常处理 ---
        map.put("exception-try-finally", "为什么 try 里 return 了，finally 还会执行？");
        map.put("exception-try-with-resources", "try-with-resources 关闭资源的顺序是反的还是正的？");
        map.put("exception-hierarchy", "异常捕获：多个 catch 的顺序有讲究吗？");
        map.put("exception-checked", "受检异常和非受检异常怎么选？Spring 为什么偏爱运行时异常？");
        map.put("exception-suppressed", "try-with-resources 如果 close 也抛异常了会怎样？");
        map.put("exception-custom", "自定义异常应该继承 Exception 还是 RuntimeException？");
        map.put("exception-performance", "异常填充栈信息是不是很慢？能禁用吗？");
        map.put("exception-best-practice", "catch 到异常后打了一条日志就吞掉了，这有什么问题？");
        // --- 集合框架 ---
        map.put("collection-arraylist", "ArrayList 的 subList 返回的是新列表还是视图？");
        map.put("collection-hashmap", "HashMap 1.7 和 1.8 有什么区别？为什么 1.8 要引入红黑树？");
        map.put("collection-concurrent-hashmap", "ConcurrentHashMap 的 size() 方法是怎么实现的？");
        map.put("collection-treemap", "TreeMap 里放自定义对象为什么报 ClassCastException？");
        map.put("collection-queue", "BlockingQueue 的 offer、add、put 有什么区别？");
        map.put("collection-hashset", "HashSet 是怎么去重的？重复的元素会被覆盖吗？");
        map.put("collection-treeset", "TreeSet 判断重复的依据是 equals 还是 Comparable？");
        map.put("collection-failfast", "在遍历集合时删除元素为什么会抛 ConcurrentModificationException？");
        map.put("collection-collections", "Collections.unmodifiableList 返回的列表真的不可修改吗？");
        map.put("collection-arrays", "Arrays.asList(1, 2, 3).add(4) 为什么报错？");
        map.put("collection-sublist", "ArrayList.subList 的修改会影响原列表吗？");
        map.put("collection-aslist", "Arrays.asList(new int[]{1,2,3}).size() 结果是多少？");
        map.put("collection-iterable", "for-each 循环里能不能删除元素？");
        map.put("collection-enum-map", "EnumMap 相比 HashMap 有什么性能优势？");
        // --- 并发编程 ---
        map.put("concurrency-thread", "线程的 start() 和 run() 有什么区别？");
        map.put("concurrency-synchronized", "synchronized 加到静态方法和实例方法锁的是什么？");
        map.put("concurrency-volatile", "volatile 能保证原子性吗？为什么 i++ 不是安全的？");
        map.put("concurrency-reentrantlock", "ReentrantLock 和 synchronized 怎么选？");
        map.put("concurrency-readwritelock", "读多写少的场景用 ReadWriteLock 一定能提升性能吗？");
        map.put("concurrency-stampedlock", "StampedLock 的乐观读和悲观读分别怎么用？");
        map.put("concurrency-threadpool", "线程池的核心线程数怎么设置？为什么 IO 密集型和 CPU 密集型不同？");
        map.put("concurrency-deadlock", "死锁怎么排查？jstack 看到死锁是什么样子的？");
        map.put("concurrency-atomic", "AtomicInteger 的 incrementAndGet 是原子的吗？底层原理？");
        map.put("concurrency-completable-future", "CompletableFuture 的 thenApply、thenCompose、thenCombine 有什么区别？");
        map.put("concurrency-threadlocal", "ThreadLocal 为什么会内存泄漏？remove 了还会吗？");
        map.put("concurrency-forkjoin", "ForkJoinPool 的工作窃取是怎么实现的？");
        map.put("concurrency-locksupport", "LockSupport.park 和 Object.wait 有什么区别？");
        map.put("concurrency-semaphore", "CountDownLatch、CyclicBarrier、Semaphore 各自的应用场景？");
        map.put("concurrency-blockingqueue", "SynchronousQueue 是没有容量的队列？那 put 和 take 怎么工作？");
        map.put("concurrency-phaser", "Phaser 相比 CyclicBarrier 多了什么能力？");
        map.put("concurrency-false-sharing", "为什么多线程修改相邻变量性能会急剧下降？");
        map.put("concurrency-aqs", "AQS 的共享模式和独占模式分别怎么用？");
        map.put("concurrency-cpu-cache", "内存屏障是什么？volatile 底层怎么用内存屏障？");
        // --- JVM ---
        map.put("jvm-memory", "JDK8 的方法区去哪了？元空间和永久代有什么区别？");
        map.put("jvm-gc", "哪些对象可以作为 GC Root？");
        map.put("jvm-collector", "CMS 垃圾收集器为什么会有并发失败？");
        map.put("jvm-oom", "java.lang.OutOfMemoryError: Java heap space 和 Direct buffer memory 有什么区别？");
        map.put("jvm-classloader", "双亲委派模型怎么打破？Tomcat 和 JDBC 分别是怎么做的？");
        map.put("jvm-tuning", "JVM 调优时一般看哪些指标？怎么判断 GC 频率是否正常？");
        map.put("jvm-reference", "软引用在内存不足时会被回收，那引用队列是干什么的？");
        map.put("jvm-string-intern", "String.intern() 在 JDK6 和 JDK7+ 的行为有什么不同？");
        map.put("jvm-escape", "什么是逃逸分析？栈上分配真的会发生吗？");
        map.put("jvm-off-heap", "堆外内存怎么释放？DirectByteBuffer 的回收机制是什么？");
        map.put("jvm-jit", "什么是 JIT 编译？什么情况下会触发分层编译？");
        map.put("jvm-tool", "CPU 飚到 100% 了，怎么用 jstack 找到问题线程？");
        map.put("jvm-bytecode", "Java 的 invokedynamic 指令是干什么用的？");
        map.put("jvm-compressed-oops", "为什么 JVM 默认开启压缩指针？超过多少 GB 会自动关闭？");
        map.put("jvm-g1-region", "G1 的 SATB 和 CMS 的增量更新有什么区别？");
        // --- Spring 陷阱 ---
        map.put("spring-trap-transaction", "@Transactional 失效：私有方法调用");
        map.put("spring-trap-circular", "循环依赖：构造器注入报错");
        map.put("spring-trap-aop", "AOP 失效：方法自调用");
        map.put("spring-trap-scope", "Singleton Bean 注入 Prototype Bean 失效");
        // --- Spring 理论节点 ---
        map.put("spring-transaction", "同一个类里 A 方法调 B 方法，B 的 @Transactional 会生效吗？");
        map.put("spring-boot", "Spring Boot 的 @EnableAutoConfiguration 是怎么扫描到第三方 Starter 的？");
        map.put("spring-event", "Spring 的 @EventListener 是同步还是异步的？怎么改成异步？");
        map.put("spring-async", "@Async 注解为什么有时候不生效？");
        map.put("spring-interceptor", "Filter 和 Interceptor 的执行顺序是什么？谁先谁后？");
        // --- 数据库 ---
        map.put("database-mysql-arch", "MySQL 执行一条 SELECT 语句的完整流程是什么？");
        map.put("database-sql-order", "SQL 中 WHERE 和 HAVING 执行顺序哪个先？");
        map.put("database-index", "为什么 MySQL 用 B+ 树而不是 B 树或红黑树？");
        map.put("database-transaction", "MySQL RR 级别下能避免幻读吗？");
        map.put("database-lock", "MySQL 的间隙锁在什么情况下会生效？");
        map.put("database-sql-optimize", "一个 SQL 查询很慢，怎么用 EXPLAIN 分析？");
        map.put("database-count", "count(*) 和 count(1) 哪个更快？");
        map.put("database-join-optimize", "为什么大表 JOIN 查询很慢？怎么优化？");
        map.put("database-deep-paging", "LIMIT 1000000, 10 为什么越来越慢？怎么优化？");
        map.put("database-normal-form", "数据库设计时一定要满足第三范式吗？");
        map.put("database-charset", "MySQL 存 emoji 为什么变成了问号？");
        map.put("database-on-duplicate", "INSERT ... ON DUPLICATE KEY UPDATE 会有死锁问题吗？");
        map.put("database-lock-pess-opti", "乐观锁在并发高的时候真的比悲观锁好吗？");
        map.put("database-sharding", "分表后跨多个分片的查询怎么处理？");
        map.put("database-read-write", "主从复制的延迟怎么解决？");
        map.put("database-migration", "大表 DDL 怎么做不影响线上？");
        // --- MyBatis ---
        map.put("mybatis-dynamic-sql", "MyBatis 的 foreach 标签批量插入性能怎么样？");
        map.put("mybatis-sql-injection", "MyBatis 里 #{} 和 ${} 有什么区别？哪个能防注入？");
        map.put("mybatis-cache", "MyBatis 二级缓存为什么会有脏数据？");
        map.put("mybatis-plugin", "PageHelper 分页插件是怎么拦截 SQL 的？");
        map.put("mybatis-result", "MyBatis 的 ResultMap 和 ResultType 怎么选？");
        map.put("mybatis-nested-query", "OneToMany 关联查询为什么会产生 N+1 问题？");
        map.put("mybatis-generator", "MyBatis Generator 生成的 Example 类有什么用？");
        // --- 设计模式 ---
        map.put("pattern-factory", "简单工厂和策略模式看起来很像，区别是什么？");
        map.put("pattern-builder", "Builder 模式一定比构造器更好吗？");
        map.put("pattern-strategy", "策略模式和 if-else 比好在哪里？");
        map.put("pattern-observer", "Guava EventBus 和 Spring Event 的区别？");
        map.put("pattern-decorator", "装饰器模式和代理模式看起来好像，怎么区分？");
        map.put("pattern-chain", "Netty 的 Pipeline 是责任链模式吗？链怎么断开的？");
        map.put("pattern-template", "模板方法模式和策略模式有什么本质区别？");
        map.put("pattern-adapter", "适配器模式和门面模式有什么不同？");
        map.put("pattern-flyweight", "Integer 缓存和 String 常量池是享元模式的应用吗？");
        map.put("pattern-facade", "门面模式会不会变成上帝类？");
        map.put("pattern-mediator", "中介者模式和观察者模式怎么选？");
        map.put("pattern-memento", "备忘录模式在 undo/redo 中怎么应用？");
        // --- 网络与IO ---
        map.put("io-bio-nio-aio", "BIO、NIO、AIO 各自的适用场景是什么？");
        map.put("io-netty", "Netty 的 Reactor 模型有几种线程模型？");
        map.put("io-zerocopy", "Kafka 为什么用零拷贝？");
        map.put("net-tcp-handshake", "TCP 三次握手，最后一次握手的 ACK 丢了会怎样？");
        map.put("net-timewait", "服务器上大量 TIME_WAIT 是什么原因？怎么解决？");
        map.put("net-keepalive", "HTTP/1.1 的 Keep-Alive 和 HTTP/2 的多路复用有什么区别？");
        map.put("net-timeout", "connect timeout 和 read timeout 分别在哪里配置？");
        map.put("net-websocket", "WebSocket 和 HTTP 是什么关系？");
        map.put("net-tls", "HTTPS 的 TLS 握手过程中，客户端怎么验证服务器证书的？");
        map.put("net-dns", "DNS 解析过程是怎样的？浏览器 DNS 缓存多久？");
        // --- 分布式 ---
        map.put("distributed-cap", "注册中心选型，为什么有人用 Eureka（AP）有人用 ZooKeeper（CP）？");
        map.put("distributed-redis", "Redis 的分布式锁用 setnx 还是 Redisson？主从切换时锁会丢吗？");
        map.put("distributed-mq", "RocketMQ 的事务消息是怎么实现最终一致性的？");
        map.put("distributed-transaction", "Seata AT 模式和 TCC 模式各自适合什么场景？");
        map.put("distributed-discovery", "Nacos 是 AP 还是 CP？能同时支持吗？");
        map.put("distributed-config", "配置中心的热更新是怎么通知到应用的？");
        map.put("distributed-gateway", "网关层做限流和业务层做限流有什么区别？");
        map.put("distributed-sentinel", "Sentinel 的滑动窗口限流是怎么统计 QPS 的？");
        map.put("distributed-tracing", "SkyWalking 怎么做到无侵入的链路追踪？");
        map.put("distributed-es", "ES 的倒排索引是怎么加速搜索的？");
        map.put("distributed-id", "雪花算法的时钟回拨问题怎么解决？");
        map.put("distributed-raft", "Raft 的 Leader 选举节点数量必须是奇数吗？");
        map.put("distributed-load-balance", "一致性哈希在扩容时能保证多少数据不迁移？");
        map.put("distributed-session", "JWT 无状态 token 注销了怎么办？");
        map.put("distributed-interface-idempotent", "接口幂等性怎么设计？MQ 重复消费怎么处理？");
        // --- Linux ---
        map.put("linux-command", "lsof -i:8080 看到的是什么信息？");
        map.put("linux-process", "服务器 OOM 了，怎么找到被 Kill 的进程？");
        map.put("linux-network", "两个服务连不上，怎么排查是防火墙、端口还是网络的问题？");
        map.put("linux-shell", "Shell 脚本中 $? 表示什么？怎么判断上一条命令是否成功？");
        map.put("linux-crontab", "crontab 的任务没执行，怎么排查？");
        map.put("linux-ulimit", "too many open files 怎么解决？");
        map.put("linux-disk", "磁盘空间满了但找不到大文件，可能是哪里占了？");
        map.put("linux-java", "Java 进程 CPU 100% 怎么定位到代码行？");
        map.put("linux-performance", "火焰图怎么看？怎么生成 Java 火焰图？");
        map.put("linux-security", "SSH 暴力破解怎么防护？");
        // --- Docker & K8s ---
        map.put("dockerfile", "Docker 镜像层缓存什么时候会失效？");
        map.put("docker-compose", "docker-compose 的 depends_on 真的能确保服务就绪吗？");
        map.put("docker-network", "bridge 和 host 网络模式怎么选？");
        map.put("docker-volume", "Docker 容器的数据存在宿主机哪里？容器删了数据还在吗？");
        map.put("k8s-pod", "Pod 中的容器都挂了，Pod 会怎么样？");
        map.put("k8s-deployment", "Deployment 滚动更新策略 maxSurge 和 maxUnavailable 怎么配？");
        map.put("k8s-service", "ClusterIP 和 NodePort 的访问路径是什么？");
        map.put("k8s-configmap", "ConfigMap 更新后 Pod 里的配置会热更新吗？");
        map.put("k8s-helm", "Helm 的 values.yaml 覆盖层级是怎么工作的？");
        map.put("k8s-monitor", "K8s 集群 Pod 内存持续上涨，怎么定位？");
        // --- Git & 工程化 ---
        map.put("git-branch", "Git Flow 和 Trunk Based 各自适合什么团队？");
        map.put("git-rebase", "rebase 和 merge 哪个更好？在公共分支上 rebase 有什么风险？");
        map.put("git-advanced", "git reset、revert、rebase 有什么区别？");
        map.put("maven-lifecycle", "mvn clean install 执行了哪些 phase？");
        map.put("maven-multi-module", "多模块 Maven 项目怎么解决循环依赖？");
        map.put("maven-settings", "Maven 依赖冲突时怎么排除？");
        map.put("ci-cd", "CI/CD 流水线中怎么做自动化测试和部署？");
        map.put("code-quality", "SonarQube 检测出的严重问题有哪些？");
        // --- 测试 ---
        map.put("test-junit5", "JUnit 5 的 @ParameterizedTest 怎么用？");
        map.put("test-mockito", "Mockito 的 when 和 verify 有什么区别？");
        map.put("test-spring", "@SpringBootTest 和 @WebMvcTest 有什么区别？哪个更快？");
        map.put("test-containers", "Testcontainers 启动的 MySQL 容器怎么让其他测试共用？");
        map.put("test-performance", "性能测试的 TP95、TP99 是什么意思？");
        map.put("test-tdd", "TDD 的红-绿-重构三个步骤分别做什么？");
        // --- 系统设计 ---
        map.put("design-arch-evolution", "从单体到微服务，第一步应该拆什么？");
        map.put("design-flash-sale", "秒杀系统怎么防止超卖？怎么防止黄牛？");
        map.put("design-short-url", "短链服务的跳转用 301 还是 302？为什么？");
        map.put("design-idempotent", "支付接口的幂等性怎么设计？重试会重复扣款吗？");
        map.put("design-rate-limit", "令牌桶和漏桶算法有什么区别？Guava RateLimiter 是哪种？");
        map.put("design-sharding", "用户表分片用 user_id hash 还是按时间？");
        map.put("design-id-gen", "分布式 ID 一定要全局递增吗？Snowflake 有什么缺点？");
        map.put("design-cap-practice", "你做的系统选的是 CP 还是 AP？怎么保证最终一致性？");
        map.put("design-high-concurrency", "高并发场景下数据库扛不住怎么办？");
        map.put("design-failover", "多活架构中写冲突怎么处理？");
        // --- 算法 ---
        map.put("algo-sort", "手写快排，时间复杂度是多少？什么情况下会退化？");
        map.put("algo-linked-list", "判断链表有没有环？快慢指针为什么一定相遇？");
        map.put("algo-lru", "LRU 缓存怎么实现？为什么用双向链表？");
        map.put("algo-binary-tree", "二叉树的层序遍历怎么写？");
        map.put("algo-topk", "海量数据中找 TopK，内存不够怎么办？");
        map.put("algo-bfs-dfs", "二维矩阵的岛屿数量怎么数？");
        map.put("algo-dp", "0-1 背包的状态转移方程是什么？");
        map.put("algo-two-pointer", "有序数组的两数之和，怎么用 O(n) 时间解决？");
        return map;
    }

    // ======================== 项目节点判断与生成 ========================

    public boolean isProjectNode(String nodeId) {
        KnowledgeNodeDTO found = findNode(getTree(), nodeId);
        return found != null && "project".equals(found.getType());
    }

    public ProjectInfoDTO generateProject(String nodeId) {
        if (!isProjectNode(nodeId)) {
            throw new RuntimeException("不是项目类型节点: " + nodeId);
        }
        return springProjectService.generateProject(nodeId);
    }

    // ======================== 多类型内容生成 ========================

    public ScenarioDto generateForNode(String nodeId) {
        return generateForNode(nodeId, null);
    }

    public ScenarioDto generateForNode(String nodeId, String userId) {
        List<KnowledgeNodeDTO> tree = getTree();
        KnowledgeNodeDTO found = findNode(tree, nodeId);
        if (found == null) {
            throw new RuntimeException("未找到知识点: " + nodeId);
        }

        String cacheKey = buildCacheKey(nodeId, userId, found.getType());
        ScenarioDto cached = getCachedScenario(cacheKey);
        if (cached != null) {
            log.debug("命中缓存 key={}", cacheKey);
            return cached;
        }

        String knowledgePoint = found.getName();
        String description = found.getDescription();
        String type = found.getType();

        // 非陷阱类型 → 使用对应的生成器（不持久化）
        if ("concept".equals(type)) {
            return generateNonTrapContent("concept", buildConceptPrompt(knowledgePoint, description), nodeId, userId);
        }
        if ("command".equals(type)) {
            return generateNonTrapContent("command", buildCommandPrompt(knowledgePoint, description), nodeId, userId);
        }
        if ("algorithm".equals(type)) {
            return generateNonTrapContent("algorithm", buildAlgorithmPrompt(knowledgePoint, description), nodeId, userId);
        }

        // 陷阱代码类型（type == null）→ 原有流程
        // Try direct mapping to existing scenario
        String mappedKnowledgePoint = SCENARIO_MAP.get(nodeId);
        if (mappedKnowledgePoint != null) {
            LabScenario mapped = scenarioMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LabScenario>()
                    .eq(LabScenario::getKnowledgePoint, mappedKnowledgePoint)
            );
            if (mapped != null) {
                if (isFallbackScenario(mapped)) {
                    scenarioMapper.deleteById(mapped.getId());
                    log.info("删除旧 fallback 记录 [{}]（SCENARIO_MAP），将重新生成", mapped.getId());
                } else {
                    log.info("知识点 [{}] -> 映射到已有场景 [{}]", nodeId, mappedKnowledgePoint);
                    return attachNodeIdAndCache(nodeId, mapped);
                }
            }
        }

        // Try exact name match
        LabScenario existing = scenarioMapper.selectOne(
            new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LabScenario>()
                .eq(LabScenario::getKnowledgePoint, knowledgePoint)
                .last("LIMIT 1")
        );
        if (existing != null) {
            if (isFallbackScenario(existing)) {
                scenarioMapper.deleteById(existing.getId());
                log.info("删除旧 fallback 记录 [{}]（精确匹配），将重新生成", existing.getId());
            } else {
                log.info("知识点 [{}] 已有 DB 缓存，直接返回", knowledgePoint);
                return attachNodeIdAndCache(nodeId, existing);
            }
        }

        // Try to generate via LLM (trap code)
        try {
            String result = pythonAIService.chatSimple(
                    buildPrompt(knowledgePoint, description));
            String cleaned = com.cortex.util.JsonUtils.cleanJson(result);
            ScenarioDto dto = JSON.parseObject(cleaned, ScenarioDto.class);

            LabScenario entity = new LabScenario();
            entity.setNodeId(nodeId);
            entity.setKnowledgePoint(knowledgePoint);
            entity.setCategory(getCategoryForNode(nodeId));
            entity.setTrapCode(dto.getTrapCode());
            entity.setExpectedPitfall(dto.getExpectedPitfall());
            entity.setCorrectExplanation(dto.getCorrectExplanation());
            entity.setHints(JSON.toJSONString(dto.getHints()));
            entity.setDifficulty(dto.getDifficulty() != null ? dto.getDifficulty() : 2);
            entity.setGmtCreate(LocalDateTime.now());
            scenarioMapper.insert(entity);
            dto.setId(entity.getId());
            cacheScenario(nodeId, entity);

            syncToQuestionBankAndGenerateCard(entity);

            return dto;
        } catch (Exception e) {
            log.warn("LLM 生成陷阱代码失败，使用概念讲解代替: {}", e.getMessage());
            // 不持久化/缓存 fallback，确保配置 API Key 后能重新生成
            String fallback = buildFallbackContent("concept", knowledgePoint, description);
            return new ScenarioDto(null, knowledgePoint, getCategoryForNode(nodeId),
                fallback, description, knowledgePoint + "\n\n" + description,
                null, 1, null, fallback);
        }
    }

    // 非陷阱类型通用生成器：查缓存 → LLM 生成 → 持久化 → 返回
    private ScenarioDto generateNonTrapContent(String contentType, String prompt, String nodeId, String userId) {
        String cacheKey = buildCacheKey(nodeId, userId, contentType);
        ScenarioDto cached = getCachedScenario(cacheKey);
        if (cached != null) {
            return cached;
        }
        // 复用 3 参数版本的生成逻辑
        return generateNonTrapContent(contentType, prompt, nodeId);
    }

    private ScenarioDto generateNonTrapContent(String contentType, String prompt, String nodeId) {
        ScenarioDto cached = getCachedScenario(nodeId);
        if (cached != null) {
            return cached;
        }

        KnowledgeNodeDTO node = findNode(getTree(), nodeId);
        String knowledgePoint = node.getName();
        String description = node.getDescription();

        // 查缓存：按 type + knowledgePoint 查找
        try {
            LabScenario dbRow = scenarioMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LabScenario>()
                    .eq(LabScenario::getType, contentType)
                    .eq(LabScenario::getKnowledgePoint, knowledgePoint)
                    .last("LIMIT 1")
            );
            if (dbRow != null) {
                if (isFallbackScenario(dbRow)) {
                    scenarioMapper.deleteById(dbRow.getId());
                    log.info("删除旧 fallback 记录 [{}]（类型缓存），将重新生成", dbRow.getId());
                } else {
                    return attachNodeIdAndCache(nodeId, dbRow);
                }
            }
        } catch (Exception e) {
            log.warn("查询缓存失败: {}", e.getMessage());
        }

        // 尝试 LLM 生成
        try {
            String result = pythonAIService.chatSimple(prompt);

            LabScenario entity = new LabScenario();
            entity.setNodeId(nodeId);
            entity.setKnowledgePoint(knowledgePoint);
            entity.setCategory(getCategoryForNode(nodeId));
            entity.setType(contentType);
            entity.setGeneratedContent(result);
            entity.setGmtCreate(LocalDateTime.now());
            scenarioMapper.insert(entity);

            ScenarioDto dto = new ScenarioDto();
            dto.setId(entity.getId());
            dto.setType(contentType);
            dto.setGeneratedContent(result);
            dto.setTrapCode(result);
            dto.setKnowledgePoint(knowledgePoint);
            cacheScenario(nodeId, entity);
            return dto;
        } catch (Exception e) {
            log.warn("{} 内容生成失败，使用备用内容: {}", contentType, e.getMessage());
            // 不持久化/缓存，确保配置 API Key 后能重新生成
            String fallback = buildFallbackContent(contentType, knowledgePoint, description);
            return new ScenarioDto(null, knowledgePoint, getCategoryForNode(nodeId),
                fallback, null, null, null, null, contentType, fallback);
        }
    }

    // LLM 不可用时的备用内容
    private String buildFallbackContent(String contentType, String name, String desc) {
        return switch (contentType) {
            case "concept" -> buildConceptFallback(name, desc);
            case "command" -> buildCommandFallback(name, desc);
            case "algorithm" -> buildAlgorithmFallback(name, desc);
            default -> desc != null ? desc : name;
        };
    }

    private String buildConceptFallback(String name, String desc) {
        return """
## 概念讲解

**%s**

%s

## 理解要点

1. 先搞清楚这概念是干什么的
2. 它解决了什么问题
3. 使用时要注意什么

## 动手试试

打开编辑器，自己写一段代码来验证你的理解。
如果有 API Key，可以生成 AI 示例代码做参考。
""".formatted(name, desc != null ? desc : "这是 Java 开发中的一个重要概念。理解它有助于写出更健壮的代码。");
    }

    private String buildCommandFallback(String name, String desc) {
        return """
## 命令说明

**%s**

%s

## 学习方式

这部分内容偏向实操，建议：

1. 打开终端亲手执行
2. 注意观察输出结果
3. 尝试修改参数看效果有什么不同

配置 API Key 后可查看 AI 生成的命令演示。
""".formatted(name, desc != null ? desc : "这是开发中常用的命令操作。");
    }

    private String buildAlgorithmFallback(String name, String desc) {
        return """
## 算法思路

**%s**

%s

## 分析步骤

1. 理解输入是什么，输出是什么
2. 想一想暴力解法怎么写
3. 有没有更优的方式

## LeetCode 建议

在 LeetCode 上搜相关题目练习。
先自己写，再看题解。
""".formatted(name, desc != null ? desc : "这是一个经典算法问题。先理解思路再动手写代码。");
    }

    private String buildPrompt(String knowledgePoint, String description) {
        return """
    你是一个 Java 编程教学专家。针对以下知识点，生成一段带有陷阱的 Java 代码。

    知识点: %s
    描述: %s

    请返回 JSON 格式（不要 markdown 标记）：
    {
      "trapCode": "完整的可编译运行的 Java 代码（必须包含 public class 和 main 方法），代码表面正常但暗藏陷阱，通过 print 输出让学习者发现异常",
      "expectedPitfall": "预期的意外现象（一句话描述学习者会看到什么）",
      "correctExplanation": "正确原理解释（200字以内，讲清楚为什么）",
      "hints": ["提示1（引导观察）", "提示2（引导思考）", "提示3（暗示方向）"],
      "difficulty": 2
    }

    要求：
    1. 代码必须完整、可编译、可运行，包含 main 方法
    2. 陷阱效果通过 System.out.println 输出体现
    3. 代码看起来正常，初学者看不出问题
    4. 提示要循序渐进，不直接给出答案
    """.formatted(knowledgePoint, description);
    }

    // 概念讲解 prompt
    private String buildConceptPrompt(String knowledgePoint, String description) {
        return """
    你是一个 Java 技术教学专家。请针对以下知识点，生成一篇简短的概念讲解。

    知识点: %s
    描述: %s

    请按以下结构生成纯文本（不要 JSON，不要 markdown 代码块标记）：
    ## 概念
    [用 100 字左右解释这个知识点]

    ## 为什么重要
    [50 字说明这个知识点在实际工作/面试中的意义]

    ## 代码示例
    [一段简洁的 Java 代码演示这个知识点，加上注释说明关键点]

    ## 常见误区
    [2-3 个常见错误理解或使用方式]

    ## 相关命令/配置（如果有）
    [如果是工具类知识点，列出相关命令或配置]
    """.formatted(knowledgePoint, description);
    }

    // 命令行演示 prompt
    private String buildCommandPrompt(String knowledgePoint, String description) {
        return """
    你是一个 DevOps/工具链教学专家。请针对以下知识点，生成一段命令行演示。

    知识点: %s
    描述: %s

    请按以下结构生成纯文本（不要 JSON，不要 markdown 代码块标记）：
    ## 概述
    [50 字说明这个知识点]

    ## 常用命令/操作
    [列出 3-5 个最常用的命令或操作，每个带简要说明和示例]

    ## 实战场景
    [1 个真实工作场景，展示如何组合使用这些命令解决问题]

    ## 常见问题
    [2-3 个常见错误和排查方法]
    """.formatted(knowledgePoint, description);
    }

    // 算法 prompt
    private String buildAlgorithmPrompt(String knowledgePoint, String description) {
        return """
    你是一个算法教学专家。请针对以下知识点，生成一段算法教学。

    知识点: %s
    描述: %s

    请按以下结构生成纯文本（不要 JSON，不要 markdown 代码块标记）：
    ## 问题描述
    [一句话说明要解决什么问题]

    ## 思路分析
    [100 字以内讲清楚算法思路]

    ## 复杂度分析
    [时间复杂度、空间复杂度]

    ## Java 代码实现
    [完整的 Java 代码，包含 main 方法测试，有注释]

    ## 边界情况
    [需要注意的边界条件和处理方式]

    ## 变体问题
    [1 个相关变体问题]
    """.formatted(knowledgePoint, description);
    }

    private KnowledgeNodeDTO findNode(List<KnowledgeNodeDTO> nodes, String id) {
        for (KnowledgeNodeDTO node : nodes) {
            if (id.equals(node.getId())) {
                return node;
            }
            if (node.getChildren() != null) {
                KnowledgeNodeDTO found = findNode(node.getChildren(), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String getCategoryForNode(String nodeId) {
        for (var cat : getTree()) {
            if (cat.getChildren() != null) {
                for (var child : cat.getChildren()) {
                    if (child.getId().equals(nodeId)) return cat.getName();
                }
            }
        }
        return "Java";
    }

    private ScenarioDto getCachedScenario(String cacheKey) {
        ScenarioDto mem = scenarioCache.get(cacheKey);
        if (mem != null) {
            if (isFallbackScenario(mem)) {
                log.debug("内存缓存为 fallback，跳过并重新生成 key={}", cacheKey);
                scenarioCache.remove(cacheKey);
            } else {
                return mem;
            }
        }
        // 陷阱类内容查 DB（用户相关的不查 DB，不存在真正的缓存）
        if (cacheKey.startsWith("trap:")) {
            String nodeId = cacheKey.substring(5);
            LabScenario db = scenarioMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<LabScenario>()
                    .eq(LabScenario::getNodeId, nodeId)
                    .last("LIMIT 1")
            );
            if (db != null) {
                if (isFallbackScenario(db)) {
                    log.info("清除节点 [{}] 的旧 fallback 缓存，将重新生成", nodeId);
                    scenarioMapper.deleteById(db.getId());
                    return null;
                }
                ScenarioDto dto = toDto(db);
                scenarioCache.put(cacheKey, dto);
                return dto;
            }
        }
        return null;
    }

    /** 缓存 key：陷阱类内容用 trap:nodeId，用户相关用 user:userId:nodeId */
    private String buildCacheKey(String nodeId, String userId, String type) {
        if ("concept".equals(type) || "command".equals(type) || "algorithm".equals(type)) {
            return "user:" + (userId != null ? userId : "anonymous") + ":" + nodeId;
        }
        return "trap:" + nodeId;
    }

    /** 判断是否为无 API Key 时的 fallback 内容 */
    private boolean isFallbackScenario(LabScenario e) {
        return e.getDifficulty() != null && e.getDifficulty() == 1
            && e.getTrapCode() != null && e.getTrapCode().contains("请配置");
    }

    private boolean isFallbackScenario(ScenarioDto dto) {
        return dto.getDifficulty() != null && dto.getDifficulty() == 1
            && dto.getTrapCode() != null && dto.getTrapCode().contains("请配置");
    }

    private ScenarioDto attachNodeIdAndCache(String nodeId, LabScenario entity) {
        if (entity.getNodeId() == null || entity.getNodeId().isBlank()) {
            entity.setNodeId(nodeId);
            scenarioMapper.updateById(entity);
        }
        ScenarioDto dto = toDto(entity);
        scenarioCache.put("trap:" + nodeId, dto);
        return dto;
    }

    private void cacheScenario(String nodeId, LabScenario entity) {
        scenarioCache.put("trap:" + nodeId, toDto(entity));
    }

    private void cacheScenario(String cacheKey, ScenarioDto dto) {
        scenarioCache.put(cacheKey, dto);
    }

    private void syncToQuestionBankAndGenerateCard(LabScenario scenario) {
        try {
            long exists = questionBankMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<QuestionBank>()
                    .eq(QuestionBank::getTitle, scenario.getKnowledgePoint())
            );
            if (exists > 0) {
                return;
            }
            QuestionBank qb = new QuestionBank();
            qb.setTitle(scenario.getKnowledgePoint());
            qb.setDescription(scenario.getExpectedPitfall());
            qb.setTrapCode(scenario.getTrapCode());
            qb.setExpectedPitfall(scenario.getExpectedPitfall());
            qb.setCorrectExplanation(scenario.getCorrectExplanation());
            qb.setHints(scenario.getHints());
            qb.setCategory(scenario.getCategory() != null ? scenario.getCategory() : "Java基础");
            qb.setDifficulty(scenario.getDifficulty() != null ? scenario.getDifficulty() : 2);
            qb.setStatus("ACTIVE");
            qb.setGmtCreate(LocalDateTime.now());
            qb.setGmtModified(LocalDateTime.now());
            questionBankMapper.insert(qb);

            Long questionId = qb.getId();
            log.info("已同步知识点 [{}] 到题库, questionId={}", scenario.getKnowledgePoint(), questionId);

            cardGeneratorExecutor.execute(() -> {
                try {
                    knowledgeCardService.generateCard(questionId);
                    log.info("知识卡片生成完成, questionId={}", questionId);
                } catch (Exception e) {
                    log.warn("知识卡片生成失败, questionId={}: {}", questionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("同步到题库失败: {}", e.getMessage());
        }
    }

    private ScenarioDto toDto(LabScenario entity) {
        ScenarioDto dto = new ScenarioDto();
        dto.setId(entity.getId());
        dto.setKnowledgePoint(entity.getKnowledgePoint());
        dto.setCategory(entity.getCategory());
        dto.setTrapCode(entity.getTrapCode());
        dto.setExpectedPitfall(entity.getExpectedPitfall());
        dto.setCorrectExplanation(entity.getCorrectExplanation());
        dto.setHints(JSON.parseArray(entity.getHints(), String.class));
        dto.setDifficulty(entity.getDifficulty());
        dto.setType(entity.getType());
        dto.setGeneratedContent(entity.getGeneratedContent());
        return dto;
    }
}