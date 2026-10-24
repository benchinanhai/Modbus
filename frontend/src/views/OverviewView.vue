<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useLiveStore } from '../stores/live'

const store = useLiveStore()
const router = useRouter()
const selected = ref('T-01')

const gateways = computed(() => (store.gateways.length ? store.gateways : store.overview?.gateways || []))
const events = computed(() => store.overview?.events || [])
const stats = computed(() => store.stats)
const selectedDevice = computed(() => store.deviceMap[selected.value])
const devices = computed(() => store.devices)

const spark = computed(() => {
  const values = selectedDevice.value?.history || []
  if (values.length < 2) {
    return { line: '', area: '', lastX: 0, lastY: 0, min: '--', max: '--' }
  }
  const width = 320
  const height = 110
  const pad = 8
  const min = Math.min(...values) - 0.5
  const max = Math.max(...values) + 0.5
  const x = (index) => pad + (index * (width - 2 * pad)) / (values.length - 1)
  const y = (value) => height - pad - ((value - min) / (max - min)) * (height - 2 * pad)
  const line = values
    .map((value, index) => `${index ? 'L' : 'M'}${x(index).toFixed(1)} ${y(value).toFixed(1)}`)
    .join(' ')
  const area = `${line} L${x(values.length - 1).toFixed(1)} ${height - pad} L${x(0).toFixed(1)} ${height - pad} Z`
  return {
    line,
    area,
    lastX: x(values.length - 1),
    lastY: y(values[values.length - 1]),
    min: min.toFixed(1),
    max: max.toFixed(1)
  }
})

function openDevice(id) {
  router.push(`/devices/${id}`)
}
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>运行总览</h2>
        <p>{{ gateways.length }} 个网关 · {{ stats.online }} 台设备 · {{ stats.points }} 个采集点位</p>
      </div>
      <router-link class="mp-btn primary" to="/pipeline">看一次采集</router-link>
    </div>

    <div class="mp-kpis">
      <div class="mp-kpi">
        <span>在线设备</span><b>{{ stats.online }}</b
        ><em>台</em>
      </div>
      <div class="mp-kpi">
        <span>采集点位</span><b>{{ stats.points }}</b
        ><em>个</em>
      </div>
      <div class="mp-kpi">
        <span>采集成功率</span><b>{{ stats.successRate }}</b
        ><em>% · 滚动窗口</em>
      </div>
      <div class="mp-kpi">
        <span>P95 延迟</span><b>{{ stats.p95 }}</b
        ><em>ms</em>
      </div>
    </div>

    <div class="mp-grid-main">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>现场拓扑</h3>
          <span>点设备进入详情</span>
        </div>
        <div class="mp-topo">
          <div v-for="gateway in gateways" :key="gateway.id" class="mp-topo-row">
            <button class="mp-gw" type="button" @click="router.push('/gateways')">
              <span class="mp-gw-name">{{ gateway.id }}</span>
              <span class="mp-gw-meta"
                >{{ gateway.transport }} · {{ gateway.deviceCount }} 台{{
                  gateway.real ? ' · 真实' : ''
                }}</span
              >
            </button>
            <span class="mp-link-line"></span>
            <div class="mp-devs">
              <button
                v-for="device in gateway.devices"
                :key="device.id"
                type="button"
                class="mp-dev"
                @click="openDevice(device.id)"
              >
                <span
                  class="mp-dot"
                  :class="{ warn: (store.deviceMap[device.id] || device).quality !== 'good' }"
                ></span>
                <span>{{ device.id }}</span>
                <b>{{ (store.deviceMap[device.id] || device).temperature.toFixed(1) }}</b>
                <span class="mp-unit">°C</span>
              </button>
            </div>
          </div>
        </div>
        <div class="mp-band" style="margin-top: 12px">
          一个网关下挂多个从站；同一通道严格串行，不同通道并行采集。
        </div>
      </div>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>温度趋势</h3>
          <select v-model="selected" class="mp-select" style="height: 26px; font-size: 11px">
            <option v-for="device in devices" :key="device.id" :value="device.id">
              {{ device.id }} · {{ device.name }}
            </option>
          </select>
        </div>
        <svg class="mp-spark" viewBox="0 0 320 110" role="img" aria-label="选中设备的温度趋势">
          <path :d="spark.area" class="area"></path>
          <path :d="spark.line" class="line"></path>
          <circle :cx="spark.lastX" :cy="spark.lastY" r="3" class="last"></circle>
        </svg>
        <div class="mp-legend">
          <span>最近 60 个采样点</span><span>{{ spark.min }} – {{ spark.max }} °C</span>
        </div>
        <div class="mp-row" style="margin-top: 8px">
          <span class="mp-grow">当前值</span>
          <b class="mono">{{ selectedDevice ? selectedDevice.temperature.toFixed(1) : '--' }} °C</b>
        </div>
        <div class="mp-row">
          <span class="mp-grow">质量</span>
          <b :class="selectedDevice?.quality === 'good' ? 'ok' : 'bad'">{{
            selectedDevice?.quality || '--'
          }}</b>
        </div>
      </div>
    </div>

    <div class="mp-grid-two">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>最近事件</h3>
          <span>质量变化与通道告警</span>
        </div>
        <div class="mp-list">
          <div v-for="event in events" :key="event.id" class="mp-row">
            <span class="mp-t">{{ new Date(event.ts).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
            <span
              class="mp-dot"
              :class="{ warn: event.level === 'WARN', bad: event.level === 'ERROR' }"
            ></span>
            <span class="mp-grow">{{ event.source }} · {{ event.message }}</span>
          </div>
          <div v-if="!events.length" class="mp-note">暂无事件</div>
        </div>
      </div>

      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>通道负载</h3>
          <span>队列深度 / 总线利用率</span>
        </div>
        <div class="mp-load">
          <div v-for="gateway in gateways" :key="gateway.id" class="mp-load-row">
            <span>{{ gateway.id }}</span>
            <div class="mp-bar"><i :style="{ width: gateway.utilization + '%' }"></i></div>
            <span>Q{{ gateway.queueDepth }} · {{ gateway.utilization }}%</span>
          </div>
        </div>
        <div class="mp-note" style="margin-top: 10px">队列持续增长，说明采集周期已经超过总线能力。</div>
      </div>
    </div>
  </div>
</template>
