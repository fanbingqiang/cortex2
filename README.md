# Cortex2 — Java 后端面试学习平台

基于 Spring Boot + Vue 3 + Python AI 的全栈学习平台，提供知识树、陷阱代码、AI 导师、题库练习等功能。

## 架构

```
Vue 3 前端 (5173)  ──→  Java 后端 (8081)  ──→  MySQL (3306)
                              │
                              └──→  Python AI 服务 (8001)  ──→  DeepSeek API
```

- **Java 后端**：用户认证、数据库操作、所有业务 API
- **Python AI 服务**：仅负责 LLM 调用、多 Key 池管理、上下文压缩
- **Vue 3 前端**：全部页面和交互

## 快速启动

### 1. 启动 MySQL

确保 MySQL 8.0 已运行在 3306 端口，数据库名 `cortex2`。

### 2. 启动 Python AI 服务

```powershell
cd ai-assistant-py\agent-engine-service
pip install -r requirements.txt
python main.py
# 运行在 http://localhost:8001
```

### 3. 启动 Java 后端

```powershell
cd cortex2
mvn spring-boot:run
# 运行在 http://localhost:8081
```

### 4. 启动前端

```powershell
cd lab-frontend
npm install
npm run dev
# 运行在 http://localhost:5173
```

### 5. 访问

打开浏览器访问 **http://localhost:5173/lab/**

## 功能模块

- **知识树**：9 大模块（Redis、MySQL、Spring、微服务、消息中间件、Java集合、并发编程、JVM、企业场景），每个节点可生成陷阱代码练习
- **AI 导师**：苏格拉底式教学，通过陷阱代码引导学习，支持流式对话
- **题库练习**：选择题 + 代码题，按知识树路径分类
- **知识卡片**：学习笔记，AI 自动生成知识点摘要，支持编辑和删除
- **社区陷阱**：用户提交踩坑案例，含投票和审核
- **个人中心**：学习画像（薄弱点/已掌握），支持自定义修改
- **后台管理**：管理员可见，包含仪表盘、Prompt 管理、流程追踪、优化建议、用户管理

## 项目结构

```
cortex2/
├── src/main/java/com/cortex/   # Java 后端
│   ├── lab/controller/         # API 控制器
│   ├── lab/service/            # 业务服务
│   ├── lab/entity/             # 数据库实体
│   ├── lab/mapper/             # MyBatis Mapper
│   └── auth/                   # JWT 认证
├── ai-assistant-py/            # Python AI 服务
│   └── agent-engine-service/   # LLM 调用、Key 池、上下文压缩
└── lab-frontend/               # Vue 3 前端
    └── src/views/              # 页面组件
```