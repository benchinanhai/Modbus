<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { get } from '../api'

const route = useRoute()
const threads = ref({ lanes: [], now: Date.now() })
const mode = ref(route.query.mode === 'bad' ? 'bad' : 'ok')
const selected = ref(null)
const SLOTS = 14
let loadTimer = null

async function load() {
  try {
    threads.value = await get('/threads')
  } catch (error) {
    console.warn('threads failed', error)
  }
}

const lanes = computed(() => threads.value.lanes || [])
const allOps = computed(() => lanes.value.flatMap((lane) => (lane.ops || []).slice(-SLOTS)))
const queueTotal = computed(() => lanes.value.reduce((sum, lane) => sum + lane.queueDepth, 0))

const displayLanes = computed(() => {
  if (mode.value === 'ok') {
    return lanes.value.map((lane) => ({
      label: `${lane.gatewayId} · ${lane.thread}`,
      state: lane.state,
      ops: (lane.ops || []).slice(-SLOTS),
      meta: `${lane.queueDepth} 个待处理`
    }))
  }
  const merged = []
  for (let slot = 0; slot < SLOTS; slot++) {
    lanes.value.forEach((lane, laneIndex) => {
      const ops = (lane.ops || []).slice(-SLOTS)
      if (ops[slot]) merged.push({ ...ops[slot], stack: laneIndex })
    })
  }
  return [
    {
      label: 'GW-01 · 共用 COM3',
      state: '3 线程争抢',
      ops: merged,
      meta: '半双工总线'
    }
  ]
})

function opStyle(op, index) {
  const slot = mode.value === 'bad' ? Math.floor(index / 3) : index
  const left = Math.min(96, (slot / SLOTS) * 100)
  const top = mode.value === 'bad' ? 3 + (op.stack ?? index % 3) * 11 : 7
  return {
    left: left + '%',
    top: top + 'px',
    width: mode.value === 'bad' ? `calc(${100 / SLOTS}% - 12px)` : `calc(${100 / SLOTS}% - 5px)`
  }
}

function pick(op) {
  selected.value = op
}

onMounted(async () => {
  await load()
  loadTimer = setInterval(load, 1000)
})

onUnmounted(() => {
  clearInterval(loadTimer)
})
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>并发线程：三条泳道，一个约束</h2>
        <p>3 个通道就是 3 个 IO 线程；通道内串行，通道间并行</p>
      </div>
      <div class="mp-seg">
        <button type="button" :class="{ on: mode === 'ok' }" @click="mode = 'ok'">正确：3 通道 3 线程</button>
        <button type="button" :class="{ on: mode === 'bad' }" @click="mode = 'bad'">
          错误：抢同一个串口
        </button>
      </div>
    </div>

    <div class="mp-panel">
      <div class="mp-stats">
        <span>活跃线程 <b>3</b></span>
        <span
          >队列深度 <b>{{ queueTotal }}</b></span
        >
        <span
          >窗口内事务 <b>{{ allOps.length }}</b></span
        >
        <span
          >CRC 冲突
          <b :style="{ color: mode === 'bad' ? 'var(--bad)' : 'var(--ok)' }">{{
            mode === 'bad' ? allOps.length * 3 : 0
          }}</b></span
        >
      </div>

      <div class="mp-gaxis">
        <span></span>
        <div class="ticks">
          <span>14 次前</span><span>10</span><span>6</span><span>2</span><span>现在</span>
        </div>
      </div>

      <div class="mp-lanes">
        <div v-for="lane in displayLanes" :key="lane.label" class="mp-lane">
          <div class="mp-lane-label">
            <b>{{ lane.label }}</b>
            <span>
              <span class="mp-gstate" :class="{ run: lane.state !== 'WAITING' }">{{ lane.state }}</span>
              {{ lane.meta }}
            </span>
          </div>
          <div class="mp-track">
            <div
              v-for="(op, index) in lane.ops"
              :key="op.id"
              class="mp-op"
              :class="{ write: op.kind === 'write', bad: mode === 'bad' }"
              :style="opStyle(op, index)"
              :title="`${op.deviceId} · ${op.kind} · ${op.durationMs.toFixed(1)} ms`"
              @click="pick(op)"
            >
              {{ mode === 'bad' ? '' : op.deviceId }}
            </div>
          </div>
        </div>
      </div>

      <div class="mp-gdetail">
        <template v-if="selected">
          {{ selected.deviceId }} · {{ selected.kind === 'write' ? 'FC06 写' : 'FC03 读' }} ·
          {{ selected.durationMs.toFixed(1) }} ms · {{ selected.lane }}
        </template>
        <template v-else>点任意事务条查看线程与设备</template>
      </div>
      <div class="mp-note" style="margin-top: 6px">
        每格 = 一次事务，按顺序排列；宽度不代表耗时，实际耗时点开查看。
      </div>

      <div class="mp-band" style="margin-top: 10px">
        <template v-if="mode === 'ok'">
          同一泳道内事务不重叠，就是串行；三条泳道同时有事务，就是并行。
        </template>
        <template v-else>
          帧在总线上交错，CRC 校验失败，所有线程一起超时——这就是串口必须独占的原因。
        </template>
      </div>
    </div>

    <div class="mp-split">
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>线程模型</h3>
          <span>调度器只投递，不碰串口</span>
        </div>
        <div class="mp-kv">
          <div><span>调度</span><b>poll-scheduler · 每 1 s</b></div>
          <div><span>通道</span><b>io-com3 / io-com4 / io-tcp</b></div>
          <div><span>队列</span><b>PriorityBlockingQueue</b></div>
          <div><span>优先级</span><b>写 0 · 轮询 1</b></div>
          <div><span>并行度</span><b>通道数，不是线程池大小</b></div>
        </div>
      </div>
      <div class="mp-panel">
        <div class="mp-panel-h">
          <h3>为什么不是“线程池 + 锁”</h3>
          <span>物理层约束</span>
        </div>
        <div class="mp-note">
          锁只能把并发变回串行，还会带来锁竞争、超时放大和不可控的排队顺序。
          一条通道一个单线程执行器，队列天然给出背压；跨通道才并行。
        </div>
        <div class="mp-band" style="margin-top: 12px">
          Spring Boot 里对应 `ThreadPoolExecutor(1, 1, …, ArrayBlockingQueue)`，每通道一个。
        </div>
      </div>
    </div>
  </div>
</template>
