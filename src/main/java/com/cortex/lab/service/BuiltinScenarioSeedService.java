package com.cortex.lab.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.cortex.lab.entity.LabScenario;
import com.cortex.lab.entity.QuestionBank;
import com.cortex.lab.mapper.LabScenarioMapper;
import com.cortex.lab.mapper.QuestionBankMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 内置陷阱题目种子数据（Java 源码 UTF-8，避免 init-mysql.sql 在 Windows/Docker 下中文乱码）。
 */
@Slf4j
@Service
@Order(1)
@RequiredArgsConstructor
public class BuiltinScenarioSeedService {

    private static final String PROBE_NODE = "java-basics-datatype";

    private final LabScenarioMapper scenarioMapper;
    private final QuestionBankMapper questionBankMapper;

    // @PostConstruct  // 已禁用自动种子，数据由用户手动管理
    public void ensureBuiltinData() {
        try {
            LabScenario probe = scenarioMapper.selectOne(
                new LambdaQueryWrapper<LabScenario>().eq(LabScenario::getNodeId, PROBE_NODE).last("LIMIT 1")
            );
            if (probe != null && !isGarbled(probe.getKnowledgePoint())) {
                return;
            }
            log.warn("检测到内置题目缺失或乱码，正在从 Java 重新种子化...");
            repairAndSeed();
        } catch (Exception e) {
            log.warn("内置题目种子化失败: {}", e.getMessage());
        }
    }

    private void repairAndSeed() {
        scenarioMapper.delete(new LambdaQueryWrapper<LabScenario>()
            .eq(LabScenario::getCategory, "Java基础"));
        questionBankMapper.delete(new LambdaQueryWrapper<QuestionBank>()
            .eq(QuestionBank::getCategory, "Java基础"));

        for (BuiltinSeed seed : builtinSeeds()) {
            LabScenario s = new LabScenario();
            s.setNodeId(seed.nodeId());
            s.setKnowledgePoint(seed.title());
            s.setCategory("Java基础");
            s.setTrapCode(seed.trapCode());
            s.setExpectedPitfall(seed.pitfall());
            s.setCorrectExplanation(seed.explanation());
            s.setHints(seed.hints());
            s.setDifficulty(seed.difficulty());
            s.setGmtCreate(LocalDateTime.now());
            scenarioMapper.insert(s);

            QuestionBank q = new QuestionBank();
            q.setTitle(seed.title());
            q.setDescription(seed.pitfall());
            q.setTrapCode(seed.trapCode());
            q.setExpectedPitfall(seed.pitfall());
            q.setCorrectExplanation(seed.explanation());
            q.setHints(seed.hints());
            q.setCategory("Java基础");
            q.setDifficulty(seed.difficulty());
            q.setStatus("ACTIVE");
            q.setGmtCreate(LocalDateTime.now());
            q.setGmtModified(LocalDateTime.now());
            questionBankMapper.insert(q);
        }
        log.info("已重新种子化 {} 道内置题目到场景库与题库", builtinSeeds().size());
    }

    private static boolean isGarbled(String text) {
        if (text == null || text.isBlank()) return true;
        if (text.contains("\uFFFD")) return true;
        return !text.startsWith("为什么");
    }

    private static List<BuiltinSeed> builtinSeeds() {
        return List.of(
            new BuiltinSeed("java-basics-datatype",
                "为什么 Integer 用 == 比较 100 是 true，200 却是 false？",
                """
                public class IntegerCacheTrap {
                    public static void main(String[] args) {
                        Integer a = 100;
                        Integer b = 100;
                        Integer c = 200;
                        Integer d = 200;
                        System.out.println("100 == 100 ? " + (a == b));
                        System.out.println("200 == 200 ? " + (c == d));
                        System.out.println("equals 200 ? " + (c.equals(d)));
                    }
                }""",
                "用 == 比较 Integer 时，小数值返回 true，大数值返回 false",
                "Integer 在 -128~127 之间用缓存池，自动装箱时复用对象。超出范围创建新对象，== 比的是引用地址。用 equals 比较值。",
                "[\"四个输出中哪些是 true 哪些是 false？\", \"100 和 200 的行为不一样，分界线在哪？\", \"猜猜 Java 是不是给常用数字做了缓存？范围是多少？\"]",
                1),
            new BuiltinSeed("java-basics-string",
                "为什么 new String(\"hello\") 用 == 比较结果是 false？",
                """
                public class StringEqualsTrap {
                    public static void main(String[] args) {
                        String s1 = "hello";
                        String s2 = "hello";
                        String s3 = new String("hello");
                        String s4 = new String("hello");
                        System.out.println("字面量 == 字面量: " + (s1 == s2));
                        System.out.println("new String == 字面量: " + (s3 == s1));
                        System.out.println("new String == new String: " + (s3 == s4));
                        System.out.println("new String equals new String: " + (s3.equals(s4)));
                    }
                }""",
                "new 出来的 String 用 == 比较返回 false，字面量和拼接字面量用 == 返回 true",
                "== 比较引用地址，equals 比较内容。字面量在常量池复用，new 在堆上创建新对象。",
                "[\"同样内容，有的 == true，有的 false，原因在哪？\", \"字面量和 new 创建 String 的区别是什么？\", \"equals 和 == 分别比较什么？\"]",
                1),
            new BuiltinSeed("java-basics-passing",
                "为什么方法里改了 List，外面的 List 也变了？",
                """
                import java.util.*;
                public class ListPassByRef {
                    public static void main(String[] args) {
                        List<String> myList = new ArrayList<>();
                        myList.add("苹果");
                        myList.add("香蕉");
                        System.out.println("调用前: " + myList);
                        addFruit(myList);
                        System.out.println("调用后: " + myList);
                    }
                    static void addFruit(List<String> list) {
                        list.add("橘子");
                    }
                }""",
                "方法外部的列表被内部修改了",
                "Java 是值传递，但对象参数传递的是引用的副本，指向同一个对象。修改对象内容会影响外部。",
                "[\"调用前后 myList 变了没？\", \"Java 是值传递还是引用传递？\", \"如果方法内 list = new ArrayList()，外面会变吗？\"]",
                1),
            new BuiltinSeed("java-basics-equals",
                "HashMap 用 Person 做 key，为什么 get 不到值？",
                """
                import java.util.*;
                class Person {
                    String name;
                    Person(String name) { this.name = name; }
                }
                public class HashMapKeyTrap {
                    public static void main(String[] args) {
                        Map<Person, String> map = new HashMap<>();
                        map.put(new Person("小明"), "学生");
                        System.out.println("查询小明: " + map.get(new Person("小明")));
                    }
                }""",
                "new 一个相同字段的对象去 HashMap 中 get，返回 null",
                "HashMap 依赖 hashCode() 和 equals() 定位 key。自定义类没重写这两个方法，用的是 Object 默认实现。",
                "[\"get 返回了什么？\", \"HashMap 怎么判断 key 相等？\", \"需要重写哪两个方法？\"]",
                2),
            new BuiltinSeed("exception-try-finally",
                "为什么 try 里 return 了，finally 还会执行？",
                """
                public class FinallyTrap {
                    public static void main(String[] args) {
                        System.out.println("返回值: " + test());
                    }
                    static int test() {
                        int x = 1;
                        try {
                            return x++;
                        } finally {
                            System.out.println("finally 中, x=" + x);
                        }
                    }
                }""",
                "finally 在 return 之后执行，返回值是 return 时保存的值",
                "finally 块在 try 的 return 之前执行，但返回值为 return 语句执行时的值。x++ 是先返回后自增。",
                "[\"return x++ 的返回值是多少？\", \"finally 在 return 之前还是之后执行？\", \"x++ 和 ++x 的区别？\"]",
                2),
            new BuiltinSeed("exception-checked",
                "异常捕获：多个 catch 的顺序有讲究吗？",
                """
                public class CatchOrderTrap {
                    public static void main(String[] args) {
                        try {
                            int[] arr = new int[3];
                            System.out.println(arr[5]);
                        } catch (RuntimeException e) {
                            System.out.println("捕获 RuntimeException");
                        } catch (ArrayIndexOutOfBoundsException e) {
                            System.out.println("捕获数组越界");
                        }
                    }
                }""",
                "ArrayIndexOutOfBoundsException 是 RuntimeException 的子类，子类 catch 应在前",
                "catch 顺序很重要：子类异常在前，父类在后。否则子类 catch 不可达，编译报错。",
                "[\"运行后输出了什么？\", \"两个 catch 的顺序有什么问题？\", \"Exception 的继承体系是怎样的？\"]",
                2)
        );
    }

    private record BuiltinSeed(String nodeId, String title, String trapCode, String pitfall,
                               String explanation, String hints, int difficulty) {}
}
