<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { del, get, post, upload } from '../api'

/**
 * 真实设备接入页。
 *
 * 流程：测试连接（FC03 试读）→ 编辑点表（可 Excel 导入）→ 接入平台。
 * 接入成功后后端持久化配置、创建通道并开始轮询；同时可以查看平台
 * 作为 Modbus TCP 从站对外的 Unit 映射。
 */
const form = reactive({
  transport: 'TCP',
  host: '127.0.0.1',
  port: 502,
  serialPort: '/dev/ttyUSB0',
  baud: 9600,
  parity: 'N',
  dataBits: 8,
  stopBits: 1,
  unitId: 1,
  name: '面试官真实设备',
  model: 'REAL-TCP',
  serial: '',
  slaveUnitId: 1
})

const pointRows = ref(defaultPoints())
const probe = ref(null)
const probeError = ref('')
const parseErrors = ref([])
const devices = ref([])
const slave = ref(null)
const message = ref('')
const error = ref('')
const busy = ref(false)

const isTcp = computed(() => form.transport === 'TCP')
const endpoint = computed(() =>
  isTcp.value ? `${form.host}:${form.port}` : `${form.serialPort} · ${form.baud}`
)

function defaultPoints() {
  return [
    {
      key: 'temp',
      name: '温度',
      function: 3,
      address: 0,
      type: 'float32',
      order: 'ABCD',
      scale: 1,
      unit: '°C',
      writable: false
    },
    {
      key: 'hum',
      name: '湿度',
      function: 3,
      address: 2,
      type: 'int16',
      order: 'ABCD',
      scale: 0.1,
      unit: '%',
      writable: false
    },
    {
      key: 'cur',
      name: '电流',
      function: 3,
      address: 3,
      type: 'int16',
      order: 'ABCD',
      scale: 0.1,
      unit: 'mA',
      writable: false
    },
    {
      key: 'sp',
      name: '设定值',
      function: 3,
      address: 8,
      type: 'uint16',
      order: 'ABCD',
      scale: 0.1,
      unit: '',
      writable: true
    },
    {
      key: 'alarmLo',
      name: '报警下限',
      function: 3,
      address: 9,
      type: 'uint16',
      order: 'ABCD',
      scale: 0.1,
      unit: '',
      writable: true
    },
    {
      key: 'alarmHi',
      name: '报警上限',
      function: 3,
      address: 10,
      type: 'uint16',
      order: 'ABCD',
      scale: 0.1,
      unit: '',
      writable: true
    }
  ]
}

function is32Bit(point) {
  return ['int32', 'uint32', 'float32'].includes(point.type)
}

function addPoint() {
  pointRows.value.push({
    key: '',
    name: '新点位',
    function: 3,
    address: 0,
    type: 'uint16',
    order: 'ABCD',
    scale: 1,
    unit: '',
    writable: false
  })
}

function removePoint(index) {
  pointRows.value.splice(index, 1)
}

/** 探测请求只包含链路与一段寄存器，不落库。 */
function probePayload() {
  return {
    transport: form.transport,
    host: form.host,
    port: Number(form.port),
    serialPort: form.serialPort,
    baud: Number(form.baud),
    parity: form.parity,
    dataBits: Number(form.dataBits),
    stopBits: Number(form.stopBits),
    unitId: Number(form.unitId),
    address: Number(form.address),
    count: Number(form.count)
  }
}

/** 接入请求包含链路、从站 Unit 与完整点表。 */
function devicePayload() {
  return {
    name: form.name,
    model: form.model,
    transport: form.transport,
    host: form.host,
    port: Number(form.port),
    serialPort: form.serialPort,
    baud: Number(form.baud),
    parity: form.parity,
    dataBits: Number(form.dataBits),
    stopBits: Number(form.stopBits),
    slaveId: Number(form.unitId),
    slaveUnitId: Number(form.slaveUnitId),
    serial: form.serial,
    points: pointRows.value.map((point, index) => ({
      key: point.key || `p${index + 1}`,
      name: point.name,
      function: Number(point.function),
      address: Number(point.address),
      type: point.type,
      order: point.order,
      scale: Number(point.scale),
      unit: point.unit,
      writable: point.writable
    }))
  }
}

function formatFloat(value) {
  if (Math.abs(value) < 1e-6) return '≈ 0'
  if (Math.abs(value) > 1e6) return '异常值'
  return value.toFixed(2)
}

async function testConnection() {
  busy.value = true
  probeError.value = ''
  probe.value = null
  try {
    probe.value = await post('/real-devices/test', probePayload())
  } catch (e) {
    probeError.value = String(e.message || e)
  } finally {
    busy.value = false
  }
}

async function importExcel(event) {
  const file = event.target.files[0]
  if (!file) return
  busy.value = true
  parseErrors.value = []
  try {
    const result = await upload('/points/parse', file)
    pointRows.value = result.points
    parseErrors.value = result.errors
    message.value = `导入 ${result.points.length} 个点位`
  } catch (e) {
    error.value = String(e.message || e)
  } finally {
    busy.value = false
    event.target.value = ''
  }
}

async function addDevice() {
  busy.value = true
  error.value = ''
  message.value = ''
  try {
    const created = await post('/real-devices', devicePayload())
    message.value = `已接入 ${created.config.id} · ${created.config.name}，开始轮询`
    await loadDevices()
  } catch (e) {
    error.value = String(e.message || e)
  } finally {
    busy.value = false
  }
}

async function loadDevices() {
  try {
    devices.value = await get('/real-devices')
    slave.value = await get('/slave')
  } catch (e) {
    error.value = String(e.message || e)
  }
}

async function removeDevice(id) {
  if (!window.confirm(`移除真实设备 ${id}？`)) return
  try {
    await del(`/real-devices/${id}`)
    await loadDevices()
    message.value = `已移除 ${id}`
  } catch (e) {
    error.value = String(e.message || e)
  }
}

onMounted(loadDevices)
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>设备接入</h2>
        <p>
          TCP / RTU 真实设备：先测试读取，再编辑点表（支持 Excel 导入）接入；配置持久化到
          config/real-devices.json
        </p>
      </div>
      <span class="mp-pill">当前端点 {{ endpoint }}</span>
    </div>

    <div v-if="error" class="mp-band" style="border-left-color: var(--bad)">{{ error }}</div>
    <div v-if="message" class="mp-band">{{ message }}</div>

    <div class="mp-grid-two">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>1. 测试连接</h3>
          <span>只读一次，不接入</span>
        </div>

        <div class="mp-seg" style="margin-bottom: 10px">
          <button type="button" :class="{ on: form.transport === 'TCP' }" @click="form.transport = 'TCP'">
            TCP
          </button>
          <button type="button" :class="{ on: form.transport === 'RTU' }" @click="form.transport = 'RTU'">
            RTU
          </button>
        </div>

        <div class="mp-form">
          <div v-if="isTcp" class="mp-form-row">
            <label class="mp-field"
              ><span>IP / 主机</span><input v-model="form.host" class="mp-input"
            /></label>
            <label class="mp-field"
              ><span>端口</span><input v-model.number="form.port" class="mp-input" type="number"
            /></label>
          </div>
          <template v-else>
            <div class="mp-form-row">
              <label class="mp-field"
                ><span>串口</span><input v-model="form.serialPort" class="mp-input"
              /></label>
              <label class="mp-field"
                ><span>波特率</span><input v-model.number="form.baud" class="mp-input" type="number"
              /></label>
            </div>
            <div class="mp-form-row">
              <label class="mp-field">
                <span>校验位</span>
                <select v-model="form.parity" class="mp-select">
                  <option value="N">N</option>
                  <option value="E">E</option>
                  <option value="O">O</option>
                </select>
              </label>
              <label class="mp-field">
                <span>数据位 / 停止位</span>
                <input class="mp-input" :value="`${form.dataBits} / ${form.stopBits}`" disabled />
              </label>
            </div>
          </template>

          <div class="mp-form-row">
            <label class="mp-field"
              ><span>从站地址</span
              ><input v-model.number="form.unitId" class="mp-input" type="number" min="1" max="247"
            /></label>
            <label class="mp-field"
              ><span>起始地址</span
              ><input v-model.number="form.address" class="mp-input" type="number" min="0" max="65535"
            /></label>
          </div>
          <div class="mp-form-row">
            <label class="mp-field"
              ><span>读取数量</span
              ><input v-model.number="form.count" class="mp-input" type="number" min="1" max="125"
            /></label>
            <div class="mp-field">
              <span>说明</span>
              <span class="mp-note">FC03 读保持寄存器；默认 11 个覆盖默认点表。</span>
            </div>
          </div>

          <button type="button" class="mp-btn primary" :disabled="busy" @click="testConnection">
            {{ busy ? '读取中…' : '测试读取' }}
          </button>
        </div>

        <div v-if="probeError" class="mp-band" style="margin-top: 12px; border-left-color: var(--bad)">
          {{ probeError }}
        </div>

        <div v-if="probe" class="mp-probe">
          <div class="mp-panel-h" style="margin-top: 14px">
            <h3>原始寄存器</h3>
            <span>{{ probe.registers.length }} 个</span>
          </div>
          <div class="mp-lregs">
            <div v-for="(hex, index) in probe.decoded.hex" :key="index" class="mp-lreg">
              <span>0x{{ (form.address + index).toString(16).toUpperCase().padStart(4, '0') }}</span>
              <b>{{ hex }}</b>
              <i>{{ probe.decoded.uint16[index] }}</i>
            </div>
          </div>

          <div class="mp-panel-h" style="margin-top: 14px">
            <h3>按不同类型试解</h3>
            <span>先确认字节序</span>
          </div>
          <table class="mp-table">
            <thead>
              <tr>
                <th>类型</th>
                <th>结果</th>
              </tr>
            </thead>
            <tbody>
              <tr>
                <td>uint16（前 4 个）</td>
                <td class="num">{{ probe.decoded.uint16.slice(0, 4).join(', ') }}</td>
              </tr>
              <tr>
                <td>int16（前 4 个）</td>
                <td class="num">{{ probe.decoded.int16.slice(0, 4).join(', ') }}</td>
              </tr>
              <tr v-for="(value, order) in probe.decoded.float32" :key="order">
                <td class="num">float32 · {{ order }}</td>
                <td class="num">{{ formatFloat(value) }}</td>
              </tr>
              <tr>
                <td>ASCII</td>
                <td class="num">{{ probe.decoded.ascii || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>2. 接入信息</h3>
          <span>默认点表可在下方修改或 Excel 导入</span>
        </div>
        <div class="mp-form">
          <div class="mp-form-row">
            <label class="mp-field"
              ><span>设备名称</span><input v-model="form.name" class="mp-input"
            /></label>
            <label class="mp-field"
              ><span>设备型号</span><input v-model="form.model" class="mp-input"
            /></label>
          </div>
          <label class="mp-field">
            <span>设备编号（用于长字符串演示，可留空）</span>
            <input v-model="form.serial" class="mp-input" placeholder="DEV-REAL-CN-SH-2026-..." />
          </label>
          <label class="mp-field">
            <span>平台从站 Unit ID（SCADA 通过它访问这台设备）</span>
            <input v-model.number="form.slaveUnitId" class="mp-input" type="number" min="1" max="247" />
          </label>
          <button type="button" class="mp-btn primary" :disabled="busy" @click="addDevice">
            {{ busy ? '接入中…' : '接入并开始轮询' }}
          </button>
          <div class="mp-note">
            平台同时是从站：外部主站用 TCP 端口 + 这里的 Unit ID 即可读写这台设备的点位。
          </div>
        </div>
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-panel-h">
        <h3>3. 点表</h3>
        <span>
          <a class="mp-btn small" href="/api/points/template">下载模板</a>
          <label class="mp-btn small" style="margin-left: 6px">
            Excel 导入<input type="file" accept=".xlsx" hidden @change="importExcel" />
          </label>
          <button type="button" class="mp-btn small" style="margin-left: 6px" @click="addPoint">
            添加点位
          </button>
        </span>
      </div>

      <div
        v-if="parseErrors.length"
        class="mp-band"
        style="border-left-color: var(--bad); margin-bottom: 10px"
      >
        <div v-for="item in parseErrors" :key="item">{{ item }}</div>
      </div>

      <div class="mp-table-wrap">
        <table class="mp-table mp-regtable">
          <thead>
            <tr>
              <th>名称</th>
              <th>功能码</th>
              <th>地址</th>
              <th>类型</th>
              <th>字序</th>
              <th>缩放</th>
              <th>单位</th>
              <th>读写</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(point, index) in pointRows" :key="index">
              <td><input v-model="point.name" class="mp-input" style="width: 110px" /></td>
              <td>
                <select v-model.number="point.function" class="mp-select" style="width: 66px">
                  <option :value="3">FC03</option>
                  <option :value="4">FC04</option>
                </select>
              </td>
              <td>
                <input
                  v-model.number="point.address"
                  class="mp-input"
                  type="number"
                  min="0"
                  max="65535"
                  style="width: 86px"
                />
              </td>
              <td>
                <select v-model="point.type" class="mp-select" style="width: 92px">
                  <option value="int16">int16</option>
                  <option value="uint16">uint16</option>
                  <option value="int32">int32</option>
                  <option value="uint32">uint32</option>
                  <option value="float32">float32</option>
                </select>
              </td>
              <td>
                <select
                  v-model="point.order"
                  class="mp-select"
                  style="width: 84px"
                  :disabled="!is32Bit(point)"
                >
                  <option>ABCD</option>
                  <option>CDAB</option>
                  <option>BADC</option>
                  <option>DCBA</option>
                </select>
              </td>
              <td>
                <input
                  v-model.number="point.scale"
                  class="mp-input"
                  type="number"
                  step="0.1"
                  style="width: 70px"
                />
              </td>
              <td><input v-model="point.unit" class="mp-input" style="width: 60px" /></td>
              <td>
                <select v-model="point.writable" class="mp-select" style="width: 82px">
                  <option :value="false">读</option>
                  <option :value="true">读写</option>
                </select>
              </td>
              <td><button type="button" class="mp-mini" @click="removePoint(index)">删</button></td>
            </tr>
            <tr v-if="!pointRows.length">
              <td colspan="9" class="mp-note">点表为空；添加点位或导入 Excel</td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mp-note" style="margin-top: 8px">
        功能码 3 = 保持寄存器，4 = 输入寄存器；32 位类型占两个寄存器，字序只对 32 位生效。
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-panel-h">
        <h3>4. 从站服务（对外）</h3>
        <span>{{ slave?.running ? `tcp/${slave.port} · 运行中` : '未启用' }}</span>
      </div>
      <table class="mp-table">
        <thead>
          <tr>
            <th>Unit</th>
            <th>设备</th>
            <th>名称</th>
            <th>地址范围</th>
            <th>可写点位</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="unit in slave?.units || []" :key="unit.unit">
            <td class="num">{{ unit.unit }}</td>
            <td class="num">{{ unit.deviceId }}</td>
            <td>{{ unit.name }}</td>
            <td class="num">
              0x{{ unit.minAddress.toString(16).toUpperCase() }} – 0x{{
                unit.maxAddress.toString(16).toUpperCase()
              }}
            </td>
            <td class="num">{{ unit.writableCount }}</td>
          </tr>
          <tr v-if="!slave?.units?.length">
            <td colspan="5" class="mp-note">接入设备后自动出现在这里</td>
          </tr>
        </tbody>
      </table>
      <div class="mp-note" style="margin-top: 8px">
        SCADA 等外部主站用 TCP 端口 + Unit 即可读写；可写点位的写命令会透传到设备并回读校验。
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-panel-h">
        <h3>5. 已接入的真实设备</h3>
        <span>配置持久化，重启自动恢复</span>
      </div>
      <table class="mp-table">
        <thead>
          <tr>
            <th>编号</th>
            <th>名称</th>
            <th>链路</th>
            <th>端点</th>
            <th>温度</th>
            <th>质量</th>
            <th>操作</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="item in devices" :key="item.config.id">
            <td class="num">{{ item.config.id }}</td>
            <td>{{ item.config.name }}</td>
            <td>{{ item.config.transport }}</td>
            <td class="num">
              {{
                item.config.transport === 'TCP'
                  ? `${item.config.host}:${item.config.port}`
                  : item.config.serialPort
              }}
            </td>
            <td class="num">{{ item.device ? item.device.temperature.toFixed(1) + ' °C' : '--' }}</td>
            <td :class="item.device?.quality === 'good' ? 'ok' : 'bad'">
              {{ item.device?.quality || 'unknown' }}
            </td>
            <td>
              <router-link class="mp-mini" :to="`/devices/${item.config.id}`">查看</router-link>
              <button
                type="button"
                class="mp-mini"
                style="margin-left: 4px"
                @click="removeDevice(item.config.id)"
              >
                移除
              </button>
            </td>
          </tr>
          <tr v-if="!devices.length">
            <td colspan="7" class="mp-note">还没有真实设备；先用上方测试读取，再接入。</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
