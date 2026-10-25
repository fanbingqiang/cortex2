"""八股文教学 AI 数据模型"""

from pydantic import BaseModel


# ─── 知识树 ────────────────────────────────────────────────

class KnowledgeNode(BaseModel):
    """知识树节点"""
    node_id: str
    title: str
    parent_id: str = ""
    tags: list[str] = []
    difficulty: str = "medium"


# ─── 用户画像 ──────────────────────────────────────────────

class UserProfile(BaseModel):
    """用户画像"""
    user_id: str
    username: str = ""
    mastered: list[str] = []
    weak_points: list[str] = []
    mistake_history: list["MistakeRecord"] = []
    current_node_id: str = ""
    total_score: int = 0
    learning_style: str = "hands-on"
    preferred_difficulty: str = "medium"


class MistakeRecord(BaseModel):
    """错误记录"""
    knowledge_node_id: str
    error_type: str
    description: str
    timestamp: float = 0.0


# ─── 会话 ──────────────────────────────────────────────────

class SessionStep(BaseModel):
    """会话中的一步"""
    step_id: str  # question / diagnosis / teaching / profile
    step_name: str  # 出题 / 诊断 / 讲解 / 更新画像
    status: str = "PENDING"
    input: str = ""
    output: str = ""
    error_message: str = ""
    duration_ms: int = 0
    timestamp: float = 0.0


class Session(BaseModel):
    """一次教学会话"""
    session_id: str
    user_id: str
    knowledge_node_id: str
    knowledge_title: str = ""
    system_prompt: str = ""
    status: str = "PENDING"
    steps: list[SessionStep] = []
    # 出题结果
    code_type: str = ""  # "correct" 或 "incorrect"
    question_code: str = ""
    question: str = ""
    reference_answer: str = ""
    hint: str = ""
    knowledge_points: list[str] = []
    # 用户回答
    user_answer: str = ""
    # 诊断结果
    knowledge_gaps: list[str] = []
    mastery_score: int = 0
    # 教学结果
    teaching_content: str = ""


# ─── API 请求/响应 ─────────────────────────────────────────

class StartSessionRequest(BaseModel):
    """开始会话请求"""
    user_id: str
    knowledge_node_id: str
    knowledge_title: str = ""
    system_prompt: str = ""
    mastered_nodes: list[str] = []
    weak_points: list[str] = []
    difficulty: str = "medium"


class SubmitAnswerRequest(BaseModel):
    """提交回答请求"""
    user_answer: str


class SubmitAnswerResponse(BaseModel):
    """提交回答响应"""
    session_id: str
    diagnosis: dict
    teaching: dict
    profile_update: dict
    next_action: str


class RerunStepRequest(BaseModel):
    """重跑某一步"""
    step_id: str
    custom_input: str = ""


class ChatRequest(BaseModel):
    """聊天请求"""
    user_id: str = "default"
    message: str
    system_prompt: str = ""


class ChatResponse(BaseModel):
    """聊天响应"""
    reply: str  # AI 回复内容（可含 Markdown）
    action: str = ""  # question / diagnosis / teaching / chat
    session_id: str = ""
    session: dict | None = None  # 完整会话追踪
    profile: dict | None = None  # 当前用户画像