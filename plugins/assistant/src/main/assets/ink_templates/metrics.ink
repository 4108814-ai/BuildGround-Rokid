<script type="application/json" def>
{
  "data": {
    "title": "Metrics",
    "cells": [
      { "label": "Active", "value": "24", "detail": "Today" },
      { "label": "Average", "value": "18 min", "detail": "Last 7 days" }
    ]
  }
}
</script>

<page>
  <view class="page">
    <text class="title">{{ title }}</text>
    <view class="cells">
      <view class="cell" wx:for="{{ cells }}">
        <text class="label">{{ item.label }}</text>
        <text class="value">{{ item.value }}</text>
        <text class="detail" wx:if="{{ item.detail }}">{{ item.detail }}</text>
      </view>
    </view>
  </view>
</page>

<style>
.page {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  width: 100%;
  box-sizing: border-box;
  padding: 18rpx;
  color: var(--color-text-primary);
  background-color: var(--color-background);
}
.title {
  color: var(--color-primary);
  font-size: 34rpx;
  font-weight: 700;
  line-height: 40rpx;
}
.cells {
  display: flex;
  flex-direction: row;
  flex-wrap: wrap;
  gap: 10rpx;
}
.cell {
  display: flex;
  flex-direction: column;
  gap: 5rpx;
  flex-grow: 1;
  flex-basis: 40%;
  min-width: 0rpx;
  box-sizing: border-box;
  padding: 12rpx;
  background-color: var(--color-background);
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-sm, 18rpx);
}
.label {
  color: var(--color-text-secondary);
  font-size: 19rpx;
  line-height: 24rpx;
  opacity: 0.7;
}
.value {
  color: var(--color-primary);
  font-size: 32rpx;
  font-weight: 700;
  line-height: 38rpx;
}
.detail {
  color: var(--color-text-secondary);
  font-size: 17rpx;
  line-height: 22rpx;
  opacity: 0.58;
}
</style>
