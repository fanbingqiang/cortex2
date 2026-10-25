"""用户画像管理 — 内存存储"""

import json
import logging
from typing import Any

from models import UserProfileDto, MistakeDto

logger = logging.getLogger(__name__)


class MemoryService:
    """用户画像 + 错误记录管理（内存版）"""

    _profiles: dict[str, dict] = {}
    _mistakes: dict[str, list[dict]] = {}

    @staticmethod
    def _to_user_profile_dto(profile: dict) -> UserProfileDto:
        mistakes = []
        for m in MemoryService._mistakes.get(profile["user_id"], [])[-10:]:
            mistakes.append(MistakeDto(keyword=m["keyword"], description=m.get("description", "")))
        return UserProfileDto(
            user_id=profile["user_id"],
            username=profile.get("username", ""),
            personality_tags=profile.get("personality_tags", []),
            work_habits=profile.get("work_habits", []),
            mistakes=mistakes,
        )

    async def get_or_create_profile(self, user_id: str, username: str = "用户") -> dict:
        if user_id not in self._profiles:
            self._profiles[user_id] = {
                "user_id": user_id,
                "username": username,
                "personality_tags": [],
                "work_habits": [],
            }
        return self._profiles[user_id]

    async def get_profile(self, user_id: str) -> UserProfileDto | None:
        profile = self._profiles.get(user_id)
        if not profile:
            return None
        return self._to_user_profile_dto(profile)

    async def add_personality_tag(self, user_id: str, tag: str):
        if user_id not in self._profiles:
            self._profiles[user_id] = {
                "user_id": user_id, "username": "用户",
                "personality_tags": [], "work_habits": [],
            }
        profile = self._profiles[user_id]
        if tag not in profile["personality_tags"]:
            profile["personality_tags"].append(tag)

    async def add_work_habit(self, user_id: str, habit: str):
        if user_id not in self._profiles:
            self._profiles[user_id] = {
                "user_id": user_id, "username": "用户",
                "personality_tags": [], "work_habits": [],
            }
        profile = self._profiles[user_id]
        if habit not in profile["work_habits"]:
            profile["work_habits"].append(habit)

    async def add_mistake(self, user_id: str, keyword: str, description: str = "",
                          task_id: str | None = None):
        if user_id not in self._mistakes:
            self._mistakes[user_id] = []
        self._mistakes[user_id].append({
            "keyword": keyword,
            "description": description,
            "task_id": task_id,
        })

    async def update_profile(self, user_id: str, personality_tags: list[str] | None = None,
                             work_habits: list[str] | None = None):
        if user_id not in self._profiles:
            self._profiles[user_id] = {
                "user_id": user_id, "username": "用户",
                "personality_tags": [], "work_habits": [],
            }
        profile = self._profiles[user_id]
        if personality_tags is not None:
            profile["personality_tags"] = personality_tags
        if work_habits is not None:
            profile["work_habits"] = work_habits