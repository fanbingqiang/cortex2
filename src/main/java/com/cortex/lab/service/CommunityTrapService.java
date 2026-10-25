package com.cortex.lab.service;

import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.dto.CommunityTrapDto;
import com.cortex.lab.dto.CommunityTrapSubmitRequest;
import com.cortex.lab.entity.CommunityTrap;
import com.cortex.lab.entity.LabScenario;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.mapper.CommunityTrapMapper;
import com.cortex.lab.mapper.LabScenarioMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CommunityTrapService {
    // 社区陷阱代码服务：提交、审核、导入题库

    private final CommunityTrapMapper communityTrapMapper;
    private final QuestionBankMapper questionBankMapper;
    private final LabScenarioMapper labScenarioMapper;

    // @PostConstruct  // 已禁用自动种子，数据由用户手动管理
    public void seedData() {
        try {
            long count = communityTrapMapper.selectCount(new LambdaQueryWrapper<>());
            if (count == 0) {
                log.info("Seeding community trap data...");
                seedTrap("@Transactional 失效：私有方法调用", "Spring事务管理", "Spring项目", "17",
                    "import org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.stereotype.Service;\nimport org.springframework.transaction.annotation.Transactional;\n\n@Service\npublic class UserService {\n    @Autowired\n    private UserRepository userRepository;\n\n    public void createUser(String name) {\n        createUserWithTx(name);\n    }\n\n    @Transactional\n    private void createUserWithTx(String name) {\n        userRepository.save(new User(name));\n        throw new RuntimeException(\"模拟异常\");\n    }\n}",
                    "事务没有回滚，用户信息被保存到了数据库",
                    "@Transactional 在私有方法上无效。Spring AOP 代理机制只拦截公有方法，私有方法不被代理。且方法内部自调用（this.method()）不走代理，需注入自身代理或使用 AopContext.currentProxy()。",
                    "[\"@Transactional 方法的可见性是？\", \"方法调用方是什么对象？是代理对象还是 this？\", \"Spring AOP 代理能拦截什么类型的方法？\"]",
                    3, "system", "APPROVED", 12);
                seedTrap("循环依赖：构造器注入报错", "Spring循环依赖", "Spring项目", "17",
                    "import org.springframework.stereotype.Service;\nimport org.springframework.beans.factory.annotation.Autowired;\n\n@Service\npublic class ServiceA {\n    private final ServiceB serviceB;\n    public ServiceA(ServiceB serviceB) { this.serviceB = serviceB; }\n}\n\n@Service\npublic class ServiceB {\n    private final ServiceA serviceA;\n    public ServiceA(ServiceA serviceA) { this.serviceA = serviceA; }\n}",
                    "应用启动报错：Requested beans are currently in creation: Is there an unresolvable circular reference?",
                    "Spring 只能解决 Setter 注入的循环依赖（三级缓存），构造器注入的循环依赖无法解决。解决方案：改用 @Autowired Setter 注入、@Lazy 延迟代理或在设计层面拆解循环。",
                    "[\"Spring 三级缓存解决的是什么类型的循环依赖？\", \"构造器注入和 Setter 注入的时机有何不同？\", \"三级缓存中的第几级能处理构造器循环依赖？\"]",
                    3, "system", "APPROVED", 15);
                seedTrap("ConcurrentModificationException 遍历时修改 List", "集合框架陷阱", "Java核心", "8",
                    "import java.util.ArrayList;\nimport java.util.List;\n\npublic class ConcurrentModTrap {\n    public static void main(String[] args) {\n        List<String> list = new ArrayList<>();\n        list.add(\"A\"); list.add(\"B\"); list.add(\"C\"); list.add(\"D\");\n        for (String s : list) {\n            if (\"B\".equals(s)) { list.remove(s); }\n        }\n        System.out.println(\"剩余: \" + list);\n    }\n}",
                    "运行报错：ConcurrentModificationException",
                    "增强 for 循环使用了 Iterator 遍历，list.remove() 会修改 modCount 导致迭代器检测到并发修改抛出异常。应使用 iterator.remove() 或 Collectors.filter 收集新列表。",
                    "[\"增强 for 循环底层用的什么？\", \"modCount 的作用是什么？\", \"正确的遍历删除方式是什么？\"]",
                    2, "system", "APPROVED", 20);
                seedTrap("finally 中 return 覆盖 try 的 return", "异常处理", "Java核心", "8",
                    "public class FinallyReturnTrap {\n    public static void main(String[] args) {\n        System.out.println(test());\n    }\n    static String test() {\n        try { return \"try\"; }\n        finally { return \"finally\"; }\n    }\n}",
                    "输出的是 \"finally\" 而不是 \"try\"",
                    "finally 块中的 return 会覆盖 try 中的 return。更危险的是，如果 finally 中有 return，try 中抛出的异常也会被吞掉。",
                    "[\"finally 和 try 谁先执行？\", \"finally 中 return 会有什么副作用？\", \"如果 try 抛出异常而 finally 有 return，异常会怎样？\"]",
                    2, "system", "APPROVED", 18);
                seedTrap("AOP 失效：方法自调用", "Spring AOP", "Spring项目", "11",
                    "import org.springframework.stereotype.Service;\nimport org.springframework.transaction.annotation.Transactional;\n\n@Service\npublic class LogService {\n    public void process() { logExecution(); }\n    @Transactional\n    public void logExecution() {\n        System.out.println(\"记录日志...\");\n    }\n}",
                    "@Transactional 没有生效，事务没有开启",
                    "Spring AOP 通过代理对象工作。当 process() 直接调用 this.logExecution() 时，调用的是原始对象而非代理对象，AOP 增强逻辑不会执行。",
                    "[\"logExecution() 是被谁调用的？\", \"AOP 代理对象和目标对象的区别？\", \"如何让自调用也能触发 AOP？\"]",
                    3, "system", "APPROVED", 10);
                log.info("Seeded {} community traps", 5);
            }
        } catch (Exception e) {
            log.warn("Seed community trap data failed: {}", e.getMessage());
        }
    }

    // Separate @PostConstruct for lab_scenario seeding (independent check so it still runs
    // even if community_trap table already has data)
    // @PostConstruct  // 已禁用自动种子，数据由用户手动管理
    public void seedScenarios() {
        try {
            long scenarioCount = labScenarioMapper.selectCount(
                new LambdaQueryWrapper<LabScenario>().eq(LabScenario::getKnowledgePoint, "@Transactional 失效：私有方法调用")
            );
            if (scenarioCount > 0) return;
            log.info("Seeding Spring trap scenarios for knowledge tree...");
            seedScenario("@Transactional 失效：私有方法调用", "Spring框架",
                "import org.springframework.stereotype.Service;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.transaction.annotation.Transactional;\n\n@Service\npublic class UserService {\n    @Autowired\n    private UserRepository userRepository;\n\n    public void createUser(String name) {\n        createUserWithTx(name);\n    }\n\n    @Transactional\n    private void createUserWithTx(String name) {\n        userRepository.save(new User(name));\n        throw new RuntimeException(\"模拟异常\");\n    }\n}",
                "事务没有回滚，用户信息被保存到了数据库",
                "@Transactional 在私有方法上无效。Spring AOP 代理机制只拦截公有方法。",
                "[\"@Transactional 方法的可见性是？\", \"自调用为什么会让 AOP 失效？\"]",
                3);
            seedScenario("循环依赖：构造器注入报错", "Spring框架",
                "import org.springframework.stereotype.Service;\n\n@Service\npublic class ServiceA {\n    private final ServiceB serviceB;\n    public ServiceA(ServiceB serviceB) { this.serviceB = serviceB; }\n}\n\n@Service\npublic class ServiceB {\n    private final ServiceA serviceA;\n    public ServiceA(ServiceA serviceA) { this.serviceA = serviceA; }\n}",
                "应用启动报错：Unresolvable circular reference",
                "Spring 只能解决 Setter 注入的循环依赖（三级缓存），构造器注入的不行。",
                "[\"Spring 三级缓存解决什么类型的循环依赖？\", \"构造器注入和 Setter 注入时机有何不同？\"]",
                3);
            seedScenario("AOP 失效：方法自调用", "Spring框架",
                "import org.springframework.stereotype.Service;\nimport org.springframework.transaction.annotation.Transactional;\n\n@Service\npublic class LogService {\n    public void process() { logExecution(); }\n    @Transactional\n    public void logExecution() {\n        System.out.println(\"记录日志...\");\n    }\n}",
                "@Transactional 没有生效，事务没有开启",
                "Spring AOP 通过代理对象工作。自调用时调用的是原始对象而非代理对象。",
                "[\"logExecution() 是被谁调用的？\", \"AOP 代理对象和目标对象的区别？\"]",
                3);
            seedScenario("Singleton Bean 注入 Prototype Bean 失效", "Spring框架",
                "import org.springframework.stereotype.Service;\nimport org.springframework.beans.factory.annotation.Autowired;\nimport org.springframework.context.annotation.Scope;\n\n@Service\n@Scope(\"prototype\")\nclass MyProto {}\n\n@Service\npublic class SingletonBean {\n    @Autowired private MyProto proto;\n}",
                "每次调用都是同一个 prototype 实例",
                "Singleton Bean 在初始化时注入了一次 Prototype Bean，之后处处持有同一个实例。",
                "[\"Singleton Bean 何时注入依赖？\", \"@Scope 的 proxyMode 参数有什么用？\"]",
                3);
            log.info("Seeded Spring trap scenarios for knowledge tree");
        } catch (Exception e) {
            log.warn("Seed scenarios failed: {}", e.getMessage());
        }
    }

    private void seedTrap(String title, String knowledgePoint, String category, String javaVersion,
                          String trapCode, String pitfall, String explanation, String hintsJson,
                          int difficulty, String submitter, String status, int votes) {
        CommunityTrap t = new CommunityTrap();
        t.setTitle(title);
        t.setKnowledgePoint(knowledgePoint);
        t.setCategory(category);
        t.setJavaVersion(javaVersion);
        t.setTrapCode(trapCode);
        t.setExpectedPitfall(pitfall);
        t.setCorrectExplanation(explanation);
        t.setHints(hintsJson);
        t.setDifficulty(difficulty);
        t.setSubmitter(submitter);
        t.setStatus(status);
        t.setVoteCount(votes);
        t.setGmtCreate(LocalDateTime.now());
        t.setGmtModified(LocalDateTime.now());
        communityTrapMapper.insert(t);
    }

    private void seedScenario(String knowledgePoint, String category, String trapCode,
                              String pitfall, String explanation, String hintsJson, int difficulty) {
        LabScenario s = new LabScenario();
        s.setKnowledgePoint(knowledgePoint);
        s.setCategory(category);
        s.setTrapCode(trapCode);
        s.setExpectedPitfall(pitfall);
        s.setCorrectExplanation(explanation);
        s.setHints(hintsJson);
        s.setDifficulty(difficulty);
        s.setGmtCreate(LocalDateTime.now());
        labScenarioMapper.insert(s);
    }

    // 提交新的社区陷阱代码
    public CommunityTrapDto submit(CommunityTrapSubmitRequest req) {
        CommunityTrap entity = new CommunityTrap();
        entity.setTitle(req.getTitle());
        entity.setKnowledgePoint(req.getKnowledgePoint());
        entity.setCategory(req.getCategory() != null ? req.getCategory() : "Java核心");
        entity.setJavaVersion(req.getJavaVersion() != null ? req.getJavaVersion() : "17");
        entity.setTrapCode(req.getTrapCode());
        entity.setExpectedPitfall(req.getExpectedPitfall());
        entity.setCorrectExplanation(req.getCorrectExplanation());
        entity.setHints(req.getHints());
        entity.setDifficulty(req.getDifficulty() != null ? req.getDifficulty() : 2);
        entity.setSubmitter(req.getSubmitter() != null ? req.getSubmitter() : "anonymous");
        entity.setKnowledgePath(req.getKnowledgePath());  // 关联知识树路径
        entity.setStatus("PENDING");
        entity.setVoteCount(0);
        entity.setGmtCreate(LocalDateTime.now());
        entity.setGmtModified(LocalDateTime.now());
        communityTrapMapper.insert(entity);
        return toDto(entity);
    }

    // 查询陷阱列表，支持按版本/分类/状态过滤
    public List<CommunityTrapDto> listTraps(String javaVersion, String category, String status) {
        LambdaQueryWrapper<CommunityTrap> qw = new LambdaQueryWrapper<CommunityTrap>()
            .orderByDesc(CommunityTrap::getVoteCount)
            .orderByDesc(CommunityTrap::getGmtCreate);
        if (javaVersion != null && !javaVersion.isBlank() && !"all".equals(javaVersion)) {
            qw.eq(CommunityTrap::getJavaVersion, javaVersion);
        }
        if (category != null && !category.isBlank() && !"all".equals(category)) {
            qw.eq(CommunityTrap::getCategory, category);
        }
        if (status != null && !status.isBlank() && !"all".equals(status)) {
            qw.eq(CommunityTrap::getStatus, status);
        }
        List<CommunityTrap> list = communityTrapMapper.selectList(qw);
        return list.stream().map(this::toDto).toList();
    }

    // 根据 ID 获取陷阱详情
    public CommunityTrapDto getById(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        return entity != null ? toDto(entity) : null;
    }

    // 投票
    public void vote(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        if (entity != null) {
            entity.setVoteCount(entity.getVoteCount() + 1);
            entity.setGmtModified(LocalDateTime.now());
            communityTrapMapper.updateById(entity);
        }
    }

    // 审核通过
    public CommunityTrapDto approve(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        if (entity == null) throw new RuntimeException("陷阱不存在");
        entity.setStatus("APPROVED");
        entity.setGmtModified(LocalDateTime.now());
        communityTrapMapper.updateById(entity);
        return toDto(entity);
    }

    // 审核拒绝
    public CommunityTrapDto reject(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        if (entity == null) throw new RuntimeException("陷阱不存在");
        entity.setStatus("REJECTED");
        entity.setGmtModified(LocalDateTime.now());
        communityTrapMapper.updateById(entity);
        return toDto(entity);
    }

    // 删除陷阱
    public void delete(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        if (entity == null) throw new RuntimeException("陷阱不存在");
        communityTrapMapper.deleteById(id);
    }

    // 将社区陷阱导入题库
    public QuestionBank integrateIntoQuestionBank(Long id) {
        CommunityTrap entity = communityTrapMapper.selectById(id);
        if (entity == null) throw new RuntimeException("陷阱不存在");

        // 先创建 LabScenario，保证知识树一致性
        LabScenario scenario = labScenarioMapper.selectOne(
            new LambdaQueryWrapper<LabScenario>()
                .eq(LabScenario::getKnowledgePoint, entity.getTitle())
                .last("LIMIT 1")
        );
        if (scenario == null) {
            scenario = new LabScenario();
            scenario.setKnowledgePoint(entity.getTitle());
            scenario.setCategory("社区-" + entity.getCategory());
            scenario.setTrapCode(entity.getTrapCode());
            scenario.setExpectedPitfall(entity.getExpectedPitfall());
            scenario.setCorrectExplanation(entity.getCorrectExplanation());
            scenario.setHints(entity.getHints());
            scenario.setDifficulty(entity.getDifficulty() != null ? entity.getDifficulty() : 2);
            scenario.setGmtCreate(LocalDateTime.now());
            labScenarioMapper.insert(scenario);
        }

        LambdaQueryWrapper<QuestionBank> qw = new LambdaQueryWrapper<>();
        qw.eq(QuestionBank::getTitle, entity.getTitle());
        long exists = questionBankMapper.selectCount(qw);
        if (exists > 0) {
            throw new RuntimeException("该陷阱已导入题库");
        }

        QuestionBank qb = new QuestionBank();
        qb.setTitle(entity.getTitle());
        qb.setDescription("社区贡献 - " + entity.getKnowledgePoint() + " (Java " + entity.getJavaVersion() + ")");
        qb.setTrapCode(entity.getTrapCode());
        qb.setExpectedPitfall(entity.getExpectedPitfall());
        qb.setCorrectExplanation(entity.getCorrectExplanation());
        qb.setHints(entity.getHints());
        qb.setCategory("社区-" + entity.getCategory());
        qb.setDifficulty(entity.getDifficulty());
        qb.setStatus("PENDING");
        qb.setGmtCreate(LocalDateTime.now());
        qb.setGmtModified(LocalDateTime.now());
        questionBankMapper.insert(qb);

        if (!"APPROVED".equals(entity.getStatus())) {
            entity.setStatus("APPROVED");
            entity.setGmtModified(LocalDateTime.now());
            communityTrapMapper.updateById(entity);
        }
        return qb;
    }

    private CommunityTrapDto toDto(CommunityTrap entity) {
        CommunityTrapDto dto = new CommunityTrapDto();
        dto.setId(entity.getId());
        dto.setTitle(entity.getTitle());
        dto.setKnowledgePoint(entity.getKnowledgePoint());
        dto.setCategory(entity.getCategory());
        dto.setJavaVersion(entity.getJavaVersion());
        dto.setTrapCode(entity.getTrapCode());
        dto.setExpectedPitfall(entity.getExpectedPitfall());
        dto.setCorrectExplanation(entity.getCorrectExplanation());
        if (entity.getHints() != null && !entity.getHints().isBlank()) {
            try {
                dto.setHints(JSON.parseArray(entity.getHints(), String.class));
            } catch (Exception e) {
                dto.setHints(List.of(entity.getHints()));
            }
        }
        dto.setDifficulty(entity.getDifficulty());
        dto.setSubmitter(entity.getSubmitter());
        dto.setStatus(entity.getStatus());
        dto.setVoteCount(entity.getVoteCount());
        dto.setGmtCreate(entity.getGmtCreate());
        return dto;
    }
}