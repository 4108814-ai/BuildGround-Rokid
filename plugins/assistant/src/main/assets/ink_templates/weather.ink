<script type="application/json" def>
{
  "data": {
    "title": "Weather",
    "location": "",
    "temperature": "18°",
    "condition": "Clear",
    "high": "",
    "low": "",
    "forecast": [
      { "label": "Now", "temperature": "18°", "condition": "Clear" },
      { "label": "Later", "temperature": "16°", "condition": "Calm" }
    ]
  }
}
</script>

<page>
  <view class="page">
    <view class="header">
      <text class="title">{{ title }}</text>
      <text class="location" wx:if="{{ location }}">{{ location }}</text>
    </view>
    <view class="current">
      <text class="temperature">{{ temperature }}</text>
      <view class="summary">
        <text class="condition">{{ condition }}</text>
        <view class="range" wx:if="{{ high || low }}">
          <text class="range-value" wx:if="{{ high }}">H {{ high }}</text>
          <text class="range-value" wx:if="{{ low }}">L {{ low }}</text>
        </view>
      </view>
    </view>
    <view class="forecast">
      <view class="forecast-cell" wx:for="{{ forecast }}">
        <text class="forecast-label">{{ item.label }}</text>
        <text class="forecast-temperature">{{ item.temperature }}</text>
        <text class="forecast-condition" wx:if="{{ item.condition }}">{{ item.condition }}</text>
      </view>
    </view>
  </view>
</page>

<style>
.page {
  display: flex;
  flex-direction: column;
  gap: 18rpx;
  width: 100%;
  box-sizing: border-box;
  padding: 18rpx;
  color: var(--color-text-primary);
  background-color: var(--color-background);
  border-width: 2rpx;
  border-style: solid;
  border-color: var(--border-color-default);
  border-radius: var(--radius-md, 18rpx);
}
.header {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.title {
  color: var(--color-primary);
  font-size: 36rpx;
  font-weight: 700;
  line-height: 44rpx;
}
.location {
  color: var(--color-text-secondary);
  font-size: 22rpx;
  line-height: 28rpx;
  opacity: 0.72;
}
.current {
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 20rpx;
}
.temperature {
  color: var(--color-primary);
  font-size: 72rpx;
  font-weight: 700;
  line-height: 78rpx;
}
.summary {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  flex-grow: 1;
}
.condition {
  color: var(--color-text-primary);
  font-size: 28rpx;
  font-weight: 600;
  line-height: 34rpx;
}
.range {
  display: flex;
  flex-direction: row;
  gap: 14rpx;
}
.range-value {
  color: var(--color-text-secondary);
  font-size: 20rpx;
  line-height: 26rpx;
  opacity: 0.72;
}
.forecast {
  display: flex;
  flex-direction: row;
  gap: 8rpx;
}
.forecast-cell {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4rpx;
  flex-grow: 1;
  flex-basis: 0%;
  min-width: 0rpx;
  padding: 10rpx 6rpx;
  background-color: var(--color-background);
  border-width: 1rpx;
  border-style: solid;
  border-color: var(--border-color-muted);
  border-radius: var(--radius-sm, 18rpx);
}
.forecast-label {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 22rpx;
  opacity: 0.72;
}
.forecast-temperature {
  color: var(--color-text-primary);
  font-size: 26rpx;
  font-weight: 700;
  line-height: 30rpx;
}
.forecast-condition {
  color: var(--color-text-secondary);
  font-size: 16rpx;
  line-height: 20rpx;
  opacity: 0.6;
}
</style>
