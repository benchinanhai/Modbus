<script setup>
import { computed, ref } from 'vue'
import { useLiveStore } from '../stores/live'

const store = useLiveStore()
const level = ref('ALL')
const search = ref('')
const paused = ref(false)
const frozen = ref([])
const selected = ref(null)

const filtered = computed(() => {
  const query = search.value.trim().toLowerCase()
  return (store.logs || []).filter((entry) => {
    if (level.value !== 'ALL' && entry.level !== level.value) return false
    if (!query) return true
    return `${entry.source} ${entry.message} ${entry.traceId}`.toLowerCase().includes(query)
  })
})

const rows = computed(() => (paused.value ? frozen.value : filtered.value).slice(0, 12))

function togglePause() {
  if (!paused.value) {
    frozen.value = filtered.value.slice()
  } else {
    frozen.value = []
  }
  paused.value = !paused.value
}

function show(entry) {
  selected.value = entry
}

function time(ts) {
  return new Date(ts).toLocaleTimeString('zh-CN', { hour12: false })
}
</script>

<template>
  <div class="mp-view">
    <div class="mp-viewhead">
      <div>
        <h2>日志中心</h2>
        <p>一条 trace 串起：入队、发帧、响应、解码、推送</p>
      </div>
      <button type="button" class="mp-btn" @click="togglePause">
        {{ paused ? '继续刷新' : '暂停刷新' }}
      </button>
    </div>

    <div class="mp-panel">
      <div class="mp-logbar">
        <div class="mp-seg">
          <button type="button" :class="{ on: level === 'ALL' }" @click="level = 'ALL'">全部</button>
          <button type="button" :class="{ on: level === 'INFO' }" @click="level = 'INFO'">INFO</button>
          <button type="button" :class="{ on: level === 'WARN' }" @click="level = 'WARN'">WARN</button>
          <button type="button" :class="{ on: level === 'ERROR' }" @click="level = 'ERROR'">ERROR</button>
        </div>
        <input
          v-model="search"
          class="mp-input"
          placeholder="搜索设备、trace 或关键字"
          style="flex: 1; min-width: 160px"
        />
        <span class="mp-note">显示 {{ rows.length }} 条 / 共 {{ store.logs.length }} 条</span>
      </div>

      <table class="mp-table">
        <thead>
          <tr>
            <th>时间</th>
            <th>级别</th>
            <th>来源</th>
            <th>消息</th>
            <th class="mp-hide-sm">trace</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="entry in rows" :key="entry.id" class="mp-logrow" @click="show(entry)">
            <td class="num">{{ time(entry.ts) }}</td>
            <td>
              <span class="mp-lv" :class="entry.level.toLowerCase()">{{ entry.level }}</span>
            </td>
            <td class="num">{{ entry.source }}</td>
            <td class="mp-logmsg">{{ entry.message }}</td>
            <td class="num mp-hide-sm">{{ entry.traceId }}</td>
          </tr>
          <tr v-if="!rows.length">
            <td colspan="5" class="mp-note">没有匹配的日志</td>
          </tr>
        </tbody>
      </table>

      <div v-if="selected" class="mp-logdetail">
        <div class="mp-panel-h">
          <h3>日志详情</h3>
          <span>{{ selected.traceId }}</span>
        </div>
        <pre>{{
          JSON.stringify(
            {
              time: new Date(selected.ts).toISOString(),
              level: selected.level,
              source: selected.source,
              trace_id: selected.traceId,
              message: selected.message,
              context: { gateway: 'GW-01', channel: 'COM3', slave: 1, function: '03', address: '0x0000' }
            },
            null,
            2
          )
        }}</pre>
      </div>
    </div>
  </div>
</template>
