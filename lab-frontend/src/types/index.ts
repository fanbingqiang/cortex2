export interface KnowledgeNode { id: string; name: string; description?: string; path?: string; children?: KnowledgeNode[]; leaf: boolean; type?: 'project' | 'concept' | 'command' | 'algorithm' | null }
export interface QuestionDto { id: number; title: string; description?: string; trapCode?: string; expectedPitfall?: string; correctExplanation?: string; hints?: string; category?: string; difficulty?: number; status?: string; mastered?: boolean | null; reviewCount?: number | null; nextReviewTime?: string | null; gmtCreate?: string }
export interface CardDto { id: number; questionId: number; questionTitle?: string; title?: string; knowledgePath?: string; keyPoints?: string; detailExplanation?: string; codeSnippet?: string; commonPitfalls?: string; gmtCreate?: string }
export interface CommunityTrapDto { id: number; title: string; knowledgePoint?: string; javaVersion: string; category: string; trapCode: string; expectedPitfall?: string; correctExplanation?: string; hints?: string; difficulty: number; submitter: string; voteCount: number; status: string }
export interface CommunityTrapSubmitRequest { title: string; knowledgePoint?: string; category?: string; javaVersion?: string; trapCode: string; expectedPitfall?: string; correctExplanation?: string; hints?: string; difficulty?: number }
export interface DiscussionDto { id: number; questionId: number; parentId?: number | null; userId: string; content: string }
export interface ScenarioDto { id?: number; knowledgePoint: string; category?: string; trapCode?: string; expectedPitfall?: string; correctExplanation?: string; hints?: string; difficulty?: number; type?: 'trap' | 'concept' | 'command' | 'algorithm'; generatedContent?: string }
export interface AssistantConversation { conversationId: string; title?: string; messageCount?: number }
export interface AssistantMessage { id?: number; conversationId: string; role: 'user' | 'assistant'; content: string }
export interface GlobalChatRequest { conversationId?: string; message: string; userId: string; currentCode?: string; knowledgePoint?: string }
export interface ApiResponse<T> { code: number; message?: string; data?: T }
export interface AuthResponse { token: string; userId: string; username: string; role?: string }
export interface UserInfoDTO { userId: string; username: string; skillLevel?: string; totalQuestionsAnswered?: number; totalCorrect?: number; accuracy?: number; studyStreak?: number; role?: string }
export interface ProjectFileDTO { path: string; content: string }
export interface ProjectInfoDTO { projectName: string; files: ProjectFileDTO[] }
export interface ExecuteResponse { stdout: string; stderr: string; exitCode: number; success: boolean; error: string | null }
export interface AiAction { type: string; payload?: any }

export interface GraphNode { nodeId: string; nodeName: string; relationType: string; weight: number; mastered: boolean; leaf: boolean }
export interface GraphData { centerNode: {id: string; name: string}; relations: GraphNode[] }
export interface ModeInfo { key: string; label: string; icon: string }