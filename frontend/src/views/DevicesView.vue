<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useLiveStore } from '../stores/live'
import { get } from '../api'

const route = useRoute()
const router = useRouter()
const store = useLiveStore()

const detail = ref(null)
const groupKey = ref('')
const readStatus = ref('点“读这一组”看请求与响应')
const longStep = ref(0)
const error = ref('')
const loading = ref(false)
let lastLoad = 0
let readToken = 0

const longSteps = ['编码', '打包', '分片', '写控制', '写片 1', '写片 2', '拼接解码']
const longNotes = [
  '① 先编码成字节流，长度单独记下来',
  '② 两字节一个寄存器；奇数长度补 00',
  '③ 按 16 个寄存器一片切开',
  '④ 先写控制区：长度、片数、状态、CRC',
  '⑤ 写片 1：16 个寄存器，32 字节',
  '⑥ 写片 2：剩余寄存器 + 填充',
  '⑦ 先读长度，再按长度截断；填充的 00 不能当字符'
]

const id = computed(() => route.params.id || store.devices[0]?.id || 'T-01')
const selectedId = computed({
  get: () => id.value,
  set: (value) => router.push(`/devices/${value}`)
})
const live = computed(() => store.deviceMap[id.value])
const device = computed(() => live.value || detail.value?.device || null)
const devicePoints = computed(() => device.value?.points || [])
const groups = computed(() => detail.value?.groups || [])
const group = computed(
  () => groups.value.find((item) => item.key === groupKey.value) || groups.value[0] || null
)
const longString = computed(() => detail.value?.longString || null)

/** 寄存器地图按点表展开：每个点位占 count 个格子。 */
const mapCells = computed(() => {
  const cells = []
  for (const item of groups.value) {
    for (let address = item.start; address < item.start + item.count; address++) {
      cells.push({
        address,
        label: address.toString(16).toUpperCase().padStart(2, '0'),
        group: item.key,
        rw: item.rw
      })
    }
  }
  return cells
})

const letters = computed(() => {
  const count = (group.value?.cells.length || 0) * 2
  return 'ABCDEFGH'.slice(0, count).split('').join(' ')
})

const longBytes = computed(() => {
  if (!longString.value) return []
  const encoded = new TextEncoder().encode(longString.value.value)
  return Array.from(encoded).map((byte) => byte.toString(16).toUpperCase().padStart(2, '0'))
})

const longExamples = computed(() => {
  if (!longString.value) return []
  const regs = longString.value.registers
  const picks = regs.slice(0, 3)
  if (regs.length > 3) picks.push(regs[regs.length - 1])
  return picks
})

const longResponsePreview = computed(() => {
  if (!longString.value) return ''
  return longString.value.readResponse.split(' ').slice(0, 16).join(' ') + ' …'
})

function hex4(value) {
  return (value & 0xffff).toString(16).toUpperCase().padStart(4, '0')
}

function fmtValue(value, type) {
  const number = Number(value)
  return type === 'float32' ? number.toFixed(2) : String(Number(number.toFixed(2)))
}

async function load() {
  if (loading.value) return
  loading.value = true
  try {
    detail.value = await get(`/devices/${id.value}`)
    if (!groups.value.some((item) => item.key === groupKey.value)) {
      groupKey.value = groups.value[0]?.key || ''
    }
    lastLoad = Date.now()
    error.value = ''
  } catch (e) {
    error.value = String(e.message || e)
  } finally {
    loading.value = false
  }
}

function selectGroup(key) {
  groupKey.value = key
  readStatus.value = '点“读这一组”看请求与响应'
}

function readGroup() {
  const current = group.value
  if (!current) return
  const token = ++readToken
  readStatus.value = `① 请求 count=${current.count}：${current.readRequest}`
  setTimeout(() => {
    if (token !== readToken) return
    readStatus.value = `② 响应 ${current.cells.length * 2} 字节：${current.readResponse}`
  }, 500)
  setTimeout(() => {
    if (token !== readToken) return
    readStatus.value = `③ 解码：${current.value}（${current.name}，${current.count} 个寄存器）`
  }, 1000)
}

function selectAndRead(key) {
  selectGroup(key)
  readGroup()
}

function writeGroup() {
  const current = group.value
  if (!current || !current.rw) return
  router.push({
    path: '/commands',
    query: {
      device: id.value,
      fc: current.count === 1 ? 6 : 16,
      addr: current.start,
      values: current.cells.map((cell) => cell.value).join(','),
      note: `写 ${current.name}`
    }
  })
}

function selectAndWrite(key) {
  selectGroup(key)
  writeGroup()
}

function longClass(index) {
  if (longStep.value === 2) return index < 16 ? 'a' : 'b'
  if (longStep.value === 4 && index < 16) return 'a'
  if (longStep.value === 5 && index >= 16) return 'b'
  if (longStep.value === 6) return 'done'
  return ''
}

onMounted(load)

watch(id, () => {
  groupKey.value = ''
  longStep.value = 0
  readStatus.value = '点“读这一组”看请求与响应'
  load()
})

watch(
  () => store.tick,
  () => {
    if (Date.now() - lastLoad > 2000) load()
  }
)
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap">
        <h2>设备与传感器</h2>
        <select v-model="selectedId" class="mp-select">
          <option v-for="item in store.devices" :key="item.id" :value="item.id">
            {{ item.id }} · {{ item.name }}
          </option>
        </select>
      </div>
      <span class="mp-pill">
        <span class="mp-dot" :class="{ warn: device?.quality !== 'good' }"></span>
        {{ device?.quality === 'good' ? '在线' : device?.quality || 'unknown' }}
      </span>
    </div>

    <div v-if="error" class="mp-band" style="border-left-color: var(--bad)">{{ error }}</div>

    <div class="mp-panel">
      <div class="mp-panel-h" style="margin-bottom: 0; align-items: flex-start">
        <div>
          <div class="mp-detail-h">
            <b>{{ id }} {{ device?.name }}</b>
            <span class="mp-lv info">{{ device?.model }}</span>
            <span class="mp-lv" :class="device?.real ? 'warn' : 'info'">{{
              device?.real ? '真实设备' : '模拟设备'
            }}</span>
            <span class="mp-lv info">{{ device?.transport }}</span>
          </div>
          <div class="mp-note">
            {{ device?.gatewayId }} · 从站 {{ device?.slaveId }} · {{ device?.endpoint }} · 平台从站 Unit
            {{ device?.slaveUnitId }} · {{ device?.serial }}
          </div>
        </div>
      </div>
    </div>

    <div class="mp-grid-three">
      <div v-for="point in devicePoints.slice(0, 4)" :key="point.key" class="mp-kpi">
        <span>{{ point.name }} · {{ point.type }}</span>
        <b>{{ fmtValue(point.value, point.type) }} {{ point.unit }}</b>
        <em>0x{{ hex4(point.address) }} · {{ point.writable ? '读写' : '只读' }}</em>
      </div>
      <div v-if="!devicePoints.length" class="mp-note">等待点表加载…</div>
    </div>

    <div class="mp-panel">
      <div class="mp-panel-h">
        <h3>寄存器编组</h3>
        <span>点表驱动：每个点位按地址展开，读取时用 count 一次取回</span>
      </div>
      <div class="mp-chips">
        <button
          v-for="item in groups"
          :key="item.key"
          type="button"
          class="mp-chip"
          :class="{ on: item.key === groupKey }"
          @click="selectGroup(item.key)"
        >
          {{ item.name }} <i>×{{ item.count }}{{ item.rw ? ' · 写' : '' }}</i>
        </button>
      </div>
      <div v-if="mapCells.length" class="mp-map">
        <button
          v-for="cell in mapCells"
          :key="cell.address"
          type="button"
          class="mp-mapcell"
          :class="[cell.rw ? 'rw' : 'ro', { sel: cell.group === groupKey }]"
          @click="selectGroup(cell.group)"
        >
          {{ cell.label }}
        </button>
      </div>
      <div class="mp-table-wrap">
        <table class="mp-table mp-regtable" style="margin-top: 12px">
          <thead>
            <tr>
              <th>名称</th>
              <th>类型</th>
              <th>地址</th>
              <th>原始字节</th>
              <th>解析值</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="item in groups"
              :key="item.key"
              :class="{ on: item.key === groupKey }"
              @click="selectGroup(item.key)"
            >
              <td>
                <b>{{ item.name }}</b>
              </td>
              <td class="num">{{ item.type }}</td>
              <td class="num">
                0x{{ hex4(item.start)
                }}<template v-if="item.count > 1">–0x{{ hex4(item.start + item.count - 1) }}</template>
              </td>
              <td class="num">{{ item.bytes }}</td>
              <td class="num">{{ item.value }}</td>
              <td>
                <button type="button" class="mp-mini" @click.stop="selectAndRead(item.key)">读</button>
                <button
                  v-if="item.rw"
                  type="button"
                  class="mp-mini"
                  style="margin-left: 4px"
                  @click.stop="selectAndWrite(item.key)"
                >
                  写
                </button>
              </td>
            </tr>
            <tr v-if="!groups.length">
              <td colspan="6" class="mp-note">等待点表加载…</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="mp-panel" v-if="group">
      <div class="mp-panel-h">
        <h3>多寄存器读取</h3>
        <span
          >{{ group.name }} · {{ group.count }} 个寄存器 ·
          {{ device?.transport === 'TCP' ? 'TCP 帧（MBAP）' : 'RTU 帧（CRC）' }}</span
        >
      </div>
      <div class="mp-regd">
        <div>
          <div class="mp-detail-h">
            <b>{{ group.name }}</b
            ><span class="mp-lv info">{{ group.type }}</span>
          </div>
          <div class="mp-note">
            0x{{ hex4(group.start) }}–0x{{ hex4(group.start + group.count - 1) }} · {{ group.count }} 个寄存器
            · {{ group.rw ? '读写' : '只读' }}
          </div>
          <div class="mp-regcells" style="margin-top: 8px">
            <div v-for="cell in group.cells" :key="cell.address" class="mp-regcell">
              <span>0x{{ hex4(cell.address) }}</span>
              <b>{{ hex4(cell.value) }}</b>
            </div>
          </div>
          <div class="mp-regbytes">
            <span>字节 {{ letters }}</span>
            <code>{{ group.bytes }}</code>
            <span>→</span>
            <b>{{ group.value }}</b>
          </div>
        </div>
        <div>
          <div class="mp-frames">
            <div>
              <span>请求</span><code>{{ group.readRequest }}</code>
            </div>
            <div>
              <span>响应</span><code>{{ group.readResponse }}</code>
            </div>
          </div>
          <div v-if="group.rw" class="mp-frames">
            <div>
              <span>写请求</span><code>{{ group.writeRequest }}</code>
            </div>
          </div>
          <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px">
            <button type="button" class="mp-btn" @click="readGroup">读这一组</button>
            <button v-if="group.rw" type="button" class="mp-btn" @click="writeGroup">写这一组</button>
          </div>
        </div>
      </div>
      <div class="mp-readstatus">{{ readStatus }}</div>
      <div class="mp-note" style="margin-top: 8px">
        count=N 表示一次事务读 N 个寄存器；响应字节数是 2N。跨寄存器字段必须一次读回。
      </div>
    </div>

    <div class="mp-panel" v-if="longString">
      <div class="mp-panel-h">
        <h3>长字符串：存储与解析</h3>
        <span>{{ longString.length }} B · UTF-8</span>
      </div>
      <div class="mp-longid">
        <span class="mp-note">设备编号</span>
        <code>{{ longString.value }}</code>
      </div>
      <div class="mp-flow">
        <button
          v-for="(label, index) in longSteps"
          :key="label"
          type="button"
          class="mp-lstep"
          :class="{ on: longStep === index }"
          @click="longStep = index"
        >
          <b>{{ index + 1 }}</b
          >{{ label }}
        </button>
      </div>
      <div class="mp-longmeta">
        <span
          >长度 <b>{{ longString.length }} B</b></span
        >
        <span
          >寄存器 <b>{{ longString.registerCount }}</b></span
        >
        <span
          >分片 <b>{{ longString.chunkCount }}</b></span
        >
        <span>校验 <b>CRC16</b></span>
      </div>

      <div class="mp-longdetail">
        <template v-if="longStep === 0">
          <b>字符串 → UTF-8 字节</b> 先编码，长度单独记下来
          <code>{{ longBytes.join(' ') }}</code>
          <div class="mp-note">{{ longString.length }} B；ASCII 1 B/字符，中文 3 B/字符。</div>
        </template>
        <template v-else-if="longStep === 1">
          <b>两字节一个寄存器，高字节在前</b> 奇数长度，最后一个补 00
          <code>
            <template v-for="(reg, index) in longExamples" :key="reg.address">
              0x{{ hex4(reg.address) }} = {{ hex4(reg.value) }} "{{ reg.chars }}"<br
                v-if="index < longExamples.length - 1"
              />
            </template>
          </code>
          <div class="mp-note">{{ longString.length }} B → {{ longString.registerCount }} 个寄存器。</div>
        </template>
        <template v-else-if="longStep === 2">
          <b>按 16 个寄存器一片切开</b> 16 × 2 B = 32 B/片
          <code>
            <template v-for="(chunk, index) in longString.chunks" :key="chunk.index">
              片 {{ index + 1 }}：{{ chunk.range }} · {{ chunk.count }} 个寄存器 · {{ chunk.bytes }} B<br />
            </template>
          </code>
        </template>
        <template v-else-if="longStep === 3">
          <b>先写控制区</b> 接收方靠它知道要收多少、收到哪
          <code>
            <template v-for="control in longString.control" :key="control.address">
              0x{{ hex4(control.address) }} {{ control.name }} = {{ control.value }}<br />
            </template>
          </code>
        </template>
        <template v-else-if="longStep === 4">
          <b>用 16 功能码写片 1</b>
          <code>{{ longString.writeFrames[0] }}</code>
        </template>
        <template v-else-if="longStep === 5">
          <b>用 16 功能码写片 2</b>
          <code>{{ longString.writeFrames[1] || '单片不需要第二片' }}</code>
        </template>
        <template v-else>
          <b>先读长度，再按长度截断</b> 不能把填充的 00 当字符
          <code>
            读 0x0100 → {{ longString.length }}<br />
            读 0x0110 起 {{ longString.registerCount }} 个寄存器 →
            {{ longString.registerCount * 2 }} 字节<br />
            {{ longString.readRequest }}<br />
            {{ longResponsePreview }}
          </code>
          <div class="mp-note">
            截取前 {{ longString.length }} B → UTF-8 解码 → CRC {{ hex4(longString.crc) }} 校验通过 →
            设备编号恢复。
          </div>
          <code>{{ longString.value }}</code>
        </template>
      </div>

      <div class="mp-note" style="margin-top: 10px">控制区</div>
      <div class="mp-lctrl">
        <div v-for="control in longString.control" :key="control.address" :class="{ on: longStep >= 3 }">
          <span>0x{{ hex4(control.address) }} {{ control.name }}</span>
          <b>{{ hex4(control.value) }}</b>
          <i>{{ control.display }}</i>
        </div>
      </div>

      <div class="mp-note" style="margin-top: 8px">数据区（0x0110 起）</div>
      <div class="mp-lregs">
        <div
          v-for="(reg, index) in longString.registers"
          :key="reg.address"
          class="mp-lreg"
          :class="longClass(index)"
        >
          <span>0x{{ hex4(reg.address) }}</span>
          <b>{{ hex4(reg.value) }}</b>
          <i>{{ reg.chars }}</i>
        </div>
      </div>

      <div class="mp-readstatus">{{ longNotes[longStep] }}</div>
      <div style="display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; align-items: center">
        <button type="button" class="mp-btn" :disabled="longStep === 0" @click="longStep--">上一步</button>
        <button type="button" class="mp-btn primary" :disabled="longStep === 6" @click="longStep++">
          下一步
        </button>
        <button type="button" class="mp-btn ghost" @click="longStep = 0">重置</button>
        <span class="mp-note">步骤 {{ longStep + 1 }} / 7</span>
      </div>
    </div>
  </div>
</template>
