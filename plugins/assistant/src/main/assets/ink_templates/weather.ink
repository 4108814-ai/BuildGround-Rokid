<script type="application/json" def>
{
  "data": {
    "title": "Weather",
    "location": "",
    "temperature": "18°",
    "condition": "Clear",
    "high": "",
    "low": "",
    "precipitation": "",
    "humidity": "",
    "wind": "",
    "hourly": [],
    "forecast": []
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
          <text class="detail" wx:if="{{ high }}">H {{ high }}</text>
          <text class="detail" wx:if="{{ low }}">L {{ low }}</text>
        </view>
      </view>
      <view class="details" wx:if="{{ precipitation || humidity || wind }}">
        <text class="detail" wx:if="{{ precipitation }}">Precip {{ precipitation }}</text>
        <text class="detail" wx:if="{{ humidity }}">Humidity {{ humidity }}</text>
        <text class="detail" wx:if="{{ wind }}">Wind {{ wind }}</text>
      </view>
    </view>
    <chart
      wx:if="{{ hourly[1] }}"
      class="curve"
      type="area"
      series="temp"
      data="{{ hourly }}"
      smooth="true"
      animate="true"
      show-value-labels="true" />
    <view class="forecast" wx:if="{{ forecast[0] }}">
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
  gap: 16rpx;
  width: 100%;
  box-sizing: border-box;
  padding: 18rpx;
  color: var(--color-text-primary);
}
.header {
  display: flex;
  flex-direction: row;
  align-items: flex-end;
  justify-content: space-between;
  gap: 12rpx;
}
.title {
  color: var(--color-primary);
  font-size: 26rpx;
  font-weight: 700;
  line-height: 32rpx;
}
.location {
  color: var(--color-text-secondary);
  font-size: 18rpx;
  line-height: 24rpx;
  opacity: 0.72;
  white-space: nowrap;
}
.current {
  display: flex;
  flex-direction: row;
  align-items: center;
  justify-content: space-between;
  gap: 20rpx;
}
.temperature {
  color: var(--color-primary);
  font-size: 52rpx;
  font-weight: 700;
  line-height: 58rpx;
  flex-shrink: 0;
  white-space: nowrap;
}
.summary {
  display: flex;
  flex-direction: column;
  gap: 4rpx;
  flex-grow: 1;
}
.details {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 4rpx;
}
.condition {
  color: var(--color-text-primary);
  font-size: 22rpx;
  font-weight: 600;
  line-height: 28rpx;
}
.range {
  display: flex;
  flex-direction: row;
  gap: 14rpx;
}
.detail {
  color: var(--color-text-secondary);
  font-size: 15rpx;
  line-height: 20rpx;
  opacity: 0.72;
}
.curve {
  width: 100%;
  height: 190rpx;
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
