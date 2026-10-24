<script setup>
import { computed, onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { useLiveStore } from '../stores/live'
import { get, post } from '../api'

const route = useRoute()
const store = useLiveStore()

const form = reactive({
  deviceId: route.query.device || 'T-01',
  function: Number(route.query.fc || 6),
  address: Number(route.query.addr || 8),
  values: route.query.values || '250',
  simulate: 'ok',
  note: route.query.note || '调整设定值'
})

const frame = ref('')
const command = ref(null)
const activeStep = ref(-1)
const result = ref('尚未下发')
const error = ref('')
let previewTimer = null
let animTimer = null

const payload = computed(() => ({
  deviceId: form.deviceId,
  function: Number(form.function),
  address: Number(form.address),
  values: String(form.values)
    .split(',')
    .map((value) => Number(value.trim()))
    .filter((value) => !Number.isNaN(value)),
  simulate: form.simulate,
  note: form.note
}))

const resultClass = computed(() => {
  if (!command.value) return ''
  return command.value.status === 'SUCCESS' ? 'ok' : 'err'
})

const recent = computed(() => (store.commands || []).slice(0, 8))
const selectedDevice = computed(() => store.deviceMap[form.deviceId])
const isReal = computed(() => Boolean(selectedDevice.value?.real))

// 选中设备后拉取点表编组，供“可写点位”下拉使用
const deviceGroups = ref([])
async function loadDetail() {
  try {
    const detail = await get(`/devices/${form.deviceId}`)
    deviceGroups.value = detail.groups
  } catch {
    deviceGroups.value = []
  }
}
const writableGroups = computed(() => deviceGroups.value.filter((item) => item.rw))

/** 从可写点位下拉选择后，自动填功能码/地址/当前值。 */
function pickPoint(key) {
  const target = deviceGroups.value.find((item) => item.key === key)
  if (!target) return
  form.function = target.count === 1 ? 6 : 16
  form.address = target.start
  form.values = target.cells.map((cell) => cell.value).join(',')
  form.note = `写 ${target.name}`
  schedulePreview()
}

const defaultSteps = [
  { name: '入队', note: '写命令进入通道队列，优先级高于轮询' },
  { name: '通道令牌', note: '等待当前事务结束，通道内串行' },
  { name: '发送请求', note: '生成 RTU 帧并发送' },
  { name: '从站处理', note: '执行写入并返回回显帧' },
  { name: '校验响应', note: 'CRC 正确，功能码匹配' },
  { name: '更新缓存', note: '写缓存、推 WebSocket、记审计日志' }
]

async function preview() {
  try {
    const response = await post('/commands/preview', payload.value)
    frame.value = response.frame
    error.value = ''
  } catch (e) {
    frame.value = ''
    error.value = String(e.message || e)
  }
}

function schedulePreview() {
  clearTimeout(previewTimer)
  previewTimer = setTimeout(preview, 180)
}

async function submit() {
  clearInterval(animTimer)
  command.value = null
  activeStep.value = -1
  result.value = '等待通道令牌…'
  error.value = ''
  try {
    const response = await post('/commands', payload.value)
    command.value = response
    if (!store.commands.some((item) => item.id === response.id)) {
      store.commands.unshift(response)
    }
    animate(response)
  } catch (e) {
    error.value = String(e.message || e)
    result.value = '下发失败'
  }
}

function animate(response) {
  let index = 0
  activeStep.value = 0
  animTimer = setInterval(() => {
    if (index < response.steps.length - 1) {
      index += 1
      activeStep.value = index
      return
    }
    clearInterval(animTimer)
    activeStep.value = response.steps.length
    result.value =
      response.status === 'SUCCESS'
        ? `写入成功 · ${response.latencyMs} ms · ${response.message}`
        : response.message
  }, 420)
}

function stepClass(index) {
  if (!command.value) return ''
  if (index === activeStep.value) return 'on'
  if (index < activeStep.value) {
    if (command.value.status === 'FAILED' && index === activeStep.value - 1) return 'err'
    return 'done'
  }
  return ''
}

watch(() => [form.deviceId, form.function, form.address, form.values], schedulePreview)

watch(
  () => form.deviceId,
  () => loadDetail()
)

watch(
  () => route.query,
  (query) => {
    if (!query.device) return
    form.deviceId = query.device
    form.function = Number(query.fc || 6)
    form.address = Number(query.addr || 8)
    form.values = query.values || ''
    form.note = query.note || ''
    preview()
  }
)

watch(
  () => store.devices.length,
  () => {
    if (!store.devices.some((item) => item.id === form.deviceId) && store.devices[0]) {
      form.deviceId = store.devices[0].id
    }
  }
)

onMounted(() => {
  preview()
  loadDetail()
})
onUnmounted(() => {
  clearTimeout(previewTimer)
  clearInterval(animTimer)
})
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>指令下发</h2>
        <p>写单个寄存器 / 写多个寄存器，带校验、重试与审计</p>
      </div>
      <span class="mp-pill">{{ command ? command.status : '空闲' }}</span>
    </div>

    <div v-if="error" class="mp-band" style="border-left-color: var(--bad)">{{ error }}</div>

    <div class="mp-split">
      <form class="mp-panel mp-form" @submit.prevent="submit">
        <div class="mp-panel-h">
          <h3>新建写命令</h3>
          <span>目标设备</span>
        </div>
        <label class="mp-field">
          <span>可写点位（来自点表）</span>
          <select class="mp-select" @change="pickPoint($event.target.value)">
            <option value="">手动填写</option>
            <option v-for="item in writableGroups" :key="item.key" :value="item.key">
              {{ item.name }} · 0x{{ item.start.toString(16).toUpperCase() }}
            </option>
          </select>
        </label>
        <div class="mp-form-row">
          <label class="mp-field">
            <span>设备</span>
            <select v-model="form.deviceId" class="mp-select">
              <option v-for="item in store.devices" :key="item.id" :value="item.id">
                {{ item.id }} · {{ item.name }}
              </option>
            </select>
          </label>
          <label class="mp-field">
            <span>功能码</span>
            <select v-model.number="form.function" class="mp-select">
              <option :value="6">06 写单个寄存器</option>
              <option :value="16">16 写多个寄存器</option>
            </select>
          </label>
        </div>
        <div class="mp-form-row">
          <label class="mp-field">
            <span>寄存器地址（十进制）</span>
            <input v-model="form.address" class="mp-input" type="number" min="0" max="65535" />
          </label>
          <label class="mp-field">
            <span>写入值（多个用逗号）</span>
            <input v-model="form.values" class="mp-input" />
          </label>
        </div>
        <div class="mp-form-row">
          <label class="mp-field">
            <span>模拟结果</span>
            <select v-model="form.simulate" class="mp-select" :disabled="isReal">
              <option value="ok">正常响应</option>
              <option value="timeout">从站超时</option>
              <option value="exception">异常码 02</option>
            </select>
          </label>
          <label class="mp-field">
            <span>备注</span>
            <input v-model="form.note" class="mp-input" />
          </label>
        </div>
        <div>
          <span class="mp-note">
            将发送的报文帧（{{ selectedDevice?.transport || 'RTU' }}）
            <template v-if="isReal"> · 真实设备忽略模拟结果，以回显和回读为准</template>
          </span>
          <code class="mp-frame">{{ frame || '参数不合法' }}</code>
        </div>
        <div style="display: flex; gap: 8px; flex-wrap: wrap; align-items: center">
          <button type="submit" class="mp-btn primary">下发指令</button>
          <span class="mp-note">写命令优先级高于轮询；超时重试 3 次。</span>
        </div>
      </form>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>执行时间线</h3>
          <span>{{ command?.traceId || '--' }}</span>
        </div>
        <ol class="mp-timeline">
          <li v-for="(step, index) in command?.steps || []" :key="index" :class="stepClass(index)">
            <span class="node"></span>
            <div>
              <b>{{ step.name }}</b>
              <span>{{ step.note }}</span>
            </div>
          </li>
          <template v-if="!command">
            <li v-for="(step, index) in defaultSteps" :key="'default-' + index">
              <span class="node"></span>
              <div>
                <b>{{ step.name }}</b
                ><span>{{ step.note }}</span>
              </div>
            </li>
          </template>
        </ol>
        <div class="mp-result" :class="resultClass">{{ result }}</div>
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-panel-h">
        <h3>最近命令</h3>
        <span>审计留痕</span>
      </div>
      <table class="mp-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>设备</th>
            <th>功能码</th>
            <th>地址</th>
            <th>结果</th>
            <th class="mp-hide-sm">耗时</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in recent" :key="item.id">
            <td class="num">{{ new Date(item.ts).toLocaleTimeString('zh-CN', { hour12: false }) }}</td>
            <td class="num">{{ item.deviceId }}</td>
            <td class="num">{{ item.function === 6 ? '06' : '16' }}</td>
            <td class="num">{{ item.address }}</td>
            <td :class="item.status === 'SUCCESS' ? 'ok' : 'bad'">
              {{ item.status === 'SUCCESS' ? '成功' : '失败' }}
            </td>
            <td class="num mp-hide-sm">{{ item.latencyMs }} ms</td>
          </tr>
          <tr v-if="!recent.length">
            <td colspan="6" class="mp-note">还没有命令</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
