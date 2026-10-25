"""八股文教学 AI — FastAPI 入口"""

import json
import logging
from contextlib import asynccontextmanager
from datetime import datetime, timezone

from fastapi import FastAPI, Request, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, StreamingResponse

from config import settings
from models import StartSessionRequest, SubmitAnswerRequest, RerunStepRequest, ChatRequest
from llm_client import LlmClient
from pipeline import TeachingPipeline
from trace_store import trace_store

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(name)s] %(levelname)s: %(message)s")
logger = logging.getLogger(__name__)

llm_client: LlmClient
pipeline: TeachingPipeline


@asynccontextmanager
async def lifespan(app: FastAPI):
    global llm_client, pipeline
    llm_client = LlmClient()
    pipeline = TeachingPipeline(llm_client)
    logger.info("八股文教学 AI 服务启动完成")
    yield
    logger.info("服务关闭")


app = FastAPI(title="八股文教学 AI", version="2.0.0", lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])
app.mount("/static", StaticFiles(directory="static"), name="static")


@app.get("/")
async def index():
    return FileResponse("static/index.html")


@app.post("/api/chat/simple")
async def chat_simple(req: Request):
    """简单对话 — 输入 system_prompt + message，返回文本"""
    body = await req.json()
    system_prompt = body.get("system_prompt", "")
    message = body.get("message", "")
    try:
        result = await llm_client.chat_simple_with_system(system_prompt, message)
        return {"code": 200, "data": {"content": result}}
    except Exception as e:
        logger.error(f"chat_simple 失败: {e}")
        raise HTTPException(status_code=500, detail=str(e))


# ─── 核心 API ───────────────────────────────────────────────

@app.post("/api/session/start")
async def start_session(req: StartSessionRequest):
    """开始教学会话 → 出题"""
    session = await pipeline.start_session(req)
    return session.model_dump()


@app.post("/api/chat")
async def chat(req: ChatRequest):
    """对话式交互 → 自动出题/诊断/讲解"""
    result = await pipeline.chat(req.user_id, req.message, req.system_prompt)
    return result.model_dump()


@app.post("/api/chat/stream")
async def chat_stream(req: Request):
    """流式对话 — SSE 逐字输出"""
    body = await req.json()
    user_id = body.get("user_id", "default")
    message = body.get("message", "")
    system_prompt = body.get("system_prompt", "")

    # 同步用户画像：Java 传入的静态偏好字段写入 trace_store
    # 注意：mastered/weak_points 由 pipeline 动态维护，不在此处覆盖
    profile_data = body.get("profile", None)
    if profile_data and isinstance(profile_data, dict):
        # 只同步静态偏好字段，不覆盖 pipeline 动态维护的 mastered/weak_points
        static_fields = {}
        for field in ("preferred_difficulty", "learning_style", "username"):
            if field in profile_data and profile_data[field] is not None:
                static_fields[field] = profile_data[field]
        if static_fields:
            trace_store.update_profile(user_id, **static_fields)
            logger.info(f"画像同步(静态): user={user_id}, fields={list(static_fields.keys())}")

    async def event_generator():
        async for chunk in pipeline.chat_stream(user_id, message, system_prompt):
            yield chunk
    return StreamingResponse(
        event_generator(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "Connection": "keep-alive",
            "X-Accel-Buffering": "no",
        }
    )


@app.put("/api/profile/{user_id}")
async def sync_profile(user_id: str, req: Request):
    """个人中心更新画像 → 实时同步到 trace_store"""
    try:
        body = await req.json()
        # 只同步静态偏好字段，mastered/weak_points 由 pipeline 动态维护
        static_fields = {}
        if "preferred_difficulty" in body:
            static_fields["preferred_difficulty"] = body["preferred_difficulty"]
        if "learning_style" in body:
            static_fields["learning_style"] = body["learning_style"]
        if "username" in body:
            static_fields["username"] = body["username"]
        if "weak_points" in body:
            static_fields["weak_points"] = body["weak_points"]
        if static_fields:
            trace_store.update_profile(user_id, **static_fields)
            logger.info(f"画像实时同步: user={user_id}, fields={list(static_fields.keys())}")
        return {"code": 200, "message": "ok"}
    except Exception as e:
        logger.error(f"画像同步失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/chat/feedback")
async def chat_feedback(req: Request):
    """用户对AI回复不满意 → AI分析上下文生成优化建议 → 存入变更日志"""
    try:
        body = await req.json()
        messages = body.get("messages", [])
        user_id = body.get("user_id", "anonymous")
        conversation_id = body.get("conversationId", "")

        if not messages:
            raise HTTPException(status_code=400, detail="缺少消息上下文")

        # 构建分析提示词
        context_text = "\n".join([
            f"[{m['role']}]: {m['content'][:300]}"
            for m in messages[-6:]  # 最近6条
        ])

        analysis_prompt = (
            "你是一个AI助手优化分析师。用户对以下AI回复点了\"不满意\"。\n"
            "请分析对话上下文，找出AI回复的不足之处，并生成一条简洁的优化建议（50字以内）。\n"
            "不要罗列问题，只给出一条最关键的改进方向。\n\n"
            f"对话上下文:\n{context_text}\n\n"
            "优化建议:"
        )

        # 调用 LLM 分析
        suggestion = await llm_client.chat_simple_with_system(
            "你是一个AI质量分析师，简洁地给出优化建议。",
            analysis_prompt
        )

        # 存储到变更日志
        suggestion_text = suggestion.strip()[:200] if suggestion else "AI回复质量需要优化"
        trace_store.add_feedback_suggestion(
            user_id=user_id,
            conversation_id=conversation_id,
            context=messages,
            suggestion=suggestion_text,
        )

        logger.info(f"反馈已记录: user={user_id}, suggestion={suggestion_text[:50]}...")
        return {"status": "ok", "suggestion": suggestion_text}

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"反馈处理失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/session/{session_id}/submit")
async def submit_answer(session_id: str, req: SubmitAnswerRequest):
    """提交回答 → 诊断 + 讲解 + 更新画像"""
    session = trace_store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    result = await pipeline.submit_answer(session, req.user_answer)
    return result.model_dump()


# ─── 画像 & 提示词 ──────────────────────────────────────────

@app.get("/api/profile/{user_id}")
async def get_profile(user_id: str):
    """获取用户画像 + 系统提示词"""
    profile = trace_store.get_profile(user_id)
    prompt = trace_store.get_system_prompt(user_id)
    return {"profile": profile, "system_prompt": prompt}


@app.put("/api/profile/{user_id}")
async def update_profile(user_id: str, req: Request):
    """更新用户画像或系统提示词"""
    body = await req.json()
    if "system_prompt" in body:
        trace_store.set_system_prompt(user_id, body["system_prompt"])
    if "difficulty" in body:
        trace_store.update_profile(user_id, preferred_difficulty=body["difficulty"])
    if "mastered" in body:
        trace_store.update_profile(user_id, mastered=body["mastered"])
    if "weak_points" in body:
        trace_store.update_profile(user_id, weak_points=body["weak_points"])
    if "total_score" in body:
        trace_store.update_profile(user_id, total_score=body["total_score"])
    profile = trace_store.get_profile(user_id)
    prompt = trace_store.get_system_prompt(user_id)
    return {"profile": profile, "system_prompt": prompt}


# ─── Agent 提示词管理 ──────────────────────────────────────

@app.get("/api/prompts")
async def get_prompts():
    """获取所有 Agent 提示词"""
    prompts = trace_store.get_agent_prompts()
    return {"prompts": prompts}


@app.put("/api/prompts")
async def update_prompts(req: Request):
    """批量更新 Agent 提示词（带版本历史）"""
    body = await req.json()
    author = body.get("author", "admin")
    if "prompts" in body:
        for key, value in body["prompts"].items():
            trace_store.update_agent_prompt(key, value, author)
    return {"status": "ok"}


@app.get("/api/prompts/{key}/history")
async def get_prompt_history(key: str):
    """获取指定提示词的版本历史"""
    history = trace_store.get_prompt_history(key)
    return {"key": key, "history": history}


@app.put("/api/prompts/{key}/rollback")
async def rollback_prompt(key: str, req: Request):
    """回滚提示词到指定版本"""
    try:
        raw = await req.body()
        logger.info(f"回滚请求 key={key} raw_body={raw[:200] if raw else 'EMPTY'}")
        body = json.loads(raw) if raw else {}
        version = body.get("version", 0)
        author = body.get("author", "admin")
        success = trace_store.rollback_prompt(key, version, author)
        if not success:
            raise HTTPException(status_code=404, detail=f"Version {version} not found for key {key}")
        return {"status": "ok", "key": key, "version": version}
    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"回滚失败 key={key}: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.get("/api/admin/prompt-changes")
async def get_prompt_changes(limit: int = 50):
    """获取所有 Agent 的提示词变更日志（聚合历史）"""
    all_history = trace_store.get_all_prompt_history()
    return {"changes": all_history[:limit], "total": len(all_history)}


@app.post("/api/admin/prompt-changes/summarize")
async def summarize_changes(req: Request):
    """AI 分析变更记录，生成总结和优化提示词"""
    try:
        body = await req.json()
        changes = body.get("changes", [])

        if not changes:
            raise HTTPException(status_code=400, detail="缺少变更记录")

        # 构建分析提示词
        changes_text = "\n".join([
            f"[{c.get('type', '?')}] {c.get('agent_key', '?')}: {c.get('message', '')[:100]}"
            for c in changes[:20]
        ])

        prompt = (
            "你是一个AI系统优化分析师。根据以下变更记录，分析系统的优化方向，并生成一条新的提示词。\n\n"
            "变更记录:\n{changes}\n\n"
            "请按以下JSON格式输出（只输出JSON，不要其他内容）：\n"
            '{{"summary": "50字以内的变更总结", "prompt": "优化后的新提示词内容", "target_agent": "exam|diagnose|teach|intent|chat"}}\n\n'
            "target_agent选择最相关的Agent：exam=出题, diagnose=诊断, teach=教学, intent=意图分类, chat=闲聊"
        ).format(changes=changes_text)

        result = await llm_client.chat_simple_with_system(
            "你是一个AI系统优化分析师，输出JSON格式。",
            prompt
        )

        # 解析 JSON
        import re
        json_match = re.search(r'\{[^{}]*"summary"[^{}]*\}', result, re.DOTALL)
        if json_match:
            data = json.loads(json_match.group())
        else:
            data = {"summary": "AI分析完成", "prompt": "", "target_agent": "exam"}

        logger.info(f"AI总结变更: {data.get('summary', '')[:50]}...")
        return data

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"AI总结失败: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=str(e))


@app.post("/api/admin/key-pool/add")
async def add_key(req: Request):
    """添加 API Key"""
    body = await req.json()
    new_key = body.get("key", "").strip()
    if not new_key:
        raise HTTPException(status_code=400, detail="Key 不能为空")
    from key_pool import key_pool
    keys = key_pool.add_key(new_key)
    return {"status": "ok", "keys": keys, "count": len(keys)}


@app.delete("/api/admin/key-pool/{key_index}")
async def remove_key(key_index: int):
    """删除指定索引的 API Key"""
    from key_pool import key_pool
    removed = key_pool.remove_key(key_index)
    if removed is None:
        raise HTTPException(status_code=400, detail="索引超出范围")
    return {"status": "ok", "removed": removed[:8] + "...", "keys": key_pool._keys, "count": len(key_pool._keys)}


# ─── 追踪/调试 API ──────────────────────────────────────────

@app.get("/api/session/{session_id}")
async def get_session(session_id: str):
    """获取会话完整追踪"""
    session = trace_store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    return session.model_dump()


@app.put("/api/session/{session_id}")
async def update_session(session_id: str, req: Request):
    """编辑会话数据（调试用）"""
    session = trace_store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    body = await req.json()
    # 更新会话字段
    for field in ["knowledge_title", "question_code", "question",
                  "reference_answer", "hint", "code_type", "system_prompt"]:
        if field in body:
            setattr(session, field, body[field])
    trace_store.update_session(session)
    return {"status": "ok", "session": session.model_dump()}


@app.get("/api/sessions")
async def list_sessions():
    """列出所有会话"""
    return {"sessions": trace_store.get_all_sessions()}


@app.get("/api/session/{session_id}/step/{step_id}")
async def get_step(session_id: str, step_id: str):
    """获取某一步的详情"""
    step = trace_store.get_step(session_id, step_id)
    if not step:
        raise HTTPException(status_code=404, detail="Step not found")
    return step


@app.put("/api/session/{session_id}/step/{step_id}/input")
async def update_step_input(session_id: str, step_id: str, req: Request):
    """编辑步骤输入"""
    body = await req.json()
    if not trace_store.update_step_input(session_id, step_id, body.get("input", "")):
        raise HTTPException(status_code=404, detail="Step not found")
    return {"status": "ok"}


@app.put("/api/session/{session_id}/step/{step_id}/output")
async def update_step_output(session_id: str, step_id: str, req: Request):
    """编辑步骤输出"""
    body = await req.json()
    if not trace_store.update_step_output(session_id, step_id, body.get("output", "")):
        raise HTTPException(status_code=404, detail="Step not found")
    return {"status": "ok"}


@app.post("/api/session/{session_id}/step/{step_id}/rerun")
async def rerun_step(session_id: str, step_id: str, req: RerunStepRequest):
    """重跑某一步"""
    session = trace_store.get_session(session_id)
    if not session:
        raise HTTPException(status_code=404, detail="Session not found")
    result = await pipeline.rerun_step(session, step_id, req.custom_input)
    return result


# ─── Key 池管理 ─────────────────────────────────────────

@app.get("/api/admin/key-pool")
async def get_key_pool_stats():
    """获取 Key 池状态"""
    from key_pool import key_pool
    return {"code": 200, "data": key_pool.stats()}


# ─── 仪表盘统计 ─────────────────────────────────────────

@app.get("/api/admin/stats")
async def get_stats():
    """获取系统统计"""
    return {
        "code": 200,
        "data": {
            "total_sessions": trace_store.count_sessions(),
            "total_profiles": trace_store.count_profiles(),
            "total_prompt_changes": len(trace_store.get_all_prompt_history()),
            "prompt_count": trace_store.count_prompts(),
        }
    }


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(
        "main:app",
        host=settings.service_host,
        port=settings.service_port,
        workers=settings.workers,
    )