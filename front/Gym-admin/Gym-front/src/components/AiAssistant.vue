<template>
  <div class="ai-assistant">
    <!-- 悬浮 AI 小球 -->
    <div class="float-btn" @click="showChat = !showChat">
      <span class="icon">AI</span>
    </div>

    <!-- 聊天窗口 -->
    <div v-show="showChat" class="chat-panel">
      <!-- 头部 -->
      <div class="chat-header">
        体育场馆智能助手
      </div>

      <!-- 聊天内容 -->
      <div ref="chatBody" class="chat-body">
        <div
          v-for="(msg, idx) in messages"
          :key="idx"
          class="msg-row"
          :class="msg.role"
        >
          <div class="msg-bubble">
            <!-- 普通文本消息 -->
            <div
              v-if="!msg.draft"
              class="msg-content"
            >
              {{ msg.content }}
            </div>

            <!-- 草稿卡片消息 -->
            <div
              v-else
              class="draft-card"
            >
              <div class="draft-title">
                {{ msg.draft.title }}
              </div>

              <!-- 预约草稿 -->
              <template v-if="msg.draft.type === 'BOOKING'">
                <div class="draft-row">
                  <span class="label">场馆</span>
                  <span class="value">{{ msg.draft.venueName || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">场地</span>
                  <span class="value">{{ msg.draft.courtName || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">类型</span>
                  <span class="value">{{ msg.draft.courtType || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">日期</span>
                  <span class="value">{{ msg.draft.date || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">时间</span>
                  <span class="value">
                    {{ msg.draft.startTime || '-' }} - {{ msg.draft.endTime || '-' }}
                  </span>
                </div>

                <div class="draft-row">
                  <span class="label">单价</span>
                  <span class="value">
                    {{ msg.draft.hourPrice || '-' }} 元/小时
                  </span>
                </div>

                <div class="draft-row total">
                  <span class="label">总价</span>
                  <span class="value">
                    ¥{{ msg.draft.totalPrice || '-' }}
                  </span>
                </div>

                <div class="draft-token">
                  确认码：{{ msg.draft.confirmToken }}
                </div>
              </template>

              <!-- 商品下单草稿，预留 -->
              <template v-else-if="msg.draft.type === 'SHOPPING'">
                <div class="draft-row">
                  <span class="label">商品</span>
                  <span class="value">{{ msg.draft.goodsName || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">数量</span>
                  <span class="value">{{ msg.draft.quantity || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">单价</span>
                  <span class="value">{{ msg.draft.price || '-' }}</span>
                </div>

                <div class="draft-row total">
                  <span class="label">总价</span>
                  <span class="value">¥{{ msg.draft.totalPrice || '-' }}</span>
                </div>

                <div class="draft-token">
                  确认码：{{ msg.draft.confirmToken }}
                </div>
              </template>

              <!-- 取消预约草稿 -->
              <template v-else-if="msg.draft.type === 'CANCEL_BOOKING'">
                <div class="draft-row">
                  <span class="label">预约ID</span>
                  <span class="value">{{ msg.draft.bookingId || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">场馆</span>
                  <span class="value">{{ msg.draft.venueName || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">场地</span>
                  <span class="value">{{ msg.draft.courtName || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">日期</span>
                  <span class="value">{{ msg.draft.bookingDate || '-' }}</span>
                </div>

                <div class="draft-row">
                  <span class="label">时间</span>
                  <span class="value">
                    {{ msg.draft.startTime || '-' }} - {{ msg.draft.endTime || '-' }}
                  </span>
                </div>

                <div class="draft-row total">
                  <span class="label">金额</span>
                  <span class="value">¥{{ msg.draft.totalPrice || '-' }}</span>
                </div>

                <div class="draft-token">
                  确认码：{{ msg.draft.confirmToken }}
                </div>
              </template>
            </div>

            <!-- AI 回复中的确认按钮 -->
            <div
              v-if="msg.role === 'ai' && msg.actions && !msg.actionUsed"
              class="action-box"
            >
              <el-button
                type="primary"
                size="small"
                :loading="msg.actionLoading"
                @click="handleConfirmAction(msg)"
              >
                {{ msg.actions.confirmLabel }}
              </el-button>

              <el-button
                size="small"
                :disabled="msg.actionLoading"
                @click="handleCancelAction(msg)"
              >
                取消
              </el-button>
            </div>

            <!-- 用户已经处理过该草稿 -->
            <div
              v-if="msg.role === 'ai' && msg.actions && msg.actionUsed"
              class="action-used"
            >
              已处理该草稿操作
            </div>
          </div>
        </div>
      </div>

      <!-- 底部输入框 -->
      <div class="chat-footer">
        <el-input
          v-model="inputMsg"
          placeholder="提问..."
          :disabled="sending"
          @keyup.enter="send"
        />

        <el-button
          type="primary"
          :loading="sending"
          @click="send"
        >
          发送
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, nextTick, computed } from 'vue'
import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuth } from '@/stores/auth'

/**
 * 接收当前页面上下文。
 *
 * 普通页面：
 * <AiAssistant />
 *
 * 场馆详情页：
 * <AiAssistant :venue-id="venueId" />
 *
 * 场地详情页：
 * <AiAssistant :venue-id="venueId" :court-id="courtId" />
 */
const props = defineProps({
  /**
   * 当前页面场馆ID，可选。
   */
  venueId: {
    type: [Number, String],
    default: null
  },

  /**
   * 当前页面场地ID，可选。
   */
  courtId: {
    type: [Number, String],
    default: null
  }
})

/**
 * 是否显示聊天窗口。
 */
const showChat = ref(false)

/**
 * 输入框消息。
 */
const inputMsg = ref('')

/**
 * 聊天内容容器，用于滚动到底部。
 */
const chatBody = ref(null)

/**
 * 是否正在请求后端。
 */
const sending = ref(false)

const authStore = useAuth()

/**
 * 会话标识的本地存储 key。
 *
 * 同一个浏览器标签页内复用同一个 sessionId，
 * 保证后端会话历史、业务上下文和草稿都落在同一个会话里。
 */
const SESSION_STORAGE_KEY = 'GYM_AI_SESSION_ID'

/**
 * 生成新的会话标识。
 * 后端限制为字母、数字、下划线、短横线，长度不超过 64。
 */
function createSessionId() {
  if (window.crypto && window.crypto.randomUUID) {
    return window.crypto.randomUUID().replace(/-/g, '')
  }

  return 'ai' + Date.now() + Math.random().toString(16).slice(2, 10)
}

/**
 * 当前会话标识。
 * 没有新会话功能时，标签页级别的隔离已经足够。
 */
const sessionId = ref((() => {
  let id = sessionStorage.getItem(SESSION_STORAGE_KEY)

  if (!id) {
    id = createSessionId()
    sessionStorage.setItem(SESSION_STORAGE_KEY, id)
  }

  return id
})())

/**
 * 当前登录用户ID。
 *
 * 后端 Agent 记忆、Redis 草稿、确认码校验都依赖 userId。
 */
const userId = computed(() => authStore.user?.id)

/**
 * 聊天消息列表。
 *
 * 每条消息结构：
 * role: user / ai
 * content: 文本内容
 * draft: 草稿卡片数据
 * actions: 确认按钮动作
 * actionUsed: 是否已处理
 * actionLoading: 按钮 loading
 */
const messages = ref([
  {
    role: 'ai',
    content: '你好！我是体育场馆AI客服，有什么可以帮你？',
    draft: null,
    actions: null,
    actionUsed: false,
    actionLoading: false
  }
])

/**
 * 点击发送按钮或回车发送。
 */
const send = async () => {
  const text = inputMsg.value.trim()

  if (!text) {
    return ElMessage.warning('请输入内容')
  }

  await sendMessage(text)

  inputMsg.value = ''
}

/**
 * 统一发送消息方法。
 *
 * 使用场景：
 * 1. 用户手动输入
 * 2. 点击“确认预约”按钮
 * 3. 点击“确认下单”按钮
 * 4. 点击“取消”按钮
 *
 * @param {string} text 要发送给后端的文本
 */
const sendMessage = async text => {
  if (!text || !text.trim()) {
    return
  }

  if (!userId.value) {
    return ElMessage.warning('请先登录后再使用 AI 助手')
  }

  const msg = text.trim()

  /**
   * 先把用户消息展示出来。
   */
  messages.value.push({
    role: 'user',
    content: msg,
    draft: null,
    actions: null,
    actionUsed: false,
    actionLoading: false
  })

  scrollToBottom()

  sending.value = true

  try {
    const { data } = await axios.post(
      'http://localhost:9601/front/agent/chat',

      /**
       * 后端 @RequestBody AgentChatDto 接收这里：
       * message / venueId / courtId。
       */
      {
        message: msg,
        venueId: props.venueId ? Number(props.venueId) : null,
        courtId: props.courtId ? Number(props.courtId) : null,
        sessionId: sessionId.value
      },

      /**
       * 后端 @RequestParam Long userId 接收这里。
       * token 用于后端校验登录态，身份以登录态为准。
       */
      {
        params: {
          userId: userId.value
        },

        headers: authStore.token
          ? { token: authStore.token }
          : {},

        /**
         * Agent + RAG + 工具调用可能较慢。
         */
        timeout: 120000
      }
    )

    /**
     * 后端明确返回非成功业务码时，展示后端消息。
     * 例如前端传入的 userId 与登录态不一致会被拒绝。
     */
    if (data && data.code && data.code !== 200) {
      messages.value.push({
        role: 'ai',
        content: data.message || '请求未成功，请稍后再试。',
        draft: null,
        actions: null,
        actionUsed: false,
        actionLoading: false
      })

      return
    }

    /**
     * 新版解析逻辑：
     * 1. 优先解析后端 AgentToolExecuteResult JSON
     * 2. 如果不是 JSON，再按旧版纯文本解析
     *
     * 这样可以解决你截图里“JSON 和字段串在一起展示”的问题。
     */
    const parsedReply = parseAgentReply(data)

    messages.value.push({
      role: 'ai',
      content: parsedReply.replyText,
      draft: parsedReply.draft,
      actions: parsedReply.actions,
      actionUsed: false,
      actionLoading: false
    })
  } catch (e) {
    ElMessage.error('连接 AI 服务失败')
    console.error(e)

    messages.value.push({
      role: 'ai',
      content: 'AI服务暂时不可用，请稍后再试。',
      draft: null,
      actions: null,
      actionUsed: false,
      actionLoading: false
    })
  } finally {
    sending.value = false
    scrollToBottom()
  }
}

/**
 * 点击“确认预约 / 确认下单”按钮。
 *
 * 注意：
 * 前端只是自动发送“确认预约 123456”。
 * 真正的 Redis 草稿校验、确认码校验、真实预约执行仍然在后端完成。
 */
const handleConfirmAction = async msg => {
  if (!msg.actions || msg.actionUsed) {
    return
  }

  msg.actionLoading = true

  try {
    /**
     * 先标记已使用，避免用户重复点击。
     */
    msg.actionUsed = true

    await sendMessage(msg.actions.confirmCommand)
  } finally {
    msg.actionLoading = false
  }
}

/**
 * 点击“取消”按钮。
 *
 * 自动发送：
 * 取消
 *
 * 后端会清理当前用户 Redis 草稿。
 */
const handleCancelAction = async msg => {
  if (!msg.actions || msg.actionUsed) {
    return
  }

  msg.actionLoading = true

  try {
    msg.actionUsed = true

    await sendMessage('取消')
  } finally {
    msg.actionLoading = false
  }
}

/**
 * 统一解析后端 Agent 回复。
 *
 * 兼容两种返回：
 * 1. 旧版纯文本：
 *    已为您生成预约草稿：\n场馆：xxx...
 *
 * 2. 新版工具结果 JSON：
 *    {
 *      status,
 *      success,
 *      message,
 *      data,
 *      extra,
 *      needConfirm,
 *      confirmAction
 *    }
 *
 * 返回结构：
 * {
 *   replyText: '给用户看的文本',
 *   draft: 草稿卡片数据,
 *   actions: 确认按钮动作
 * }
 */
function parseAgentReply(responseData) {
  /**
   * 1. 先取出后端 Result 里的真正 data。
   *
   * 你的接口大概率返回：
   * {
   *   code: 200,
   *   data: "..."
   * }
   */
  const rawData = responseData && responseData.data !== undefined
    ? responseData.data
    : responseData

  /**
   * 2. 尝试把 rawData 解析成对象。
   *
   * 支持：
   * - rawData 本来就是对象
   * - rawData 是 JSON 字符串
   * - rawData 是被二次 JSON.stringify 的字符串
   */
  const parsed = parseJsonDeep(rawData)

  /**
   * 3. 如果解析出来的是统一工具执行结果，
   * 就优先按结构化结果处理。
   */
  if (isToolExecuteResult(parsed)) {
    return parseToolExecuteResult(parsed)
  }

  /**
   * 4. 有些情况下，大模型可能把 JSON 工具结果包在普通文本里。
   * 例如：
   * “已为您生成预约草稿：{...json...}”
   *
   * 这里尝试从文本里提取 AgentToolExecuteResult JSON。
   */
  const embeddedToolResult = extractToolExecuteResultFromText(rawData)

  if (embeddedToolResult) {
    return parseToolExecuteResult(embeddedToolResult)
  }

  /**
   * 5. 如果不是统一工具结果，就兼容旧版纯文本。
   */
  const replyText = getPlainReplyText(rawData)

  const actions = parseConfirmActions(replyText)
  const draft = parseDraftCard(replyText, actions)

  return {
    replyText,
    draft,
    actions
  }
}

/**
 * 判断对象是否是后端统一工具执行结果 AgentToolExecuteResult。
 *
 * 典型字段：
 * status / success / message / data / needConfirm / confirmAction / extra
 */
function isToolExecuteResult(obj) {
  return obj
    && typeof obj === 'object'
    && !Array.isArray(obj)
    && (
      Object.prototype.hasOwnProperty.call(obj, 'status')
      || Object.prototype.hasOwnProperty.call(obj, 'needConfirm')
      || Object.prototype.hasOwnProperty.call(obj, 'confirmAction')
    )
}

/**
 * 解析统一工具执行结果。
 *
 * 重点：
 * 1. replyText 用 result.message
 * 2. 草稿卡片优先从 result.data 取
 * 3. 确认码优先从 result.extra.confirmToken 取
 */
function parseToolExecuteResult(result) {
  const message = normalizeText(result.message || '工具执行完成。')

  const actions = parseActionsFromToolResult(result, message)

  let draft = null

  /**
   * 只有需要确认时，才尝试构造草稿卡片。
   */
  if (result.needConfirm || result.status === 'NEED_CONFIRM') {
    if (actions && actions.type === 'BOOKING') {
      draft = parseBookingDraftFromToolResult(result, actions)
    } else if (actions && actions.type === 'SHOPPING') {
      draft = parseShoppingDraftFromToolResult(result, actions)
    } else if (actions && actions.type === 'CANCEL_BOOKING') {
      draft = parseCancelDraftFromToolResult(result, actions)
    }
  }

  return {
    /**
     * 有草稿卡片时，模板里 v-if="!msg.draft" 不会展示 content。
     * 这里仍然保留 message，方便没有 draft 时兜底展示。
     */
    replyText: message,
    draft,
    actions
  }
}

/**
 * 从统一工具结果中解析确认动作。
 *
 * 后端现在可能返回：
 * confirmAction = "confirm_booking"
 * extra.confirmToken = "587415"
 *
 * 前端要转换成真正发给后端的中文命令：
 * 确认预约 587415
 */
function parseActionsFromToolResult(result, message) {
  const extra = toPlainObject(result.extra)
  const token = extra.confirmToken || extractTokenFromText(message)

  if (!token) {
    return null
  }

  if (result.confirmAction === 'confirm_booking') {
    return {
      type: 'BOOKING',
      token,
      confirmLabel: '确认预约',
      confirmCommand: `确认预约 ${token}`
    }
  }

  if (result.confirmAction === 'confirm_shopping' || result.confirmAction === 'confirm_shopping_order') {
    return {
      type: 'SHOPPING',
      token,
      confirmLabel: '确认下单',
      confirmCommand: `确认下单 ${token}`
    }
  }

  /**
   * 取消预约草稿：
   * 后端 confirmAction = confirm_cancel_booking，
   * 前端转换成后端确认流程能识别的中文命令。
   */
  if (result.confirmAction === 'confirm_cancel_booking') {
    return {
      type: 'CANCEL_BOOKING',
      token,
      confirmLabel: '确认取消预约',
      confirmCommand: `确认取消预约 ${token}`
    }
  }

  /**
   * 如果 confirmAction 没传，但是文本里出现了“确认预约”，也按预约处理。
   */
  if (message.includes('确认预约')) {
    return {
      type: 'BOOKING',
      token,
      confirmLabel: '确认预约',
      confirmCommand: `确认预约 ${token}`
    }
  }

  /**
   * 如果文本里出现了“确认下单”，按商城处理。
   */
  if (message.includes('确认下单')) {
    return {
      type: 'SHOPPING',
      token,
      confirmLabel: '确认下单',
      confirmCommand: `确认下单 ${token}`
    }
  }

  return null
}

/**
 * 从统一工具结果中构造预约草稿卡片。
 *
 * 后端 data 里字段通常是：
 * venueName
 * courtName
 * courtType
 * date
 * startTime
 * endTime
 * hoursPrice
 * totalPrice
 *
 * 前端卡片字段是：
 * venueName
 * courtName
 * courtType
 * date
 * startTime
 * endTime
 * hourPrice
 * totalPrice
 */
function parseBookingDraftFromToolResult(result, actions) {
  const data = toPlainObject(result.data)
  const extra = toPlainObject(result.extra)

  return {
    type: 'BOOKING',
    title: '预约草稿',

    /**
     * 优先从结构化 data 里取字段。
     * 不再从 JSON 字符串里用正则硬抠。
     */
    venueName: data.venueName || '-',
    courtName: data.courtName || '-',
    courtType: data.courtType || '-',
    date: data.date || data.bookingDate || '-',
    startTime: data.startTime || '-',
    endTime: data.endTime || '-',

    /**
     * 后端字段可能叫 hoursPrice，前端展示叫 hourPrice。
     */
    hourPrice: data.hourPrice || data.hoursPrice || '-',
    totalPrice: data.totalPrice || '-',

    /**
     * 确认码优先从 extra.confirmToken 取。
     */
    confirmToken: extra.confirmToken || actions.token
  }
}

/**
 * 从统一工具结果中构造商品下单草稿卡片。
 *
 * 当前先做兼容，后面按你的商品工具真实返回字段调整。
 */
function parseShoppingDraftFromToolResult(result, actions) {
  const data = toPlainObject(result.data)
  const extra = toPlainObject(result.extra)

  return {
    type: 'SHOPPING',
    title: '商品下单草稿',
    goodsName: data.goodsName || data.productName || '-',
    quantity: data.quantity || '-',
    price: data.price || data.unitPrice || '-',
    totalPrice: data.totalPrice || '-',
    confirmToken: extra.confirmToken || actions.token
  }
}

/**
 * 从统一工具结果中构造取消预约草稿卡片。
 *
 * 后端 data 字段：
 * bookingId / venueName / courtName / bookingDate / startTime / endTime / totalPrice
 */
function parseCancelDraftFromToolResult(result, actions) {
  const data = toPlainObject(result.data)
  const extra = toPlainObject(result.extra)

  return {
    type: 'CANCEL_BOOKING',
    title: '取消预约草稿',
    bookingId: data.bookingId || '-',
    venueName: data.venueName || '-',
    courtName: data.courtName || '-',
    bookingDate: data.bookingDate || data.date || '-',
    startTime: data.startTime || '-',
    endTime: data.endTime || '-',
    totalPrice: data.totalPrice || '-',
    confirmToken: extra.confirmToken || actions.token
  }
}

/**
 * 深度解析 JSON。
 *
 * 为什么需要这个：
 * 有时候后端返回的是对象；
 * 有时候返回的是 JSON 字符串；
 * 有时候大模型/后端又把 JSON 字符串包了一层。
 *
 * 这个函数会尽量解析 2 次，避免双重 JSON 字符串导致前端拿不到字段。
 */
function parseJsonDeep(value) {
  let current = value

  for (let i = 0; i < 2; i++) {
    if (typeof current !== 'string') {
      return current
    }

    const text = current.trim()

    if (!text) {
      return current
    }

    /**
     * 去掉可能出现的 markdown 代码块。
     */
    const cleaned = text
      .replace(/^```json\s*/i, '')
      .replace(/^```\s*/i, '')
      .replace(/```$/i, '')
      .trim()

    /**
     * 只有明显是 JSON 对象或数组时才解析。
     */
    if (!(
      (cleaned.startsWith('{') && cleaned.endsWith('}'))
      || (cleaned.startsWith('[') && cleaned.endsWith(']'))
    )) {
      return current
    }

    try {
      current = JSON.parse(cleaned)
    } catch (e) {
      return current
    }
  }

  return current
}

/**
 * 把任意值转换成普通对象。
 *
 * 支持：
 * 1. 本身就是对象
 * 2. JSON 字符串
 * 3. 空值
 */
function toPlainObject(value) {
  if (!value) {
    return {}
  }

  if (typeof value === 'object' && !Array.isArray(value)) {
    return value
  }

  const parsed = parseJsonDeep(value)

  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    return parsed
  }

  return {}
}

/**
 * 从普通文本里提取 AgentToolExecuteResult JSON。
 *
 * 解决这种情况：
 * 大模型把工具 JSON 包进了自然语言里，导致整个返回不是合法 JSON，
 * 但中间仍然包含 { "status": "NEED_CONFIRM", ... }。
 */
function extractToolExecuteResultFromText(value) {
  if (typeof value !== 'string') {
    return null
  }

  const text = normalizeText(value)

  /**
   * 先尝试原文本。
   */
  const result1 = extractJsonObjects(text).find(isToolExecuteResult)
  if (result1) {
    return result1
  }

  /**
   * 再尝试把 \" 还原成 "。
   * 有些后端/模型会把 JSON 双重转义。
   */
  const unescaped = text.replace(/\\"/g, '"')
  const result2 = extractJsonObjects(unescaped).find(isToolExecuteResult)

  return result2 || null
}

/**
 * 从一段文本中提取所有平衡的 JSON 对象。
 *
 * 说明：
 * 这里不是简单正则，因为 JSON 里可能有嵌套对象。
 * 所以用括号计数的方式提取 {...}。
 */
function extractJsonObjects(text) {
  const results = []

  if (!text) {
    return results
  }

  let start = -1
  let depth = 0
  let inString = false
  let escaped = false

  for (let i = 0; i < text.length; i++) {
    const ch = text[i]

    if (escaped) {
      escaped = false
      continue
    }

    if (ch === '\\') {
      escaped = true
      continue
    }

    if (ch === '"') {
      inString = !inString
      continue
    }

    if (inString) {
      continue
    }

    if (ch === '{') {
      if (depth === 0) {
        start = i
      }
      depth++
    }

    if (ch === '}') {
      depth--

      if (depth === 0 && start !== -1) {
        const candidate = text.slice(start, i + 1)

        try {
          const parsed = JSON.parse(candidate)
          results.push(parsed)
        } catch (e) {
          /**
           * 不是合法 JSON，忽略。
           */
        }

        start = -1
      }
    }
  }

  /**
   * 优先返回后出现的 JSON。
   * 因为模型有时前面会输出一些非关键对象，最后一个才是工具结果。
   */
  return results.reverse()
}

/**
 * 获取普通文本回复。
 *
 * 用于兼容旧版后端返回：
 * 1. { data: "xxx" }
 * 2. { data: { answer: "xxx" } }
 * 3. "xxx"
 */
function getPlainReplyText(responseData) {
  if (!responseData) {
    return '暂时没有返回内容。'
  }

  const data = responseData && responseData.data !== undefined
    ? responseData.data
    : responseData

  if (typeof data === 'string') {
    return normalizeText(data)
  }

  if (data && typeof data.answer === 'string') {
    return normalizeText(data.answer)
  }

  if (typeof responseData === 'string') {
    return normalizeText(responseData)
  }

  return normalizeText(JSON.stringify(data || responseData))
}

/**
 * 把字符串里的转义换行转换成真实换行。
 *
 * 例如：
 * "\\n" -> "\n"
 *
 * 这样旧版正则解析时才能按行截断。
 */
function normalizeText(text) {
  if (text === null || text === undefined) {
    return ''
  }

  return String(text)
    .replace(/\\r\\n/g, '\n')
    .replace(/\\n/g, '\n')
    .replace(/\\r/g, '\n')
    .trim()
}

/**
 * 从 AI 回复文本中解析确认按钮动作。
 *
 * 当前支持：
 * 1. 确认预约 536348
 * 2. 确认预约：536348
 * 3. 确认下单 536348
 */
function parseConfirmActions(text) {
  if (!text) {
    return null
  }

  const normalizedText = normalizeText(text)

  /**
   * 匹配预约确认：
   * 确认预约 536348
   * 确认预约：536348
   * 确认预约: 536348
   */
  const bookingMatch = normalizedText.match(/确认预约\s*[:：]?\s*(\d{6})/)

  if (bookingMatch && bookingMatch[1]) {
    return {
      type: 'BOOKING',
      token: bookingMatch[1],
      confirmLabel: '确认预约',
      confirmCommand: `确认预约 ${bookingMatch[1]}`
    }
  }

  /**
   * 匹配商品下单确认：
   * 确认下单 536348
   * 确认下单：536348
   */
  const shoppingMatch = normalizedText.match(/确认下单\s*[:：]?\s*(\d{6})/)

  if (shoppingMatch && shoppingMatch[1]) {
    return {
      type: 'SHOPPING',
      token: shoppingMatch[1],
      confirmLabel: '确认下单',
      confirmCommand: `确认下单 ${shoppingMatch[1]}`
    }
  }

  return null
}

/**
 * 从文本里提取 6 位确认码。
 */
function extractTokenFromText(text) {
  if (!text) {
    return ''
  }

  const normalizedText = normalizeText(text)

  const tokenMatch = normalizedText.match(/确认码\s*[:：]?\s*(\d{6})/)

  if (tokenMatch && tokenMatch[1]) {
    return tokenMatch[1]
  }

  const bookingMatch = normalizedText.match(/确认预约\s*[:：]?\s*(\d{6})/)

  if (bookingMatch && bookingMatch[1]) {
    return bookingMatch[1]
  }

  const shoppingMatch = normalizedText.match(/确认下单\s*[:：]?\s*(\d{6})/)

  if (shoppingMatch && shoppingMatch[1]) {
    return shoppingMatch[1]
  }

  return ''
}

/**
 * 解析草稿卡片。
 *
 * 这是旧版纯文本兜底解析。
 * 新版 JSON 工具结果会优先走 parseBookingDraftFromToolResult。
 *
 * @param {string} text AI 回复文本
 * @param {Object|null} actions 确认动作
 */
function parseDraftCard(text, actions) {
  if (!text || !actions) {
    return null
  }

  if (actions.type === 'BOOKING') {
    return parseBookingDraft(text, actions)
  }

  if (actions.type === 'SHOPPING') {
    return parseShoppingDraft(text, actions)
  }

  return null
}

/**
 * 解析旧版预约草稿文本。
 *
 * 支持后端文本：
 * 场馆：xxx
 * 场地：xxx
 * 类型：xxx
 * 日期：xxx
 * 开始时间：xxx
 * 结束时间：xxx
 * 单小时价格：xxx
 * 总价：xxx
 * 确认码：xxx
 */
function parseBookingDraft(text, actions) {
  const normalizedText = normalizeText(text)

  return {
    type: 'BOOKING',
    title: '预约草稿',
    venueName: extractField(normalizedText, '场馆') || extractField(normalizedText, '场馆名称') || '-',
    courtName: extractField(normalizedText, '场地') || extractField(normalizedText, '场地名称') || '-',
    courtType: extractField(normalizedText, '类型') || extractField(normalizedText, '场地类型') || '-',
    date: extractField(normalizedText, '日期') || extractField(normalizedText, '预约日期') || '-',
    startTime: extractField(normalizedText, '开始时间') || '-',
    endTime: extractField(normalizedText, '结束时间') || '-',
    hourPrice: extractField(normalizedText, '单小时价格') || extractField(normalizedText, '单价') || '-',
    totalPrice: extractField(normalizedText, '总价') || '-',
    confirmToken: extractField(normalizedText, '确认码') || actions.token
  }
}

/**
 * 解析旧版商品下单草稿文本。
 *
 * 这里先兼容常见字段。
 * 如果你的后端商品草稿字段不一样，后面按实际返回文本扩展。
 */
function parseShoppingDraft(text, actions) {
  const normalizedText = normalizeText(text)

  return {
    type: 'SHOPPING',
    title: '商品下单草稿',
    goodsName: extractField(normalizedText, '商品') || extractField(normalizedText, '商品名称') || '-',
    quantity: extractField(normalizedText, '数量') || extractField(normalizedText, '购买数量') || '-',
    price: extractField(normalizedText, '单价') || extractField(normalizedText, '商品单价') || '-',
    totalPrice: extractField(normalizedText, '总价') || extractField(normalizedText, '合计') || '-',
    confirmToken: extractField(normalizedText, '确认码') || actions.token
  }
}

/**
 * 从纯文本中提取字段值。
 *
 * 支持：
 * 场馆：沈阳航空航天大学体育馆
 * 场馆: 沈阳航空航天大学体育馆
 *
 * 关键修复：
 * 用 normalizeText 把 "\\n" 变成真实换行，
 * 再用 [^\n\r]+ 截断，避免字段串到后面的 JSON。
 *
 * @param {string} text 原始文本
 * @param {string} fieldName 字段名
 */
function extractField(text, fieldName) {
  if (!text || !fieldName) {
    return ''
  }

  const normalizedText = normalizeText(text)
  const reg = new RegExp(`${fieldName}\\s*[:：]\\s*([^\\n\\r]+)`)
  const match = normalizedText.match(reg)

  if (!match || !match[1]) {
    return ''
  }

  return cleanFieldValue(match[1])
}

/**
 * 清理字段值。
 */
function cleanFieldValue(value) {
  if (!value) {
    return ''
  }

  return String(value)
    .replace(/\r/g, '')
    .replace(/\n/g, '')
    .replace(/\\n/g, '')
    .replace(/"/g, '')
    .replace(/,$/, '')
    .trim()
}

/**
 * 滚动到底部。
 */
function scrollToBottom() {
  nextTick(() => {
    if (chatBody.value) {
      chatBody.value.scrollTop = chatBody.value.scrollHeight
    }
  })
}
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 99999;
}

.float-btn {
  width: 60px;
  height: 60px;
  background: #409eff;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-weight: bold;
  cursor: pointer;
  box-shadow: 0 4px 8px #0002;
}

.float-btn:hover {
  background: #337ecc;
}

.icon {
  font-size: 16px;
}

.chat-panel {
  position: absolute;
  right: 0;
  bottom: 70px;
  width: 390px;
  height: 560px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 4px 20px #0002;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.chat-header {
  padding: 14px;
  background: #409eff;
  color: #fff;
  font-weight: bold;
  font-size: 15px;
}

.chat-body {
  flex: 1;
  padding: 14px;
  overflow-y: auto;
  background: #f5f5f5;
}

/**
 * 消息行。
 */
.msg-row {
  display: flex;
  margin-bottom: 12px;
}

/**
 * AI 消息靠左。
 */
.msg-row.ai {
  justify-content: flex-start;
}

/**
 * 用户消息靠右。
 */
.msg-row.user {
  justify-content: flex-end;
}

/**
 * 消息气泡。
 */
.msg-bubble {
  max-width: 82%;
  padding: 8px 12px;
  border-radius: 12px;
  line-height: 1.6;
  word-break: break-word;
  white-space: pre-wrap;
}

/**
 * AI 气泡。
 */
.msg-row.ai .msg-bubble {
  background: #fff;
  color: #303133;
  border: 1px solid #e4e7ed;
}

/**
 * 用户气泡。
 */
.msg-row.user .msg-bubble {
  background: #409eff;
  color: #fff;
}

/**
 * 普通文本内容。
 */
.msg-content {
  white-space: pre-wrap;
}

/**
 * 草稿卡片。
 */
.draft-card {
  min-width: 270px;
  background: #ffffff;
  border-radius: 10px;
  border: 1px solid #e4e7ed;
  overflow: hidden;
}

/**
 * 草稿标题。
 */
.draft-title {
  padding: 10px 12px;
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
  border-bottom: 1px solid #d9ecff;
}

/**
 * 草稿行。
 */
.draft-row {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 12px;
  border-bottom: 1px solid #f0f2f5;
  font-size: 13px;
}

/**
 * 草稿字段名。
 */
.draft-row .label {
  color: #909399;
  flex-shrink: 0;
}

/**
 * 草稿字段值。
 */
.draft-row .value {
  color: #303133;
  text-align: right;
  word-break: break-word;
}

/**
 * 总价行。
 */
.draft-row.total .value {
  color: #f56c6c;
  font-weight: 600;
}

/**
 * 确认码区域。
 */
.draft-token {
  padding: 10px 12px;
  background: #fdf6ec;
  color: #e6a23c;
  font-size: 13px;
  font-weight: 600;
}

/**
 * 按钮区域。
 */
.action-box {
  margin-top: 10px;
  display: flex;
  gap: 8px;
}

/**
 * 已处理提示。
 */
.action-used {
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

.chat-footer {
  display: flex;
  padding: 10px;
  gap: 8px;
  background: #fff;
  border-top: 1px solid #ebeef5;
}
</style>