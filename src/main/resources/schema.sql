CREATE TABLE IF NOT EXISTS user_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL UNIQUE,
    username VARCHAR(100),
    personality_tags VARCHAR(500),
    work_habits VARCHAR(500),
    preferences VARCHAR(500),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS mistake_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    keyword VARCHAR(100) NOT NULL,
    description TEXT,
    task_id VARCHAR(50),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_metadata (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_id VARCHAR(50) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    capabilities VARCHAR(500),
    input_types VARCHAR(200),
    output_types VARCHAR(200),
    priority INT DEFAULT 0,
    enabled TINYINT DEFAULT 1,
    prompt_template TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(50) NOT NULL UNIQUE,
    user_id VARCHAR(50) NOT NULL,
    title VARCHAR(255),
    description TEXT,
    status VARCHAR(20) DEFAULT 'PENDING',
    task_graph TEXT,
    result TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS task_execution_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(50) NOT NULL,
    node_id VARCHAR(50) NOT NULL,
    agent_id VARCHAR(50),
    input TEXT,
    output TEXT,
    status VARCHAR(20),
    error_message TEXT,
    duration_ms BIGINT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS llm_call_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id VARCHAR(50),
    model VARCHAR(50),
    input_tokens INT,
    output_tokens INT,
    cost DECIMAL(10, 6),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lab_scenario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    knowledge_point VARCHAR(200) NOT NULL,
    category VARCHAR(100),
    trap_code TEXT NOT NULL,
    expected_pitfall VARCHAR(500),
    correct_explanation TEXT,
    hints TEXT,
    difficulty INT DEFAULT 1,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lab_session (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id VARCHAR(50) NOT NULL UNIQUE,
    scenario_id BIGINT,
    user_id VARCHAR(50),
    status VARCHAR(20) DEFAULT 'IN_PROGRESS',
    attempts INT DEFAULT 0,
    chat_history TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO agent_metadata (agent_id, name, description, capabilities, input_types, output_types, priority, enabled, prompt_template)
SELECT * FROM (VALUES
('code-analyzer', '代码分析Agent', '分析代码结构、发现潜在问题、提供重构建议', '["code-analysis","bug-detection","refactoring"]', '["text","code"]', '["text","json"]', 1, 1, '你是一个专业的代码分析专家。请分析用户提供的代码，从以下维度进行评估：
1. 代码质量和可读性
2. 潜在的Bug和安全问题
3. 性能优化建议
4. 重构建议

请以结构化的方式输出分析结果。'),

('tech-designer', '技术方案Agent', '根据需求设计技术方案，包括架构设计、技术选型、实现路径', '["architecture","tech-design","solution"]', '["text"]', '["text","json"]', 1, 1, '你是一个资深的技术架构师。请根据用户的需求描述，设计一个完整的技术方案，包括：
1. 需求分析
2. 架构设计
3. 技术选型及理由
4. 实现步骤
5. 潜在风险和应对措施

请以清晰的层次结构输出方案。'),

('search-agent', '搜索Agent', '搜索和收集信息', '["search","information-collection"]', '["text"]', '["text","json"]', 1, 1, '你是一个信息搜索专家。请根据用户的需求，整理相关信息，包括：
1. 关键概念解释
2. 相关技术或工具
3. 最佳实践
4. 参考资源

请以结构化的方式输出搜索结果。'),

('report-generator', '报告生成Agent', '生成结构化的分析报告', '["report","summary","documentation"]', '["text","json"]', '["text","markdown"]', 1, 1, '你是一个专业的技术文档撰写专家。请根据提供的信息，生成一份结构清晰、内容详实的报告，包括：
1. 摘要
2. 详细分析
3. 结论和建议

请使用Markdown格式输出。')
) AS t (agent_id, name, description, capabilities, input_types, output_types, priority, enabled, prompt_template)
WHERE NOT EXISTS (SELECT 1 FROM agent_metadata WHERE agent_metadata.agent_id = t.agent_id);

INSERT INTO lab_scenario (knowledge_point, category, trap_code, expected_pitfall, correct_explanation, hints, difficulty)
SELECT * FROM (VALUES
(
'为什么 Integer 用 == 比较 100 是 true，200 却是 false？',
'Java基础',
'public class IntegerCacheTrap {
    public static void main(String[] args) {
        Integer a = 100;
        Integer b = 100;
        Integer c = 200;
        Integer d = 200;

        System.out.println("100 == 100 ? " + (a == b));
        System.out.println("200 == 200 ? " + (c == d));
        System.out.println("equals 200 ? " + (c.equals(d)));
        System.out.println("new Integer(100) == 100 ? " + (new Integer(100) == a));
    }
}
',
'用 == 比较 Integer 时，小数值返回 true，大数值返回 false',
'Integer 在 -128~127 之间用缓存池，自动装箱时复用对象。超出范围创建新对象，== 比的是引用地址。用 equals 比较值。',
'["四个输出中哪些是 true 哪些是 false？", "100 和 200 的行为不一样，分界线在哪？", "猜猜 Java 是不是给常用数字做了缓存？范围是多少？"]',
1
),

(
'为什么 new String("hello") 用 == 比较结果是 false？',
'Java基础',
'public class StringEqualsTrap {
    public static void main(String[] args) {
        String s1 = "hello";
        String s2 = "hello";
        String s3 = new String("hello");
        String s4 = new String("hello");

        System.out.println("字面量 == 字面量: " + (s1 == s2));
        System.out.println("new String == 字面量: " + (s3 == s1));
        System.out.println("new String == new String: " + (s3 == s4));
        System.out.println("new String equals new String: " + (s3.equals(s4)));

        String s5 = "hel" + "lo";
        System.out.println("拼接字面量 == 字面量: " + (s5 == s1));
    }
}
',
'new 出来的 String 用 == 比较返回 false，字面量和拼接字面量用 == 返回 true',
'== 比较引用地址，equals 比较内容。字面量在常量池复用，new 在堆上创建新对象。编译期 "hel"+"lo" 优化为 "hello"。',
'["同样内容，有的 == true，有的 false，原因在哪？", "字面量和 new 创建 String 的区别是什么？", "equals 和 == 分别比较什么？"]',
1
),

(
'为什么方法里改了 List，外面的 List 也变了？',
'Java基础',
'import java.util.*;

public class ListPassByRef {
    public static void main(String[] args) {
        List<String> myList = new ArrayList<>();
        myList.add("苹果");
        myList.add("香蕉");

        System.out.println("调用前: " + myList);
        addFruit(myList);
        System.out.println("调用后: " + myList);
        System.out.println("列表大小: " + myList.size());
    }

    static void addFruit(List<String> list) {
        list.add("橘子");
        System.out.println("方法内部: " + list);
    }
}
',
'方法外部的列表被内部修改了',
'Java 是值传递，但对象参数传递的是引用的副本，指向同一个对象。修改对象内容会影响外部。但 list = new ArrayList() 不会影响外部。',
'["调用前后 myList 变了没？", "Java 是值传递还是引用传递？", "如果方法内 list = new ArrayList()，外面会变吗？"]',
1
),

(
'HashMap 用 Person 做 key，为什么 get 不到值？',
'Java基础',
'import java.util.*;

class Person {
    String name;
    Person(String name) { this.name = name; }
}

public class HashMapKeyTrap {
    public static void main(String[] args) {
        Map<Person, String> map = new HashMap<>();
        map.put(new Person("小明"), "学生");
        map.put(new Person("小红"), "老师");

        System.out.println("查询小明: " + map.get(new Person("小明")));
        System.out.println("map 大小: " + map.size());

        Person p = new Person("小李");
        map.put(p, "医生");
        System.out.println("用原对象查询: " + map.get(p));
    }
}
',
'new 一个相同字段的对象去 HashMap 中 get，返回 null',
'HashMap 依赖 hashCode() 和 equals() 定位 key。自定义类没重写这两个方法，用的是 Object 的默认实现（比较引用地址）。new Person("小明") 和另一个 new Person("小明") 不是同一个对象。',
'["get 返回了什么？", "HashMap 怎么判断 key 相等？", "需要重写哪两个方法？"]',
2
),

(
'为什么 try 里 return 了，finally 还会执行？',
'Java基础',
'public class FinallyTrap {
    public static void main(String[] args) {
        System.out.println("返回值: " + test());
    }

    static int test() {
        int x = 1;
        try {
            System.out.println("try 中");
            return x++;
        } finally {
            System.out.println("finally 中, x=" + x);
        }
    }
}
',
'finally 在 return 之后执行，返回值是 return 时保存的值',
'finally 块在 try 的 return 之前执行，但返回值为 return 语句执行时的值。x++ 是先返回后自增，所以返回值是 1，finally 中 x=2。',
'["return x++ 的返回值是多少？", "finally 在 return 之前还是之后执行？", "x++ 和 ++x 的区别？"]',
2
),

(
'异常捕获：多个 catch 的顺序有讲究吗？',
'Java基础',
'public class CatchOrderTrap {
    public static void main(String[] args) {
        try {
            int[] arr = new int[3];
            System.out.println(arr[5]);
        } catch (RuntimeException e) {
            System.out.println("捕获 RuntimeException");
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("捕获数组越界");
        }
        System.out.println("程序结束");
    }
}
',
'ArrayIndexOutOfBoundsException 是 RuntimeException 的子类，第一个 catch 已捕获，第二个永远执行不到',
'catch 顺序很重要：子类异常在前，父类在后。ArrayIndexOutOfBoundsException 继承 RuntimeException，所以被第一个 catch 捕获。编译时会因不可达代码报错。',
'["运行后输出了什么？", "两个 catch 的顺序有什么问题？", "Exception 的继承体系是怎样的？"]',
2
)
) AS t (knowledge_point, category, trap_code, expected_pitfall, correct_explanation, hints, difficulty)
WHERE NOT EXISTS (SELECT 1 FROM lab_scenario WHERE lab_scenario.knowledge_point = t.knowledge_point);

CREATE TABLE IF NOT EXISTS lab_question_bank (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    title VARCHAR(200) NOT NULL,
    description TEXT,
    trap_code TEXT,
    expected_pitfall VARCHAR(500),
    correct_explanation TEXT,
    hints TEXT,
    category VARCHAR(100),
    difficulty INT DEFAULT 2,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lab_question_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    mastered BOOLEAN DEFAULT FALSE,
    review_count INT DEFAULT 0,
    last_review_time TIMESTAMP,
    next_review_time TIMESTAMP,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lab_knowledge_card (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    title VARCHAR(200),
    key_points TEXT,
    detail_explanation TEXT,
    code_snippet TEXT,
    common_pitfalls TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS lab_discussion (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    question_id BIGINT NOT NULL,
    parent_id BIGINT,
    user_id VARCHAR(50),
    content TEXT NOT NULL,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== 全局AI助手 ====================

CREATE TABLE IF NOT EXISTS assistant_config (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    config_key VARCHAR(100) NOT NULL UNIQUE,
    config_value TEXT,
    config_type VARCHAR(50) DEFAULT 'string',
    description VARCHAR(500),
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO assistant_config (config_key, config_value, config_type, description) SELECT * FROM (VALUES
('temperature', '0.7', 'double', 'AI回复的随机性，0-1之间，越高越随机'),
('max_tokens', '2048', 'int', 'AI回复的最大token数'),
('model', 'deepseek-chat', 'string', '使用的AI模型'),
('system_prompt', '你是一个智能编程导师"小C"，可以帮助用户解决任何编程问题。回答要简洁有力、有针对性，不要泛泛而谈。当用户的问题不够具体时，主动询问细节。', 'text', '系统提示词'),
('rag_enabled', 'true', 'boolean', '是否启用RAG知识检索'),
('history_enabled', 'true', 'boolean', '是否使用对话历史'),
('evolution_enabled', 'true', 'boolean', '是否启用自我进化功能'),
('max_history_length', '20', 'int', '对话历史最大条数')
) AS t (config_key, config_value, config_type, description)
WHERE NOT EXISTS (SELECT 1 FROM assistant_config WHERE assistant_config.config_key = t.config_key);

CREATE TABLE IF NOT EXISTS assistant_conversation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(50) NOT NULL UNIQUE,
    user_id VARCHAR(50) NOT NULL,
    title VARCHAR(200),
    message_count INT DEFAULT 0,
    status VARCHAR(20) DEFAULT 'ACTIVE',
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS assistant_message (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    metadata TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== RAG知识块 ====================

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    chunk_key VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    keywords VARCHAR(500),
    source VARCHAR(100),
    source_id BIGINT,
    weight INT DEFAULT 1,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ==================== 反馈与自我进化 ====================

CREATE TABLE IF NOT EXISTS feedback_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(50),
    message_id BIGINT,
    user_id VARCHAR(50),
    rating INT NOT NULL,
    feedback_type VARCHAR(20),
    comment TEXT,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS evolution_insight (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    insight_key VARCHAR(200) NOT NULL,
    insight_content TEXT NOT NULL,
    category VARCHAR(100),
    confidence DOUBLE DEFAULT 1.0,
    source_count INT DEFAULT 1,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);


-- ==================== 知识树节点进度 ====================
CREATE TABLE IF NOT EXISTS lab_tree_progress (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(50) NOT NULL,
    mastered BOOLEAN DEFAULT FALSE,
    gmt_create TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    gmt_modified TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (node_id, user_id)
);
